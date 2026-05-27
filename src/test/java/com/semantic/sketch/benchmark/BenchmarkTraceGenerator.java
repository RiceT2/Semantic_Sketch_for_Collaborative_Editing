package com.semantic.sketch.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

final class BenchmarkTraceGenerator {
    private BenchmarkTraceGenerator() {
    }

    static Path generate(Path outputFile, int docSize, int actorCount, int concurrencyWindow, int operations, String mode) throws IOException {
        Random random = new Random(42L + docSize + actorCount + operations + mode.hashCode());
        StringBuilder text = new StringBuilder("x".repeat(Math.max(0, docSize)));
        StringBuilder lines = new StringBuilder();
        long logicalTs = 1;
        int window = Math.max(1, concurrencyWindow);
        for (int i = 0; i < operations; i++) {
            int actorNum = (i % actorCount) + 1;
            String actor = "actor-" + actorNum;
            String opType = chooseOpType(random, mode);
            if (text.isEmpty()) opType = "insert";
            int idx = text.isEmpty() ? 0 : random.nextInt(text.length() + ("insert".equals(opType) ? 1 : 0));
            String payload = "";
            int deleteLen = 0;
            if ("insert".equals(opType)) {
                payload = String.valueOf((char) ('a' + (i % 26)));
                text.insert(Math.min(idx, text.length()), payload);
            } else {
                deleteLen = Math.min(1 + random.nextInt(Math.min(3, text.length())), Math.max(1, text.length() - idx));
                text.delete(idx, Math.min(text.length(), idx + deleteLen));
            }
            long seq = (i / actorCount) + 1;
            logicalTs += random.nextInt(window);
            lines.append(String.format("{\"opId\":\"op-%d\",\"actorId\":\"%s\",\"seq\":%d,\"mode\":\"%s\",\"opType\":\"%s\",\"index\":%d,\"text\":\"%s\",\"deleteLen\":%d,\"logicalTs\":%d}%n",
                    i + 1, actor, seq, mode, opType, idx, payload, deleteLen, logicalTs));
        }
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, lines.toString());
        return outputFile;
    }

    private static String chooseOpType(Random random, String mode) {
        int roll = random.nextInt(100);
        return switch (mode) {
            case "insert-heavy" -> roll < 85 ? "insert" : "delete";
            case "delete-heavy" -> roll < 25 ? "insert" : "delete";
            default -> roll < 55 ? "insert" : "delete";
        };
    }
}
