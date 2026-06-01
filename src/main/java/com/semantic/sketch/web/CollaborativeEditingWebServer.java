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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 优化重构后的 HTTP + Server-Sent Events 本地协同流验证服务器。
 * 彻底消除了资源泄露风险、冗余表达式及非标准转义警告。
 */
public class CollaborativeEditingWebServer {
    private static final Logger LOGGER = Logger.getLogger(CollaborativeEditingWebServer.class.getName());

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
        LOGGER.info(() -> "Semantic sketch simulator started at http://localhost:" + port);
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
        byte[] body;

        // 【修复警告 1】利用 try-with-resources 自动安全释放前端静态文件的文件指针
        try (InputStream is = CollaborativeEditingWebServer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                send(exchange, 404, "text/plain", "Not found");
                return;
            }
            body = is.readAllBytes();
        }
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            LOGGER.info(() -> "[handleOperation] incoming " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 【修复警告 2 & 3】移除了恒等判定并修正了非文本块外冗余换行转义
            String bodyPrefix = body.isEmpty() ? "<empty>" : escapeNewlines(body.substring(0, Math.min(1024, body.length())));
            LOGGER.fine(() -> "[handleOperation] body (prefix): " + bodyPrefix);
            Map<String, Object> payload = JsonSupport.parseObjectValues(body);

            CrdtOperationEnvelope envelope = operationEnvelope(payload);
            LOGGER.fine(() -> "[handleOperation] envelope constructed: opId=" + envelope.getOpId() + " actor=" + envelope.getActorId());
            CollaborationSessionHub.HubResult result = hub.submit(envelope);
            LOGGER.fine(() -> "[handleOperation] hub.submit returned type=" + result.type());
            Map<String, ?> view = hub.resultView(result);
            broadcaster.broadcast(result.type(), view);
            if ("human_intervention_required".equals(result.type())) {
                broadcaster.broadcast("conflict_detected", view);
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "accepted");
            Object receivedBranch = payload.get("branchId");
            Object receivedActor = payload.get("actorId");
            resp.put("receivedBranchId", receivedBranch);
            resp.put("receivedActorId", receivedActor);
            Object payloadObj = payload.get("payload");
            String payloadSummary = null;
            if (payloadObj != null) {
                String s = String.valueOf(payloadObj);
                payloadSummary = s.substring(0, Math.min(256, s.length()));
            }
            resp.put("receivedPayloadSummary", payloadSummary);

            byte[] respBytes = JsonSupport.stringify(resp).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        } catch (Exception e) {
            // 【修复警告 7】采用高可靠的 Logger 替代高风险、不可溯源的 printStackTrace()
            LOGGER.log(Level.SEVERE, "Error handling operation inside collaboration gateway", e);
            try {
                Map<String, Object> err = new HashMap<>();
                err.put("error", String.valueOf(e));
                err.put("message", e.getMessage());
                byte[] errBytes = JsonSupport.stringify(err).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(500, errBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errBytes);
                }
            } catch (Exception inner) {
                try { exchange.close(); } catch (Exception ignore) {}
            }
        }
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
        // 【修复警告 4, 5, 6】删除了始终为 false 的空映射检查，改为多层健壮防御
        if (request == null) {
            request = Map.of();
        }
        String branchId = valueOrDefault(stringValue(request.get("branchId")), "master");
        String actorId = valueOrDefault(stringValue(request.get("actorId")), "anonymous");
        String payload = valueOrDefault(stringValue(request.get("payload")), "empty edit");
        CrdtOperationType operationType = inferCompatibilityOperationType(payload);
        String intentText = valueOrDefault(stringValue(request.get("intentText")), payload);
        String insertedText = stringValue(request.get("insertedText"));
        String deletedTextPreview = stringValue(request.get("deletedTextPreview"));
        if (request.get("operationType") == null) {
            insertedText = operationType == CrdtOperationType.DELETE ? insertedText : payload;
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

    private static String escapeNewlines(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("\n", "\\n");
    }

    private CrdtOperationType inferCompatibilityOperationType(String payload) {
        if (payload != null) {
            // 【修复警告 8】避免非标准文本块外发生不必要及不兼容的空白字符匹配
            String stripped = payload.trim();
            if (!stripped.isEmpty()) {
                int i = 0;
                while (i < stripped.length() && !Character.isWhitespace(stripped.charAt(i))) i++;
                String firstToken = stripped.substring(0, i);
                try {
                    return CrdtOperationType.fromString(firstToken);
                } catch (IllegalArgumentException ignored) {
                    // 保留默认类型兼容处理
                }
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
                        valueOrDefault(stringValue(triple.get("operationType")), "UNKNOWN"),
                        valueOrDefault(stringValue(triple.get("intent")), "unspecified intent"),
                        valueOrDefault(stringValue(triple.get("target")), "unspecified target"),
                        valueOrDefault(stringValue(triple.get("precondition")), "unspecified precondition"),
                        valueOrDefault(stringValue(triple.get("impactScope")), "unspecified impact"),
                        valueOrDefault(stringValue(triple.get("polarity")), "neutral")
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
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
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