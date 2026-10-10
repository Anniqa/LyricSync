package com.lyricsync.app.ai.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight local HTTP loopback server listening on 127.0.0.1:51121 for OAuth redirect callbacks.
 */
public class OAuthLoopbackServer {

    public interface Callback {
        void onCodeReceived(String authCode, String state);
        void onError(Exception e);
    }

    public static final int PORT = 51121;
    public static final String PATH = "/oauth-callback";
    private static final int CLIENT_SOCKET_TIMEOUT_MS = 5000;

    private static final String SUCCESS_HTML = "<!DOCTYPE html>\n" +
            "<html lang=\"id\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "  <title>Login Berhasil - LyricSync</title>\n" +
            "  <style>\n" +
            "    * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "    body {\n" +
            "      background: linear-gradient(135deg, #0d1117 0%, #161b22 100%);\n" +
            "      color: #e6edf3;\n" +
            "      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\n" +
            "      display: flex;\n" +
            "      align-items: center;\n" +
            "      justify-content: center;\n" +
            "      min-height: 100vh;\n" +
            "      padding: 24px;\n" +
            "    }\n" +
            "    .card {\n" +
            "      background: rgba(22, 27, 34, 0.85);\n" +
            "      border: 1px solid #30363d;\n" +
            "      border-radius: 20px;\n" +
            "      padding: 40px 32px;\n" +
            "      max-width: 440px;\n" +
            "      width: 100%;\n" +
            "      text-align: center;\n" +
            "      box-shadow: 0 16px 32px rgba(0, 0, 0, 0.4);\n" +
            "      backdrop-filter: blur(10px);\n" +
            "    }\n" +
            "    .icon-wrapper {\n" +
            "      width: 64px;\n" +
            "      height: 64px;\n" +
            "      margin: 0 auto 20px;\n" +
            "      background: rgba(46, 160, 67, 0.15);\n" +
            "      border-radius: 50%;\n" +
            "      display: flex;\n" +
            "      align-items: center;\n" +
            "      justify-content: center;\n" +
            "      border: 2px solid #2ea043;\n" +
            "    }\n" +
            "    .icon-wrapper svg {\n" +
            "      width: 32px;\n" +
            "      height: 32px;\n" +
            "      fill: #3fb950;\n" +
            "    }\n" +
            "    h1 {\n" +
            "      font-size: 24px;\n" +
            "      font-weight: 700;\n" +
            "      color: #ffffff;\n" +
            "      margin-bottom: 8px;\n" +
            "    }\n" +
            "    .subtitle {\n" +
            "      font-size: 14px;\n" +
            "      color: #8b949e;\n" +
            "      margin-bottom: 24px;\n" +
            "    }\n" +
            "    .message {\n" +
            "      font-size: 15px;\n" +
            "      line-height: 1.6;\n" +
            "      color: #c9d1d9;\n" +
            "      margin-bottom: 28px;\n" +
            "    }\n" +
            "    .app-name {\n" +
            "      color: #58a6ff;\n" +
            "      font-weight: 600;\n" +
            "    }\n" +
            "    .badge {\n" +
            "      display: inline-block;\n" +
            "      padding: 8px 18px;\n" +
            "      background: #21262d;\n" +
            "      color: #8b949e;\n" +
            "      border: 1px solid #30363d;\n" +
            "      border-radius: 24px;\n" +
            "      font-size: 13px;\n" +
            "    }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <div class=\"card\">\n" +
            "    <div class=\"icon-wrapper\">\n" +
            "      <svg viewBox=\"0 0 24 24\"><path d=\"M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z\"/></svg>\n" +
            "    </div>\n" +
            "    <h1>Login Berhasil</h1>\n" +
            "    <div class=\"subtitle\">Antigravity AI Authorization</div>\n" +
            "    <p class=\"message\">Autentikasi akun Google berhasil.<br>Silakan kembali ke aplikasi <span class=\"app-name\">LyricSync</span> untuk melanjutkan sinkronisasi lirik.<br><br><em>You can now return to LyricSync.</em></p>\n" +
            "    <div class=\"badge\">Anda dapat menutup tab ini</div>\n" +
            "  </div>\n" +
            "</body>\n" +
            "</html>\n";

    private static final String ERROR_HTML = "<!DOCTYPE html>\n" +
            "<html lang=\"id\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "  <title>Login Gagal - LyricSync</title>\n" +
            "  <style>\n" +
            "    * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "    body { background: #0d1117; color: #e6edf3; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 24px; }\n" +
            "    .card { background: #161b22; border: 1px solid #30363d; border-radius: 20px; padding: 40px 32px; max-width: 440px; width: 100%; text-align: center; }\n" +
            "    h1 { font-size: 24px; font-weight: 700; color: #ffffff; margin-bottom: 8px; }\n" +
            "    p { font-size: 15px; line-height: 1.6; color: #c9d1d9; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <div class=\"card\">\n" +
            "    <h1>Login Gagal</h1>\n" +
            "    <p>Otorisasi Antigravity AI tidak selesai. Silakan kembali ke aplikasi <strong>LyricSync</strong> dan coba lagi.<br><br><em>You can return to LyricSync.</em></p>\n" +
            "  </div>\n" +
            "</body>\n" +
            "</html>\n";

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * Starts the loopback server asynchronously and listens for callback requests.
     */
    public synchronized void start(final Callback callback) {
        if (running) {
            return;
        }

        ServerSocket newSocket = null;
        try {
            newSocket = new ServerSocket();
            newSocket.setReuseAddress(true);
            newSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1);
        } catch (IOException e) {
            closeQuietly(newSocket);
            if (callback != null) {
                callback.onError(e);
            }
            return;
        }

        final ServerSocket socket = newSocket;
        serverSocket = socket;
        running = true;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running && !socket.isClosed()) {
                        Socket clientSocket;
                        try {
                            clientSocket = socket.accept();
                            clientSocket.setSoTimeout(CLIENT_SOCKET_TIMEOUT_MS);
                        } catch (SocketException e) {
                            // Server socket closed intentionally
                            break;
                        } catch (IOException e) {
                            if (running && callback != null) {
                                callback.onError(e);
                            }
                            break;
                        }

                        handleClient(clientSocket, socket, callback);
                    }
                } finally {
                    closeServerSocket(socket);
                }
            }
        }, "OAuthLoopbackServer-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Handles an incoming HTTP client request, draining headers to prevent TCP RST on mobile browsers
     * and ensuring proper socket cleanup.
     */
    private void handleClient(Socket clientSocket, final ServerSocket boundSocket, Callback callback) {
        boolean shouldStop = false;
        try (Socket socket = clientSocket) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }

            // Drain remaining HTTP headers to avoid unread bytes causing TCP RST on mobile browsers
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.trim().isEmpty()) {
                // Discard header lines
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }

            String rawUri = parts[1];
            String path = rawUri;
            String rawQuery = null;
            int questionMarkIdx = rawUri.indexOf('?');
            if (questionMarkIdx != -1) {
                path = rawUri.substring(0, questionMarkIdx);
                rawQuery = rawUri.substring(questionMarkIdx + 1);
            }

            if (PATH.equals(path)) {
                Map<String, String> queryParams = parseQueryParams(rawQuery);
                String authCode = queryParams.get("code");
                String state = queryParams.get("state");
                String error = queryParams.get("error");

                sendHtmlResponse(socket, 200, authCode != null ? SUCCESS_HTML : ERROR_HTML);
                shouldStop = true;

                if (callback != null) {
                    if (authCode != null) {
                        callback.onCodeReceived(authCode, state);
                    } else if (error != null) {
                        callback.onError(new Exception("OAuth callback error: " + error));
                    } else {
                        callback.onError(new Exception("Missing authorization code in callback request"));
                    }
                }
            } else {
                sendNotFoundResponse(socket);
            }
        } catch (SocketTimeoutException ignored) {
            // Timed out client connection ignored to prevent hanging
        } catch (Exception e) {
            if (running && callback != null) {
                callback.onError(e);
            }
        } finally {
            if (shouldStop) {
                closeServerSocket(boundSocket);
            }
        }
    }

    /**
     * Parses a raw URL query string into key-value pairs safely handling URL encoding.
     */
    private Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            try {
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    params.put(key, value);
                } else if (idx == -1 && !pair.isEmpty()) {
                    params.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // Ignore malformed percent encoding gracefully
            }
        }
        return params;
    }

    /**
     * Sends an HTTP HTML response.
     */
    private void sendHtmlResponse(Socket socket, int statusCode, String html) throws IOException {
        byte[] bodyBytes = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.print("HTTP/1.1 " + statusCode + " OK\r\n");
        writer.print("Content-Type: text/html; charset=utf-8\r\n");
        writer.print("Content-Length: " + bodyBytes.length + "\r\n");
        writer.print("Connection: close\r\n");
        writer.print("\r\n");
        writer.flush();
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * Sends an HTTP 404 Not Found response.
     */
    private void sendNotFoundResponse(Socket socket) throws IOException {
        byte[] bodyBytes = "Not Found".getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.print("HTTP/1.1 404 Not Found\r\n");
        writer.print("Content-Type: text/plain; charset=utf-8\r\n");
        writer.print("Content-Length: " + bodyBytes.length + "\r\n");
        writer.print("Connection: close\r\n");
        writer.print("\r\n");
        writer.flush();
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * Stops the loopback server and releases the socket.
     */
    public synchronized void stop() {
        closeServerSocket(serverSocket);
    }

    /**
     * Closes the given listener and, when it is still this instance's active one,
     * marks the server stopped. Using the socket the caller owns (instead of the
     * {@code serverSocket} field) keeps a finishing accept-thread from closing a
     * socket that a later restart has already replaced.
     */
    private synchronized void closeServerSocket(ServerSocket socket) {
        closeQuietly(socket);
        if (socket != null && socket == serverSocket) {
            serverSocket = null;
            running = false;
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Returns whether the loopback server is currently active and listening.
     */
    public synchronized boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }
}
