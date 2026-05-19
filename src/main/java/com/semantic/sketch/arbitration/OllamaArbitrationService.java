package com.semantic.sketch.arbitration;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OllamaArbitrationService {
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern RESPONSE_FIELD = Pattern.compile("\"response\"\\s*:\\s*\"([\\s\\S]*?)\"\\s*(,|})");
    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.60d;

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration timeout;
    private final String model;
    private final double confidenceThreshold;

    public OllamaArbitrationService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                URI.create("http://localhost:11434/api/generate"),
                Duration.ofSeconds(5),
                "deepseek-r1:1.5b",
                DEFAULT_CONFIDENCE_THRESHOLD);
    }

    public OllamaArbitrationService(HttpClient httpClient, URI endpoint, Duration timeout, String model, double confidenceThreshold) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.model = model;
        this.confidenceThreshold = confidenceThreshold;
    }

    public ArbitrationOutput arbitrate(String branchId, ArbitrationInput input) {
        try {
            String payload = buildRequest(input);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return ArbitrationOutput.askHumanFallback("OLLAMA_UNAVAILABLE_HTTP_" + response.statusCode());
            }
            return parseAndMap(branchId, response.body());
        } catch (ConnectException e) {
            return ArbitrationOutput.askHumanFallback("OLLAMA_UNAVAILABLE");
        } catch (java.net.http.HttpTimeoutException | TimeoutException e) {
            return ArbitrationOutput.askHumanFallback("OLLAMA_TIMEOUT");
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ArbitrationOutput.askHumanFallback("OLLAMA_IO_ERROR");
        } catch (RuntimeException e) {
            return ArbitrationOutput.askHumanFallback("OLLAMA_ILLEGAL_JSON");
        }
    }

    private ArbitrationOutput parseAndMap(String branchId, String rawResponse) {
        String llmText = extractResponseText(rawResponse);
        String json = extractFirstJsonObject(llmText);
        ArbitrationAction action = ArbitrationAction.valueOf(readStringField(json, "decision"));
        String reason = readStringField(json, "reason");
        double confidence = Double.parseDouble(readNumberField(json, "confidence"));
        if (confidence < confidenceThreshold) {
            return ArbitrationOutput.askHumanFallback("LOW_CONFIDENCE");
        }
        List<CrdtOperationEnvelope> compensation = List.of();
        if (action == ArbitrationAction.APPLY_COMPENSATION) {
            compensation = parseCompensation(branchId, json);
        }
        return new ArbitrationOutput(action, reason, confidence, compensation);
    }

    private List<CrdtOperationEnvelope> parseCompensation(String branchId, String json) {
        String array = readArrayField(json, "compensationOperations");
        if (array.isBlank()) {
            return List.of();
        }
        Matcher matcher = JSON_BLOCK.matcher(array);
        List<CrdtOperationEnvelope> result = new ArrayList<>();
        while (matcher.find()) {
            String opJson = matcher.group();
            CrdtOperationType type = CrdtOperationType.valueOf(readStringField(opJson, "operationType"));
            String inserted = nullableStringField(opJson, "insertedText");
            String deleted = nullableStringField(opJson, "deletedTextPreview");
            String intent = "SYSTEM_COMPENSATION:" + readStringField(opJson, "intentText");
            result.add(new CrdtOperationEnvelope(
                    UUID.randomUUID().toString(),
                    "SYSTEM_COMPENSATION",
                    branchId,
                    type,
                    Map.of(),
                    nullableStringField(opJson, "targetPath"),
                    nullableIntField(opJson, "fromIndex"),
                    nullableIntField(opJson, "toIndex"),
                    inserted,
                    deleted,
                    intent,
                    null,
                    0L,
                    List.of(),
                    Instant.now()));
        }
        return result;
    }

    private String buildRequest(ArbitrationInput input) {
        String prompt = "Return STRICT JSON: {\"decision\":\"ACCEPT_CRDT|APPLY_COMPENSATION|ASK_HUMAN|ROLLBACK_SEMANTIC_PROJECTION\",\"reason\":\"...\",\"confidence\":0.0," +
                "\"compensationOperations\":[{\"operationType\":\"INSERT|DELETE|REPLACE|MOVE|ANNOTATE\",\"targetPath\":\"...\",\"fromIndex\":0,\"toIndex\":0,\"insertedText\":\"...\",\"deletedTextPreview\":\"...\",\"intentText\":\"...\"}]}\\n" +
                "INPUT=" + input.toString();
        return "{\"model\":\"" + escapeJson(model) + "\",\"stream\":false,\"prompt\":\"" + escapeJson(prompt) + "\"}";
    }

    private static String extractResponseText(String rawBody) {
        Matcher matcher = RESPONSE_FIELD.matcher(rawBody);
        if (!matcher.find()) {
            throw new IllegalArgumentException("missing response field");
        }
        return unescapeJsonString(matcher.group(1));
    }

    private static String extractFirstJsonObject(String text) {
        Matcher matcher = JSON_BLOCK.matcher(text.replace("```json", "").replace("```", ""));
        if (!matcher.find()) {
            throw new IllegalArgumentException("missing json block");
        }
        return matcher.group();
    }

    private static String readStringField(String json, String field) { return mustMatch(json, "\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\""); }
    private static String nullableStringField(String json, String field) {
        Matcher m = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*(null|\\\"([^\\\"]*)\\\")").matcher(json);
        return m.find() ? m.group(2) : null;
    }
    private static String readNumberField(String json, String field) { return mustMatch(json, "\\\"" + field + "\\\"\\s*:\\s*([0-9.]+)"); }
    private static Integer nullableIntField(String json, String field) {
        Matcher m = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*(null|-?[0-9]+)").matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return null;
        return Integer.valueOf(m.group(1));
    }
    private static String readArrayField(String json, String field) {
        Matcher m = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*(\\[[\\s\\S]*])").matcher(json);
        return m.find() ? m.group(1) : "";
    }
    private static String mustMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new IllegalArgumentException("missing field");
        return m.group(1);
    }
    private static String escapeJson(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private static String unescapeJsonString(String s) { return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\"); }
}
