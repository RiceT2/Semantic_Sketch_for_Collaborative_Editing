package com.semantic.sketch.web;

import com.semantic.sketch.IntentResidueCalculator;
import com.semantic.sketch.ablation.ConflictManager;
import com.semantic.sketch.ablation.FactorGraphBuilder;
import com.semantic.sketch.ablation.GreedyInferenceEngine;
import com.semantic.sketch.semantic.LightweightSemanticFingerprintService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP + Server-Sent Events server for validating the collaborative editing flow in a browser.
 */
public class CollaborativeEditingWebServer {
    private final HttpServer server;
    private final CollaborationSessionHub hub;
    private final SseBroadcaster broadcaster;

    public CollaborativeEditingWebServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.hub = new CollaborationSessionHub(
                new LightweightSemanticFingerprintService(),
                new ConflictManager(),
                new FactorGraphBuilder(Map.of("owner", 1.5d, "reviewer", 1.2d)),
                new GreedyInferenceEngine(),
                new IntentResidueCalculator(),
                0.80d
        );
        this.broadcaster = new SseBroadcaster();
        configureRoutes();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        CollaborativeEditingWebServer webServer = new CollaborativeEditingWebServer(port);
        webServer.start();
        System.out.println("Semantic sketch simulator started at http://localhost:" + port);
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    private void configureRoutes() {
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handleStatic);
        server.createContext("/events", this::handleEvents);
        server.createContext("/api/operations", this::handleOperation);
        server.createContext("/api/human-decision", this::handleHumanDecision);
        server.createContext("/api/snapshot", this::handleSnapshot);
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(exchange, 400, "text/plain", "Bad path");
            return;
        }
        String resourcePath = "/static" + path;
        InputStream resource = CollaborativeEditingWebServer.class.getResourceAsStream(resourcePath);
        if (resource == null) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }
        byte[] body = resource.readAllBytes();
        String contentType = path.endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream";
        send(exchange, 200, contentType, body);
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        broadcaster.connect(exchange);
    }

    private void handleOperation(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json", JsonSupport.stringify(Map.of("error", "Method not allowed")));
            return;
        }
        Map<String, String> request = JsonSupport.parseObject(readBody(exchange));
        String branchId = valueOrDefault(request.get("branchId"), "master");
        String actorId = valueOrDefault(request.get("actorId"), "anonymous");
        String payload = valueOrDefault(request.get("payload"), "empty edit");
        CollaborationSessionHub.HubResult result = hub.submit(branchId, actorId, payload);
        Map<String, ?> view = hub.resultView(result);
        broadcaster.broadcast(result.type(), view);
        if ("human_intervention_required".equals(result.type())) {
            broadcaster.broadcast("conflict_detected", view);
        }
        send(exchange, 200, "application/json", JsonSupport.stringify(view));
    }

    private void handleHumanDecision(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json", JsonSupport.stringify(Map.of("error", "Method not allowed")));
            return;
        }
        Map<String, String> request = JsonSupport.parseObject(readBody(exchange));
        String branchId = valueOrDefault(request.get("branchId"), "master");
        String requestId = valueOrDefault(request.get("requestId"), "");
        String decision = valueOrDefault(request.get("decision"), "accept");
        CollaborationSessionHub.HubResult result = hub.decide(branchId, requestId, decision);
        Map<String, ?> view = hub.resultView(result);
        broadcaster.broadcast("human_decision", view);
        send(exchange, 200, "application/json", JsonSupport.stringify(view));
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException {
        String branchId = valueOrDefault(queryParams(exchange.getRequestURI().getQuery()).get("branchId"), "master");
        send(exchange, 200, "application/json", JsonSupport.stringify(hub.snapshot(branchId)));
    }

    private Map<String, String> queryParams(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                values.put(part.substring(0, separator), part.substring(separator + 1));
            }
        }
        return values;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
