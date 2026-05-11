package com.semantic.sketch.web;

import com.semantic.sketch.semantic.IntentResidualCalculator;
import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.model.SemanticTriple;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
                new IntentResidualCalculator(),
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
        Map<String, Object> request = JsonSupport.parseObjectValues(readBody(exchange));
        CrdtOperationEnvelope envelope = operationEnvelope(request);
        CollaborationSessionHub.HubResult result = hub.submit(envelope);
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
        Map<String, ?> view = hub.snapshot(branchId);
        broadcaster.broadcast("snapshot", view);
        send(exchange, 200, "application/json", JsonSupport.stringify(view));
    }

    private CrdtOperationEnvelope operationEnvelope(Map<String, Object> request) {
        String branchId = valueOrDefault(stringValue(request.get("branchId")), "master");
        String actorId = valueOrDefault(stringValue(request.get("actorId")), "anonymous");
        String payload = valueOrDefault(stringValue(request.get("payload")), "empty edit");
        CrdtOperationType operationType = request.get("operationType") == null
                ? inferCompatibilityOperationType(payload)
                : CrdtOperationType.fromJsonValue(stringValue(request.get("operationType")));
        String intentText = valueOrDefault(stringValue(request.get("intentText")), payload);
        String insertedText = stringValue(request.get("insertedText"));
        String deletedTextPreview = stringValue(request.get("deletedTextPreview"));
        if (request.get("operationType") == null) {
            insertedText = operationType == CrdtOperationType.INSERT ? payload : insertedText;
            deletedTextPreview = operationType == CrdtOperationType.DELETE ? payload : deletedTextPreview;
        }
        return new CrdtOperationEnvelope(
                valueOrDefault(stringValue(request.get("opId")), "op-" + UUID.randomUUID()),
                actorId,
                branchId,
                operationType,
                Map.of(),
                stringValue(request.get("targetPath")),
                integerValue(request.get("fromIndex")),
                integerValue(request.get("toIndex")),
                insertedText,
                deletedTextPreview,
                intentText,
                stringValue(request.get("yjsUpdateBase64")),
                0L,
                semanticTriples(request.get("semanticTriples")),
                Instant.now()
        );
    }

    private CrdtOperationType inferCompatibilityOperationType(String payload) {
        if (payload != null) {
            String firstToken = payload.strip().split("\\s+", 2)[0];
            try {
                return CrdtOperationType.fromString(firstToken);
            } catch (IllegalArgumentException ignored) {
                // Preserve legacy free-text payload behavior by emitting a REPLACE envelope.
            }
        }
        return CrdtOperationType.REPLACE;
    }

    private List<SemanticTriple> semanticTriples(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<SemanticTriple> triples = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> triple) {
                triples.add(new SemanticTriple(
                        valueOrDefault(stringValue(triple.get("intent")), "unspecified intent"),
                        valueOrDefault(stringValue(triple.get("precondition")), "unspecified precondition"),
                        valueOrDefault(stringValue(triple.get("impactScope")), "unspecified impact")
                ));
            }
        }
        return triples;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        return text == null || text.isBlank() ? null : Integer.parseInt(text);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
