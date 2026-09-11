package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import org.junit.Test;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

public class NeteaseLyricsProviderTest {

    @Test
    public void testSyllablePrioritizedOverLineCandidate() throws Exception {
        // Song 101 has only line lyrics (lrc)
        // Song 102 has syllable lyrics (yrc)
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;

                        if (url.contains("/api/search/get")) {
                            responseJson = "{\"code\":200,\"result\":{\"songs\":["
                                    + "{\"id\":101,\"name\":\"Special Song\",\"artists\":[{\"name\":\"Famous Artist\"}],\"duration\":200000},"
                                    + "{\"id\":102,\"name\":\"Special Song\",\"artists\":[{\"name\":\"Famous Artist\"}],\"duration\":200000}"
                                    + "]}}";
                        } else if (url.contains("id=101")) {
                            // Only line synced (lrc)
                            responseJson = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]Line one\\n[00:03.00]Line two\"}}";
                        } else if (url.contains("id=102")) {
                            // Syllable synced (yrc)
                            responseJson = "{\"code\":200,\"yrc\":{\"lyric\":\"[1000,2000](1000,1000,0)Line (2000,1000,0)one\\n[3000,2000](3000,1000,0)Line (4000,1000,0)two\"},"
                                    + "\"lrc\":{\"lyric\":\"[00:01.00]Line one\\n[00:03.00]Line two\"}}";
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

        NeteaseLyricsProvider provider = new NeteaseLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Special Song";
        track.artist = "Famous Artist";
        track.duration = 200000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Lyrics should be found", result);
        assertEquals("Should prioritize syllable (YRC) lyrics even if candidate 0 had line lyrics",
                LyricsData.Type.SYLLABLE, result.type);
        assertEquals("Netease", result.provider);
    }

    @Test
    public void testFallbackToLineLyricsWhenNoSyllableAvailable() throws Exception {
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;

                        if (url.contains("/api/search/get")) {
                            responseJson = "{\"code\":200,\"result\":{\"songs\":["
                                    + "{\"id\":201,\"name\":\"Old Track\",\"artists\":[{\"name\":\"Classic Artist\"}],\"duration\":180000}"
                                    + "]}}";
                        } else if (url.contains("id=201")) {
                            responseJson = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]Old line\\n[00:04.00]Second line\"}}";
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

        NeteaseLyricsProvider provider = new NeteaseLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "Old Track";
        track.artist = "Classic Artist";
        track.duration = 180000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Lyrics should be found", result);
        assertEquals(LyricsData.Type.LINE, result.type);
    }

    @Test
    public void testFalseMatchRejectedWhenTitleDoesNotMatchEvenIfSameArtist() throws Exception {
        // Simulates searching for an album like "POST HUMAN: NeX GEn Album" where NetEase returns
        // popular songs by the artist like "Kingslayer" or "LosT", but title does not match.
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;

                        if (url.contains("/api/search/get")) {
                            responseJson = "{\"code\":200,\"result\":{\"songs\":["
                                    + "{\"id\":301,\"name\":\"Kingslayer\",\"artists\":[{\"name\":\"Bring Me The Horizon\"}],\"duration\":220000},"
                                    + "{\"id\":302,\"name\":\"Can You Feel My Heart\",\"artists\":[{\"name\":\"Bring Me The Horizon\"}],\"duration\":228000}"
                                    + "]}}";
                        } else {
                            responseJson = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]Sample lyric\"}}";
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

        NeteaseLyricsProvider provider = new NeteaseLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "POST HUMAN: NeX GEn Album";
        track.artist = "Bring Me The Horizon";
        track.duration = 2400000;

        LyricsData result = provider.fetchLyrics(track);
        assertNull("Should reject candidates whose title has no match with the query, returning null instead of wrong song lyrics", result);
    }

    @Test
    public void testCandidateWithMatchingTitleSelectedOverFirstCandidateWithMismatchedTitle() throws Exception {
        // First candidate is "Kingslayer" (wrong song), second is "liMOusIne" (correct song)
        OkHttpClient mockClient = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request req = chain.request();
                        String url = req.url().toString();
                        String responseJson;

                        if (url.contains("/api/search/get")) {
                            responseJson = "{\"code\":200,\"result\":{\"songs\":["
                                    + "{\"id\":401,\"name\":\"Kingslayer\",\"artists\":[{\"name\":\"Bring Me The Horizon\"}],\"duration\":220000},"
                                    + "{\"id\":402,\"name\":\"liMOusIne\",\"artists\":[{\"name\":\"Bring Me The Horizon\"}],\"duration\":195000}"
                                    + "]}}";
                        } else if (url.contains("id=402")) {
                            responseJson = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]limousine lyric\"}}";
                        } else {
                            responseJson = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]kingslayer lyric\"}}";
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

        NeteaseLyricsProvider provider = new NeteaseLyricsProvider(mockClient);
        TrackInfo track = new TrackInfo();
        track.title = "liMOusIne";
        track.artist = "Bring Me The Horizon";
        track.duration = 195000;

        LyricsData result = provider.fetchLyrics(track);
        assertNotNull("Should find matching lyrics for liMOusIne", result);
        assertEquals("limousine lyric", result.lines.get(0).text);
    }
}
