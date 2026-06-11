package com.youtubeauto.upload.youtube;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * OAuth callback receiver that binds Java's built-in HttpServer to
 * 0.0.0.0:8089 explicitly. Replaces google-oauth-client-jetty's
 * LocalServerReceiver which silently ignores setHost("0.0.0.0") and
 * binds Jetty to localhost only — fine on a developer laptop, useless
 * inside a Docker container.
 *
 * Reports the redirect URI as http://127.0.0.1:8089/Callback because
 * Google's OAuth 2.0 policy (2024+) rejects both 0.0.0.0 and (sometimes)
 * "localhost" for new Desktop clients. The numeric loopback is always
 * accepted.
 */
@Slf4j
public class Bind0000Receiver implements VerificationCodeReceiver {

    private final int port;
    private final String callbackPath;
    private HttpServer server;
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile String code;
    private volatile String error;

    public Bind0000Receiver(int port, String callbackPath) {
        this.port = port;
        this.callbackPath = callbackPath;
    }

    @Override
    public String getRedirectUri() throws IOException {
        if (server == null) start();
        // Google requires loopback IP, not 0.0.0.0 or hostname.
        return "http://127.0.0.1:" + port + callbackPath;
    }

    private synchronized void start() throws IOException {
        if (server != null) return;
        // Bind 0.0.0.0 — Docker port-forward sends host:8089 traffic here.
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", port);
        server = HttpServer.create(addr, 0);
        server.createContext(callbackPath, exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> params = parseQuery(query);
            String body;
            int status;
            if (params.containsKey("code")) {
                code = params.get("code");
                status = 200;
                body = "<!doctype html><html><body style='font-family:sans-serif;padding:40px;text-align:center'>"
                     + "<h1>✅ Authorization received</h1>"
                     + "<p>You can close this window. The upload service is now finishing up.</p>"
                     + "</body></html>";
            } else if (params.containsKey("error")) {
                error = params.get("error");
                status = 400;
                body = "<!doctype html><html><body style='font-family:sans-serif;padding:40px;text-align:center'>"
                     + "<h1>❌ Authorization error</h1>"
                     + "<p>Google returned: " + error + "</p>"
                     + "<p>Check the upload-service logs for details.</p>"
                     + "</body></html>";
            } else {
                status = 400;
                body = "<!doctype html><html><body><h1>No code in callback</h1></body></html>";
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            latch.countDown();
        });
        server.setExecutor(null);
        server.start();
        log.info("OAuth callback receiver listening on 0.0.0.0:{}{}  (Google redirect URI: http://127.0.0.1:{}{})",
                port, callbackPath, port, callbackPath);
    }

    @Override
    public String waitForCode() throws IOException {
        try {
            if (!latch.await(5, TimeUnit.MINUTES)) {
                throw new IOException("Timed out waiting for OAuth code (5 min)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for OAuth code", e);
        }
        if (code != null) return code;
        throw new IOException("OAuth callback returned error: " + error);
    }

    @Override
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            log.info("OAuth callback receiver stopped");
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null) return out;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = java.net.URLDecoder.decode(kv.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            String v = java.net.URLDecoder.decode(kv.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
