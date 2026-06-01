package com.semantic.sketch.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;

/** Metrics compatible with the terminology used by dmonad/crdt-benchmarks. */
public record CrdtBenchmarkResult(String benchmark,
                                  String adapter,
                                  int operationCount,
                                  int actorCount,
                                  long timeNanos,
                                  long updateSizeBytes,
                                  long avgUpdateSizeBytes,
                                  long docSizeBytes,
                                  long encodeTimeNanos,
                                  long parseTimeNanos,
                                  long memUsedBytes,
                                  String finalDocumentHash,
                                  String notes) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("benchmark", benchmark);
        out.put("adapter", adapter);
        out.put("operationCount", operationCount);
        out.put("actorCount", actorCount);
        out.put("timeNanos", timeNanos);
        out.put("timeMillis", nanosToMillis(timeNanos));
        out.put("updateSizeBytes", updateSizeBytes);
        out.put("avgUpdateSizeBytes", avgUpdateSizeBytes);
        out.put("docSizeBytes", docSizeBytes);
        out.put("encodeTimeNanos", encodeTimeNanos);
        out.put("parseTimeNanos", parseTimeNanos);
        out.put("memUsedBytes", memUsedBytes);
        out.put("finalDocumentHash", finalDocumentHash);
        if (notes != null && !notes.isBlank()) {
            out.put("notes", notes);
        }
        return out;
    }

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        int index = 0;
        for (Map.Entry<String, Object> entry : toMap().entrySet()) {
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("  \"").append(escape(entry.getKey())).append("\": ");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append("\"").append(escape(String.valueOf(value))).append("\"");
            }
        }
        json.append("\n}");
        return json.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
