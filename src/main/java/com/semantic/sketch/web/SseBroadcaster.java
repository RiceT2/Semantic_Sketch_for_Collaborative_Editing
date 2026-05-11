package com.semantic.sketch.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class SseBroadcaster {
    private final Set<OutputStream> clients = ConcurrentHashMap.newKeySet();

    void connect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        clients.add(output);
        send(output, "connected", Map.of("message", "SSE connected"));
    }

    void broadcast(String event, Map<String, ?> payload) {
        for (OutputStream client : clients) {
            try {
                send(client, event, payload);
            } catch (IOException e) {
                clients.remove(client);
            }
        }
    }

    private void send(OutputStream output, String event, Map<String, ?> payload) throws IOException {
        String frame = "event: " + event + "\n"
                + "data: " + JsonSupport.stringify(payload) + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
