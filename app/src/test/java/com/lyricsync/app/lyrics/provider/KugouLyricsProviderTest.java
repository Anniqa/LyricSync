package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

public class KugouLyricsProviderTest {

    private static final int[] KRC_KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69
    };

    private String makeMockKrcBase64(String krcText) throws Exception {
        byte[] textBytes = krcText.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater();
        deflater.setInput(textBytes);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        byte[] compressed = baos.toByteArray();

        byte[] rawKrc = new byte[4 + compressed.length];
        rawKrc[0] = 'k';
        rawKrc[1] = 'r';
        rawKrc[2] = 'c';
        rawKrc[3] = '1';
        for (int i = 0; i < compressed.length; i++) {
            rawKrc[4 + i] = (byte) (compressed[i] ^ KRC_KEY[i % KRC_KEY.length]);
        }
        return Base64.getEncoder().encodeToString(rawKrc);
    }

    @Test
    public void testGetName() {
        KugouLyricsProvider provider = new KugouLyricsProvider();
        assertEquals("Kugou", provider.getName());
    }

    @Test
    public void testFetchLyricsSuccess() throws Exception {
        String krcContent = "[ar:YOASOBI]\n[ti:アイドル]\n[1000,2000]<0,1000,0>Hello <1000,1000,0>world";
        String base64Payload = makeMockKrcBase64(krcContent);

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;
                        if (url.contains("/search")) {
                            responseJson = "{\"status\":200,\"candidates\":[{"
                                    + "\"id\":\"12345\","
                                    + "\"accesskey\":\"abcde\","
                                    + "\"song\":\"アイドル\","
                                    + "\"singer\":\"YOASOBI\","
                                    + "\"duration\":214000,"
                                    + "\"krctype\":2"
                                    + "}]}";
                        } else if (url.contains("/download")) {
                            responseJson = "{\"status\":200,\"content\":\"" + base64Payload + "\"}";
                        } else {
                            responseJson = "{}";
                        }

                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), responseJson))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "YOASOBI - アイドル (Idol) [Official Music Video]";
        track.artist = "YOASOBI";
        track.duration = 214000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Lyrics should be fetched", result);
        assertEquals(LyricsData.Type.SYLLABLE, result.type);
        assertEquals("Kugou", result.provider);
        assertFalse(result.lines.isEmpty());
    }

    @Test
    public void testFetchLyricsNoCandidate() throws Exception {
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), "{\"status\":200,\"candidates\":[]}"))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Unknown Song";
        track.artist = "Unknown Artist";

        LyricsData result = provider.fetchLyrics(track);
        assertNull("Should return null when no candidate matches", result);
    }

    @Test
    public void testFetchLyricsArtistMinusTitlePermutation() throws Exception {
        String krcContent = "[ar:The Weeknd]\n[ti:Blinding Lights]\n[1000,2000]<0,1000,0>Yeah";
        String base64Payload = makeMockKrcBase64(krcContent);

        List<String> capturedKeywords = new ArrayList<>();
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;
                        if (url.contains("/search")) {
                            String keyword = req.url().queryParameter("keyword");
                            capturedKeywords.add(keyword);
                            if ("The Weeknd - Blinding Lights".equals(keyword)) {
                                responseJson = "{\"status\":200,\"candidates\":[{"
                                        + "\"id\":\"99999\","
                                        + "\"accesskey\":\"xyz\","
                                        + "\"song\":\"Blinding Lights\","
                                        + "\"singer\":\"The Weeknd\","
                                        + "\"duration\":200000,"
                                        + "\"krctype\":2"
                                        + "}]}";
                            } else {
                                responseJson = "{\"status\":200,\"candidates\":[]}";
                            }
                        } else if (url.contains("/download")) {
                            responseJson = "{\"status\":200,\"content\":\"" + base64Payload + "\"}";
                        } else {
                            responseJson = "{}";
                        }

                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), responseJson))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Blinding Lights";
        track.artist = "The Weeknd";
        track.duration = 200000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Should fetch lyrics using Artist - Title permutation", result);
        assertFalse(capturedKeywords.isEmpty());
        assertEquals("First query should be 'Artist - Title'", "The Weeknd - Blinding Lights", capturedKeywords.get(0));
        assertEquals("Should break immediately on finding krctype == 2 candidate", 1, capturedKeywords.size());
    }

    @Test
    public void testFetchLyricsPermutationFallback() throws Exception {
        String krcContent = "[ar:The Weeknd]\n[ti:Blinding Lights]\n[1000,2000]<0,1000,0>Yeah";
        String base64Payload = makeMockKrcBase64(krcContent);

        List<String> capturedKeywords = new ArrayList<>();
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;
                        if (url.contains("/search")) {
                            String keyword = req.url().queryParameter("keyword");
                            capturedKeywords.add(keyword);
                            // Only match on 2nd permutation: Title - Artist
                            if ("Blinding Lights - The Weeknd".equals(keyword)) {
                                responseJson = "{\"status\":200,\"candidates\":[{"
                                        + "\"id\":\"99999\","
                                        + "\"accesskey\":\"xyz\","
                                        + "\"song\":\"Blinding Lights\","
                                        + "\"singer\":\"The Weeknd\","
                                        + "\"duration\":200000,"
                                        + "\"krctype\":2"
                                        + "}]}";
                            } else {
                                responseJson = "{\"status\":200,\"candidates\":[]}";
                            }
                        } else if (url.contains("/download")) {
                            responseJson = "{\"status\":200,\"content\":\"" + base64Payload + "\"}";
                        } else {
                            responseJson = "{}";
                        }

                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), responseJson))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Blinding Lights";
        track.artist = "The Weeknd";
        track.duration = 200000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Should fetch lyrics on 2nd permutation", result);
        assertTrue("Should have tried at least 2 queries", capturedKeywords.size() >= 2);
        assertEquals("The Weeknd - Blinding Lights", capturedKeywords.get(0));
        assertEquals("Blinding Lights - The Weeknd", capturedKeywords.get(1));
    }

    @Test
    public void testFetchLyricsRelaxedDurationTolerance() throws Exception {
        String krcContent = "[ar:The Weeknd]\n[ti:Blinding Lights]\n[1000,2000]<0,1000,0>Yeah";
        String base64Payload = makeMockKrcBase64(krcContent);

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;
                        if (url.contains("/search")) {
                            // Candidate duration is 214000ms while track duration is 200000ms (diff = 14000ms, within +/- 15s)
                            responseJson = "{\"status\":200,\"candidates\":[{"
                                    + "\"id\":\"99999\","
                                    + "\"accesskey\":\"xyz\","
                                    + "\"song\":\"Blinding Lights\","
                                    + "\"singer\":\"The Weeknd\","
                                    + "\"duration\":214000,"
                                    + "\"krctype\":2"
                                    + "}]}";
                        } else if (url.contains("/download")) {
                            responseJson = "{\"status\":200,\"content\":\"" + base64Payload + "\"}";
                        } else {
                            responseJson = "{}";
                        }

                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), responseJson))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Blinding Lights";
        track.artist = "The Weeknd";
        track.duration = 200000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Should accept candidate within +/- 15s duration window", result);
    }

    @Test
    public void testBuildQueryPermutationsStandard() {
        List<String> queries = KugouLyricsProvider.buildQueryPermutations("Blinding Lights", "The Weeknd", Collections.emptyList());
        assertEquals(3, queries.size());
        assertEquals("The Weeknd - Blinding Lights", queries.get(0));
        assertEquals("Blinding Lights - The Weeknd", queries.get(1));
        assertEquals("Blinding Lights The Weeknd", queries.get(2));
    }

    @Test
    public void testBuildQueryPermutationsWithAliases() {
        List<String> aliases = Arrays.asList("Idol", "アイドル");
        List<String> queries = KugouLyricsProvider.buildQueryPermutations("アイドル", "YOASOBI", aliases);
        // cleanTitle is "アイドル", alias "アイドル" is ignored (equalsIgnoreCase)
        // 1. "YOASOBI - アイドル"
        // 2. "アイドル - YOASOBI"
        // 3. "アイドル YOASOBI"
        // 4. "YOASOBI - Idol"
        // 5. "Idol YOASOBI"
        assertEquals(5, queries.size());
        assertEquals("YOASOBI - アイドル", queries.get(0));
        assertEquals("アイドル - YOASOBI", queries.get(1));
        assertEquals("アイドル YOASOBI", queries.get(2));
        assertEquals("YOASOBI - Idol", queries.get(3));
        assertEquals("Idol YOASOBI", queries.get(4));
    }

    @Test
    public void testBuildQueryPermutationsNoArtist() {
        List<String> queries = KugouLyricsProvider.buildQueryPermutations("Solo Title", "", Collections.emptyList());
        assertEquals(1, queries.size());
        assertEquals("Solo Title", queries.get(0));
    }

    @Test
    public void testScoreCandidateExactAndRelaxedDuration() {
        KugouLyricsProvider provider = new KugouLyricsProvider();

        // Exact match
        double scoreExact = provider.scoreCandidate("Blinding Lights", "The Weeknd", 200000,
                "Blinding Lights", "The Weeknd", 200000);
        // diff = 0 -> ds = 1.0; ts = 1.0, as = 1.0 -> 0.50 + 0.35 + 0.15 = 1.00
        assertEquals(1.00, scoreExact, 0.01);

        // 14 seconds difference (within relaxed 15s window, but outside old 12s window)
        double score14s = provider.scoreCandidate("Blinding Lights", "The Weeknd", 200000,
                "Blinding Lights", "The Weeknd", 214000);
        // diff = 14000 -> ds = 1 - (14000/15000) = 1/15 ≈ 0.0667
        // score = 0.50 + 0.35 + 0.0667 * 0.15 = 0.85 + 0.01 = 0.86
        assertTrue("Score with 14s diff should include positive duration credit in 15s window", score14s > 0.85);

        // 18 seconds difference (outside 15s window, ds clamped to 0)
        double score18s = provider.scoreCandidate("Blinding Lights", "The Weeknd", 200000,
                "Blinding Lights", "The Weeknd", 218000);
        // diff = 18000 -> ds = 0.0
        // score = 0.50 + 0.35 + 0 = 0.85
        assertEquals(0.85, score18s, 0.01);
        assertTrue("Candidate with >15s diff should still not be dropped if title and artist match", score18s >= 0.30);
    }

    @Test
    public void testScoreCandidateWithAliases() {
        KugouLyricsProvider provider = new KugouLyricsProvider();
        List<String> aliases = Collections.singletonList("Idol");

        // candSong matches alias "Idol", while inTitle is Japanese "アイドル"
        double score = provider.scoreCandidate("アイドル", aliases, "YOASOBI", 214000,
                "Idol", "YOASOBI", 214000);
        // ts should match "Idol" via aliases (1.0), as = 1.0, ds = 1.0 -> 1.00
        assertEquals(1.00, score, 0.01);
    }

    @Test
    public void testFetchLyricsPrefersKrcOverNonKrcCandidate() throws Exception {
        String krcContent = "[ar:The Weeknd]\n[ti:Blinding Lights]\n[1000,2000]<0,1000,0>Yeah";
        String base64Payload = makeMockKrcBase64(krcContent);

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;
                        if (url.contains("/search")) {
                            String keyword = req.url().queryParameter("keyword");
                            if ("The Weeknd - Blinding Lights".equals(keyword)) {
                                // 1st query only returns LRC (krctype = 1)
                                responseJson = "{\"status\":200,\"candidates\":[{"
                                        + "\"id\":\"11111\","
                                        + "\"accesskey\":\"lrc_key\","
                                        + "\"song\":\"Blinding Lights\","
                                        + "\"singer\":\"The Weeknd\","
                                        + "\"duration\":200000,"
                                        + "\"krctype\":1"
                                        + "}]}";
                            } else if ("Blinding Lights - The Weeknd".equals(keyword)) {
                                // 2nd query returns KRC (krctype = 2)
                                responseJson = "{\"status\":200,\"candidates\":[{"
                                        + "\"id\":\"22222\","
                                        + "\"accesskey\":\"krc_key\","
                                        + "\"song\":\"Blinding Lights\","
                                        + "\"singer\":\"The Weeknd\","
                                        + "\"duration\":200000,"
                                        + "\"krctype\":2"
                                        + "}]}";
                            } else {
                                responseJson = "{\"status\":200,\"candidates\":[]}";
                            }
                        } else if (url.contains("/download")) {
                            // Verify that candidate 22222 (krctype = 2) was the one downloaded
                            assertTrue("Should download the KRC syllable candidate (id=22222)", url.contains("id=22222"));
                            responseJson = "{\"status\":200,\"content\":\"" + base64Payload + "\"}";
                        } else {
                            responseJson = "{}";
                        }

                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), responseJson))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Blinding Lights";
        track.artist = "The Weeknd";
        track.duration = 200000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Should fetch lyrics preferring KRC candidate", result);
        assertEquals(LyricsData.Type.SYLLABLE, result.type);
    }

    @Test
    public void testSearchCandidatePrefersKrcOverExactMatchingNonKrcInSameResponse() throws Exception {
        String krcContent = "[ar:The Weeknd]\n[ti:Blinding Lights]\n[1000,2000]<0,1000,0>Yeah";
        String base64Payload = makeMockKrcBase64(krcContent);

        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;
                        if (url.contains("/search")) {
                            // Same response contains:
                            // 1. Exact match non-KRC candidate (krctype = 1, perfect title/artist/duration match -> score = 1.0)
                            // 2. Valid KRC candidate (krctype = 2, lower base score e.g. duration diff of 14s -> score ~ 0.86)
                            responseJson = "{\"status\":200,\"candidates\":["
                                    + "{"
                                    + "\"id\":\"lrc_perfect\","
                                    + "\"accesskey\":\"key1\","
                                    + "\"song\":\"Blinding Lights\","
                                    + "\"singer\":\"The Weeknd\","
                                    + "\"duration\":200000,"
                                    + "\"krctype\":1"
                                    + "},"
                                    + "{"
                                    + "\"id\":\"krc_syllable\","
                                    + "\"accesskey\":\"key2\","
                                    + "\"song\":\"Blinding Lights\","
                                    + "\"singer\":\"The Weeknd\","
                                    + "\"duration\":214000,"
                                    + "\"krctype\":2"
                                    + "}"
                                    + "]}";
                        } else if (url.contains("/download")) {
                            // Verify that candidate krc_syllable (id=krc_syllable) was chosen over lrc_perfect
                            assertTrue("Must download KRC syllable candidate (id=krc_syllable) despite lower base score",
                                    url.contains("id=krc_syllable"));
                            responseJson = "{\"status\":200,\"content\":\"" + base64Payload + "\"}";
                        } else {
                            responseJson = "{}";
                        }

                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), responseJson))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Blinding Lights";
        track.artist = "The Weeknd";
        track.duration = 200000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Should fetch lyrics preferring KRC syllable candidate in same response", result);
        assertEquals(LyricsData.Type.SYLLABLE, result.type);
    }

    @Test
    public void testCaseInsensitiveQueryDeduplication() throws Exception {
        List<String> queriesExecuted = new ArrayList<>();
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        if (url.contains("/search")) {
                            queriesExecuted.add(req.url().queryParameter("keyword"));
                        }
                        return new Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(MediaType.parse("application/json"), "{\"status\":200,\"candidates\":[]}"))
                                .build();
                    }
                })
                .build();

        KugouLyricsProvider provider = new KugouLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        // Title and artist with case differences that might generate duplicate queries if case-insensitive deduplication is absent
        track.title = "hello";
        track.artist = "HELLO";

        provider.fetchLyrics(track);
        // "HELLO - hello" vs "hello - HELLO" should be deduplicated case-insensitively
        long distinctCaseInsensitive = queriesExecuted.stream()
                .map(String::toLowerCase)
                .distinct()
                .count();
        assertEquals("Executed queries should be case-insensitively unique", distinctCaseInsensitive, queriesExecuted.size());
    }

    @Test
    public void testScoreCandidateRejectsTitleMismatchEvenIfSameArtist() {
        KugouLyricsProvider provider = new KugouLyricsProvider();
        // User searched "liMOusIne" by "Bring Me The Horizon"
        // Candidate is "Kingslayer" by "Bring Me The Horizon" with matching duration
        double score = provider.scoreCandidate("liMOusIne", Collections.emptyList(), "Bring Me The Horizon", 220000,
                "Kingslayer", "Bring Me The Horizon", 220000);
        // Due to title gatekeeper, score must be 0.0
        assertEquals(0.0, score, 0.001);
    }
}
