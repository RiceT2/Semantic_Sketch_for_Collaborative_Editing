package com.semantic.sketch.benchmark;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.crdt.InMemoryTextCrdtAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Small no-dependency benchmark harness inspired by dmonad/crdt-benchmarks.
 *
 * <p>It is intentionally runnable with Maven's exec plugin so the Java prototype can be compared against
 * JavaScript CRDT benchmark outputs using the same B1-B4 scenario names and metric vocabulary.</p>
 */
public final class CrdtBenchmarkRunner {
    private static final String BRANCH_ID = "benchmark";
    private static final String TARGET_PATH = "/document";
    private static final Instant CREATED_AT = Instant.parse("2026-06-01T00:00:00Z");

    private CrdtBenchmarkRunner() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        CrdtBenchmarkResult result = run(config);
        String json = result.toJson();
        if (config.outputPath() == null) {
            System.out.println(json);
        } else {
            Path parent = config.outputPath().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(config.outputPath(), json + System.lineSeparator(), StandardCharsets.UTF_8);
            System.out.println("Wrote benchmark result to " + config.outputPath());
        }
    }

    public static CrdtBenchmarkResult run(Config config) throws IOException {
        return switch (config.scenario()) {
            case "b1-append" -> replay("B1 append", 1, generateAppend(config.operations()),
                    "No-conflict append scenario matching crdt-benchmarks B1 metric vocabulary.");
            case "b1-prepend" -> replay("B1 prepend", 1, generatePrepend(config.operations()),
                    "No-conflict prepend scenario matching crdt-benchmarks B1 metric vocabulary.");
            case "b1-random" -> replay("B1 random insert/delete", 1, generateRandomEdits(config.operations(), config.seed()),
                    "No-conflict random insert/delete scenario matching crdt-benchmarks B1 metric vocabulary.");
            case "b2-conflict" -> runTwoUserConflict(config.operations());
            case "b3-many-conflicts" -> runManyConflicts(config.operations());
            case "b4-edit-by-index" -> runAutomergeTrace(config);
            default -> throw new IllegalArgumentException("Unknown scenario: " + config.scenario());
        };
    }

    private static CrdtBenchmarkResult runTwoUserConflict(int operations) {
        int perActor = Math.max(1, operations / 2);
        List<ActorEdit> edits = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            edits.add(new ActorEdit("seed", CrdtBenchmarkOperation.insert(i, "x")));
        }
        for (int i = 0; i < perActor; i++) {
            edits.add(new ActorEdit("alice", CrdtBenchmarkOperation.insert(50, "A")));
            edits.add(new ActorEdit("bob", CrdtBenchmarkOperation.insert(50, "B")));
        }
        return replay("B2 two-user conflicts", 3, edits,
                "Two actors start from a 100-character seed and produce same-position concurrent inserts.");
    }

    private static CrdtBenchmarkResult runManyConflicts(int operations) {
        int actorCount = Math.max(2, (int) Math.sqrt(Math.max(1, operations)));
        List<ActorEdit> edits = new ArrayList<>();
        for (int actor = 0; actor < actorCount; actor++) {
            for (int i = 0; i < actorCount; i++) {
                String text = Character.toString((char) ('a' + actor % 26));
                edits.add(new ActorEdit("actor-" + actor, CrdtBenchmarkOperation.insert(0, text)));
            }
        }
        return replay("B3 many conflicts", actorCount, edits,
                "sqrt(N) actors generate same-index inserts to approximate crdt-benchmarks B3 conflict pressure.");
    }

    private static CrdtBenchmarkResult runAutomergeTrace(Config config) throws IOException {
        if (config.tracePath() == null) {
            throw new IllegalArgumentException("--trace is required for b4-edit-by-index");
        }
        List<CrdtBenchmarkOperation> operations = AutomergeEditingTraceLoader.load(config.tracePath(), config.operations());
        List<ActorEdit> edits = operations.stream().map(operation -> new ActorEdit("trace", operation)).toList();
        return replay("B4 real-world edit-by-index", 1, edits,
                "Replays Automerge perf edit-by-index operations used by crdt-benchmarks B4.");
    }

    private static List<ActorEdit> generateAppend(int operations) {
        List<ActorEdit> edits = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            edits.add(new ActorEdit("actor", CrdtBenchmarkOperation.insert(i, "a")));
        }
        return edits;
    }

    private static List<ActorEdit> generatePrepend(int operations) {
        List<ActorEdit> edits = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            edits.add(new ActorEdit("actor", CrdtBenchmarkOperation.insert(0, "a")));
        }
        return edits;
    }

    private static List<ActorEdit> generateRandomEdits(int operations, long seed) {
        Random random = new Random(seed);
        List<ActorEdit> edits = new ArrayList<>();
        int length = 0;
        for (int i = 0; i < operations; i++) {
            if (length == 0 || random.nextDouble() < 0.65) {
                int index = random.nextInt(length + 1);
                String text = Character.toString((char) ('a' + random.nextInt(26)));
                edits.add(new ActorEdit("actor", CrdtBenchmarkOperation.insert(index, text)));
                length++;
            } else {
                int index = random.nextInt(length);
                edits.add(new ActorEdit("actor", CrdtBenchmarkOperation.delete(index, 1)));
                length--;
            }
        }
        return edits;
    }

    private static CrdtBenchmarkResult replay(String benchmark, int actorCount, List<ActorEdit> edits, String notes) {
        InMemoryTextCrdtAdapter adapter = new InMemoryTextCrdtAdapter();
        long beforeMemory = usedMemoryAfterGc();
        long updateSize = 0L;
        long start = System.nanoTime();
        Map<String, Long> actorTicks = new HashMap<>();
        for (int i = 0; i < edits.size(); i++) {
            ActorEdit edit = edits.get(i);
            long actorTick = actorTicks.merge(edit.actorId(), 1L, Long::sum);
            CrdtOperationEnvelope envelope = toEnvelope(i + 1, edit.actorId(), actorTick, edit.operation());
            updateSize += envelope.getCrdtPayload().getBytes(StandardCharsets.UTF_8).length;
            adapter.apply(envelope);
        }
        String document = adapter.renderDocument(BRANCH_ID);
        long timeNanos = System.nanoTime() - start;
        long afterMemory = usedMemoryAfterGc();

        long encodeStart = System.nanoTime();
        byte[] encoded = document.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(encoded);
        long encodeTimeNanos = System.nanoTime() - encodeStart;

        long parseStart = System.nanoTime();
        new String(encoded, StandardCharsets.UTF_8).length();
        long parseTimeNanos = System.nanoTime() - parseStart;

        return new CrdtBenchmarkResult(
                benchmark,
                InMemoryTextCrdtAdapter.class.getSimpleName(),
                edits.size(),
                actorCount,
                timeNanos,
                updateSize,
                edits.isEmpty() ? 0L : updateSize / edits.size(),
                encoded.length,
                encodeTimeNanos,
                parseTimeNanos,
                afterMemory - beforeMemory,
                hash,
                notes
        );
    }

    private static CrdtOperationEnvelope toEnvelope(int sequence, String actorId, long actorTick, CrdtBenchmarkOperation operation) {
        if (operation.isInsert()) {
            return envelope("bench-" + sequence, actorId, actorTick, CrdtOperationType.INSERT,
                    operation.index(), null, operation.insertedText(), null);
        }
        return envelope("bench-" + sequence, actorId, actorTick, CrdtOperationType.DELETE,
                operation.index(), operation.index() + operation.deleteCount(), null, null);
    }

    private static CrdtOperationEnvelope envelope(String opId,
                                                  String actorId,
                                                  long tick,
                                                  CrdtOperationType operationType,
                                                  Integer fromIndex,
                                                  Integer toIndex,
                                                  String insertedText,
                                                  String deletedTextPreview) {
        return new CrdtOperationEnvelope(
                opId,
                actorId,
                BRANCH_ID,
                operationType,
                Map.of(actorId, tick),
                TARGET_PATH,
                fromIndex,
                toIndex,
                insertedText,
                deletedTextPreview,
                operationType.toJsonValue(),
                null,
                0L,
                List.of(),
                CREATED_AT
        );
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long usedMemoryAfterGc() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record ActorEdit(String actorId, CrdtBenchmarkOperation operation) {
    }

    public record Config(String scenario, int operations, long seed, Path tracePath, Path outputPath) {
        static Config parse(String[] args) {
            String scenario = "b1-append";
            int operations = 10_000;
            long seed = 1L;
            Path tracePath = null;
            Path outputPath = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--scenario" -> scenario = requireValue(args, ++i, "--scenario").toLowerCase(Locale.ROOT);
                    case "--operations" -> operations = Integer.parseInt(requireValue(args, ++i, "--operations"));
                    case "--seed" -> seed = Long.parseLong(requireValue(args, ++i, "--seed"));
                    case "--trace" -> tracePath = Path.of(requireValue(args, ++i, "--trace"));
                    case "--output" -> outputPath = Path.of(requireValue(args, ++i, "--output"));
                    case "--help" -> throw new IllegalArgumentException(usage());
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i] + System.lineSeparator() + usage());
                }
            }
            if (operations < 0) {
                throw new IllegalArgumentException("--operations must be non-negative");
            }
            return new Config(scenario, operations, seed, tracePath, outputPath);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[index];
        }

        private static String usage() {
            return "Usage: CrdtBenchmarkRunner --scenario <b1-append|b1-prepend|b1-random|b2-conflict|b3-many-conflicts|b4-edit-by-index> "
                    + "[--operations N] [--seed N] [--trace path/to/editing-trace.js] [--output target/benchmark-results/result.json]";
        }
    }
}
