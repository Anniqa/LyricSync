package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
}
