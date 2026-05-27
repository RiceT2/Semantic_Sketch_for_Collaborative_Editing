package com.semantic.sketch.benchmark;

import com.semantic.sketch.crdt.CrdtAdapter;
import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.crdt.InMemoryTextCrdtAdapter;
import com.semantic.sketch.crdt.YjsUpdateCrdtAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class CrdtBenchmarkRunner {
    private static final String BRANCH = "bench";

    @Test
    void smokeBenchmarkAndPersistResults() throws Exception {
        int docSize = Integer.getInteger("benchmark.docSize", 64);
        int actors = Integer.getInteger("benchmark.actors", 3);
        int concurrency = Integer.getInteger("benchmark.concurrency", 2);
        int operations = Integer.getInteger("benchmark.operations", 120);
        String mode = System.getProperty("benchmark.mode", "mixed");
        String traceName = System.getProperty("benchmark.traceName", "smoke");
        Path trace = BenchmarkTraceGenerator.generate(Path.of("benchmarks/traces/" + traceName + ".jsonl"),
                docSize, actors, concurrency, operations, mode);
        List<BenchmarkTraceEvent> events = BenchmarkTraceIO.read(trace);

        Map<String, String> results = new HashMap<>();
        results.put("in_memory", runAdapter("in_memory", new InMemoryTextCrdtAdapter(), events));
        results.put("yjs_sidecar", runAdapter("yjs_sidecar", new YjsUpdateCrdtAdapter(), events));

        Path resultDir = Path.of("benchmarks/results", LocalDate.now().toString());
        Files.createDirectories(resultDir);
        for (Map.Entry<String, String> entry : results.entrySet()) {
            Files.writeString(resultDir.resolve(entry.getKey() + "_" + traceName + ".json"), entry.getValue());
        }
    }

    private String runAdapter(String name, CrdtAdapter adapter, List<BenchmarkTraceEvent> events) throws Exception {
        List<Long> latenciesMicros = new ArrayList<>();
        long start = System.nanoTime();
        long peakMemory = usedMemory();
        int failures = 0;
        for (BenchmarkTraceEvent event : events) {
            long t0 = System.nanoTime();
            try {
                adapter.apply(toEnvelope(event, name));
            } catch (Exception e) {
                failures++;
            }
            long dt = System.nanoTime() - t0;
            latenciesMicros.add(dt / 1_000);
            peakMemory = Math.max(peakMemory, usedMemory());
        }
        long elapsedNanos = System.nanoTime() - start;
        String document = adapter.renderDocument(BRANCH);
        String hash = sha256(document);
        latenciesMicros.sort(Long::compareTo);
        long p50 = percentile(latenciesMicros, 50);
        long p95 = percentile(latenciesMicros, 95);
        double opsPerSec = events.isEmpty() ? 0 : events.size() / (elapsedNanos / 1_000_000_000.0);
        double failureRate = events.isEmpty() ? 0.0 : failures / (double) events.size();

        return """
                {
                  \"adapter\": \"%s\",
                  \"trace\": \"smoke\",
                  \"opsPerSec\": %.2f,
                  \"p50ApplyMicros\": %d,
                  \"p95ApplyMicros\": %d,
                  \"peakMemoryBytes\": %d,
                  \"finalDocumentHash\": \"%s\",
                  \"consistencyFailureRate\": %.4f,
                  \"samples\": %d,
                  \"generatedAt\": \"%s\"
                }
                """.formatted(name, opsPerSec, p50, p95, peakMemory, hash, failureRate, events.size(), Instant.now().toString());
    }

    private CrdtOperationEnvelope toEnvelope(BenchmarkTraceEvent e, String adapterName) {
        Map<String, Long> vc = Map.of(e.actorId(), e.seq());
        if ("yjs_sidecar".equals(adapterName)) {
            String update = Base64.getEncoder().encodeToString(("insert".equals(e.opType()) ? e.text() : "").getBytes(StandardCharsets.UTF_8));
            return new CrdtOperationEnvelope(e.opId(), e.actorId(), BRANCH, CrdtOperationType.INSERT, vc, "/document",
                    e.index(), e.index(), e.text(), null, e.mode(), update, null, "yjs-update-base64", 2, 0L, List.of(), Instant.now());
        }
        CrdtOperationType opType = "insert".equals(e.opType()) ? CrdtOperationType.INSERT : CrdtOperationType.DELETE;
        Integer to = "delete".equals(e.opType()) ? e.index() + e.deleteLen() : e.index();
        return new CrdtOperationEnvelope(e.opId(), e.actorId(), BRANCH, opType, vc, "/document",
                e.index(), to, e.text(), null, e.mode(), null, null, "json-crdt-patch-v1", 2, 0L, List.of(), Instant.now());
    }

    private long percentile(List<Long> values, int p) {
        if (values.isEmpty()) return 0;
        int idx = (int) Math.ceil((p / 100.0) * values.size()) - 1;
        return values.get(Math.max(0, Math.min(values.size() - 1, idx)));
    }

    private long usedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
