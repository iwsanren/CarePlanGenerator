package com.page24.backend.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for an LLM provider's HTTP API, built on the JDK's own {@link HttpServer} so the tests need no extra
 * library and never leave the machine. It listens on a free loopback port, records every request it receives and
 * answers each one with the status and body set by {@link #respondWith(int, String)}.
 */
final class FakeLlmApiServer implements AutoCloseable {

    /** One request as the server saw it; header names are looked up ignoring letter case. */
    record RecordedRequest(String method, String path, Map<String, String> headers, String body) {

        String header(String name) {
            return headers.get(name);
        }
    }

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    /** Released when the server closes, so a request that is being held back does not outlive the test. */
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMillis;

    private FakeLlmApiServer(HttpServer server) {
        this.server = server;
    }

    static FakeLlmApiServer start() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeLlmApiServer fake = new FakeLlmApiServer(httpServer);
            httpServer.createContext("/", fake::handle);
            httpServer.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the fake LLM API server", e);
        }
    }

    /** The full URL of a path on this server, e.g. {@code http://127.0.0.1:50321/v1/messages}. */
    String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Every later request is answered with this status and body (an empty body is sent with no content). */
    void respondWith(int responseStatus, String responseBody) {
        respondAfter(0, responseStatus, responseBody);
    }

    /** Like {@link #respondWith}, but each answer is held back for the given time, as a slow provider would. */
    void respondAfter(long delayInMillis, int responseStatus, String responseBody) {
        this.delayMillis = delayInMillis;
        this.status = responseStatus;
        this.body = responseBody;
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    RecordedRequest onlyRequest() {
        List<RecordedRequest> seen = requests();
        if (seen.size() != 1) {
            throw new AssertionError("Expected exactly one request but the server saw " + seen.size());
        }
        return seen.get(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, requestBody));

            holdBack();

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (payload.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, payload.length);
                exchange.getResponseBody().write(payload);
            }
        }
    }

    /** Waits for the configured delay, or until the server is closed, whichever comes first. */
    private void holdBack() {
        long delay = delayMillis;
        if (delay <= 0) {
            return;
        }
        try {
            closed.await(delay, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
    }
}
