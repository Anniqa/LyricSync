package com.lyricsync.app.ai.auth;

import org.junit.After;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class OAuthLoopbackServerTest {

    private OAuthLoopbackServer server;

    @After
    public void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    public void testLoopbackReceivesAuthCode() throws Exception {
        server = new OAuthLoopbackServer();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedCode = new AtomicReference<>();
        AtomicReference<String> receivedState = new AtomicReference<>();

        server.start(new OAuthLoopbackServer.Callback() {
            @Override
            public void onCodeReceived(String authCode, String state) {
                receivedCode.set(authCode);
                receivedState.set(state);
                latch.countDown();
            }
            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });

        assertTrue(server.isRunning());

        URL url = new URL("http://127.0.0.1:51121/oauth-callback?code=test_code_xyz&state=test_state");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        assertEquals(200, responseCode);

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) response.append(line);
        in.close();

        assertTrue(response.toString().contains("Login Berhasil"));
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("test_code_xyz", receivedCode.get());
        assertEquals("test_state", receivedState.get());

        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    public void testLoopbackHandlesUrlDecodingAndParamOrder() throws Exception {
        server = new OAuthLoopbackServer();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedCode = new AtomicReference<>();
        AtomicReference<String> receivedState = new AtomicReference<>();

        server.start(new OAuthLoopbackServer.Callback() {
            @Override
            public void onCodeReceived(String authCode, String state) {
                receivedCode.set(authCode);
                receivedState.set(state);
                latch.countDown();
            }
            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });

        URL url = new URL("http://127.0.0.1:51121/oauth-callback?state=my%20secret%20state&code=4%2F0AeaYSH_xyz-123");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        assertEquals(200, responseCode);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("4/0AeaYSH_xyz-123", receivedCode.get());
        assertEquals("my secret state", receivedState.get());
    }

    @Test
    public void testLoopbackStop() {
        server = new OAuthLoopbackServer();
        server.start(new OAuthLoopbackServer.Callback() {
            @Override
            public void onCodeReceived(String authCode, String state) {}
            @Override
            public void onError(Exception e) {}
        });

        assertTrue(server.isRunning());
        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    public void testLoopbackHandlesOAuthErrorCallback() throws Exception {
        server = new OAuthLoopbackServer();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> receivedError = new AtomicReference<>();

        server.start(new OAuthLoopbackServer.Callback() {
            @Override
            public void onCodeReceived(String authCode, String state) {
                latch.countDown();
            }
            @Override
            public void onError(Exception e) {
                receivedError.set(e);
                latch.countDown();
            }
        });

        URL url = new URL("http://127.0.0.1:51121/oauth-callback?error=access_denied");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        assertEquals(200, responseCode);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(receivedError.get());
        assertTrue(receivedError.get().getMessage().contains("access_denied"));
    }

    @Test
    public void testLoopbackNonCallbackPathReturns404() throws Exception {
        server = new OAuthLoopbackServer();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedCode = new AtomicReference<>();

        server.start(new OAuthLoopbackServer.Callback() {
            @Override
            public void onCodeReceived(String authCode, String state) {
                receivedCode.set(authCode);
                latch.countDown();
            }
            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });

        // Hit 404 path first
        URL notFoundUrl = new URL("http://127.0.0.1:51121/favicon.ico");
        HttpURLConnection notFoundConn = (HttpURLConnection) notFoundUrl.openConnection();
        notFoundConn.setRequestMethod("GET");
        assertEquals(404, notFoundConn.getResponseCode());

        // Server should still be running and accept the valid callback
        assertTrue(server.isRunning());

        URL callbackUrl = new URL("http://127.0.0.1:51121/oauth-callback?code=after_404_code&state=st");
        HttpURLConnection callbackConn = (HttpURLConnection) callbackUrl.openConnection();
        callbackConn.setRequestMethod("GET");
        assertEquals(200, callbackConn.getResponseCode());

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("after_404_code", receivedCode.get());
    }
}
