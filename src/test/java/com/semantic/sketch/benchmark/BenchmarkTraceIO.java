package com.semantic.sketch.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BenchmarkTraceIO {
    private BenchmarkTraceIO() {
    }

    static List<BenchmarkTraceEvent> read(Path tracePath) throws IOException {
        List<BenchmarkTraceEvent> out = new ArrayList<>();
        for (String line : Files.readAllLines(tracePath)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            out.add(parseLine(trimmed));
        }
        return out;
    }

    private static BenchmarkTraceEvent parseLine(String line) {
        String sanitized = line.substring(1, line.length() - 1);
        String[] parts = sanitized.split(",");
        String opId = ""; String actorId = ""; long seq = 0; String mode = "mixed";
        String opType = "insert"; int index = 0; String text = ""; int deleteLen = 0; long logicalTs = 0;
        for (String part : parts) {
            String[] kv = part.split(":", 2);
            String k = kv[0].trim().replace("\"", "");
            String v = kv[1].trim();
            switch (k) {
                case "opId" -> opId = unquote(v);
                case "actorId" -> actorId = unquote(v);
                case "seq" -> seq = Long.parseLong(v);
                case "mode" -> mode = unquote(v);
                case "opType" -> opType = unquote(v);
                case "index" -> index = Integer.parseInt(v);
                case "text" -> text = unquote(v);
                case "deleteLen" -> deleteLen = Integer.parseInt(v);
                case "logicalTs" -> logicalTs = Long.parseLong(v);
                default -> { }
            }
        }
        return new BenchmarkTraceEvent(opId, actorId, seq, mode, opType, index, text, deleteLen, logicalTs);
    }

    private static String unquote(String raw) {
        return raw.trim().replaceAll("^\"|\"$", "");
    }
}
