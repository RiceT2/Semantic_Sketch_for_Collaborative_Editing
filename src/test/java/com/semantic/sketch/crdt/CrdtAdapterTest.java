package com.semantic.sketch.crdt;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrdtAdapterTest {

    @Test
    void inMemoryTextAdapter_appliesInsertDeleteReplaceAndTracksStateVector() {
        InMemoryTextCrdtAdapter adapter = new InMemoryTextCrdtAdapter();

        adapter.apply(envelope("op-1", CrdtOperationType.INSERT, 0, null, "hello world", null, Map.of("alice", 1L)));
        adapter.apply(envelope("op-2", CrdtOperationType.DELETE, 5, 11, null, " world", Map.of("alice", 2L)));
        adapter.apply(envelope("op-3", CrdtOperationType.REPLACE, 0, 5, "semantic sketch", "hello", Map.of("bob", 1L)));

        assertEquals("semantic sketch", adapter.renderDocument("main"));
        assertEquals(Map.of("alice", 2L, "bob", 1L), adapter.stateVector("main"));
        assertEquals("semantic sketch", adapter.snapshot("main").get("document"));
    }

    @Test
    void inMemoryTextAdapter_rejectsUnsupportedOperationTypes() {
        InMemoryTextCrdtAdapter adapter = new InMemoryTextCrdtAdapter();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                adapter.apply(envelope("op-format", CrdtOperationType.FORMAT, 0, 0, "", null, Map.of("alice", 1L))));

        assertTrue(error.getMessage().contains("Unsupported in-memory text operation"));
    }

    @Test
    void inMemoryTextAdapter_concurrentSetConvergesAcrossRandomReplayOrder() {
        List<CrdtOperationEnvelope> concurrent = List.of(
                insertById("op-a1", "alice", 1, null, null, "A", 1),
                insertById("op-b1", "bob", 1, null, null, "B", 1),
                insertById("op-a2", "alice", 2, new TextAtomId("alice", 1), null, "a", 2),
                insertById("op-b2", "bob", 2, new TextAtomId("bob", 1), null, "b", 2),
                deleteById("op-del", "alice", new TextAtomId("bob", 1), 3)
        );

        InMemoryTextCrdtAdapter baselineAdapter = new InMemoryTextCrdtAdapter();
        concurrent.forEach(baselineAdapter::apply);
        String baseline = baselineAdapter.renderDocument("main");

        for (int seed = 0; seed < 20; seed++) {
            List<CrdtOperationEnvelope> replay = new ArrayList<>(concurrent);
            Collections.shuffle(replay, new Random(seed));
            InMemoryTextCrdtAdapter adapter = new InMemoryTextCrdtAdapter();
            replay.forEach(adapter::apply);
            assertEquals(baseline, adapter.renderDocument("main"));
        }
    }

    @Test
    void yjsAdapter_storesBase64UpdatesUntilExternalRendererIsAttached() {
        YjsUpdateCrdtAdapter adapter = new YjsUpdateCrdtAdapter();
        adapter.apply(new CrdtOperationEnvelope(
                "op-yjs",
                "alice",
                "main",
                CrdtOperationType.REPLACE,
                Map.of("alice", 7L),
                null,
                null,
                null,
                null,
                null,
                "apply yjs update",
                "AQIDBA==",
                0L,
                List.of(),
                Instant.parse("2026-05-11T00:00:00Z")
        ));

        Map<String, ?> snapshot = adapter.snapshot("main");

        assertEquals("\u0001\u0002\u0003\u0004", adapter.renderDocument("main"));
        assertEquals(Map.of("updates", 1L), adapter.stateVector("main"));
        assertEquals("rendered_by_sidecar", snapshot.get("renderingStatus"));
        assertTrue(snapshot.containsKey("documentHash"));
        assertTrue(snapshot.containsKey("sidecarVersion"));
        assertTrue(String.valueOf(snapshot.get("updates")).contains("AQIDBA=="));
    }

    @Test
    void yjsAdapter_convergesAcrossShuffledUpdateSetAndMatchesGoldenHash() {
        List<CrdtOperationEnvelope> updates = List.of(
                yjsEnvelope("op-1", "alice", 1, "QQ=="),
                yjsEnvelope("op-2", "bob", 1, "Qg=="),
                yjsEnvelope("op-3", "alice", 2, "Qw==")
        );

        YjsUpdateCrdtAdapter reference = new YjsUpdateCrdtAdapter();
        updates.forEach(reference::apply);
        Map<String, ?> baselineSnapshot = reference.snapshot("main");
        String baselineText = reference.renderDocument("main");
        String goldenHash = String.valueOf(baselineSnapshot.get("documentHash"));

        for (int seed = 0; seed < 20; seed++) {
            List<CrdtOperationEnvelope> replay = new ArrayList<>(updates);
            Collections.shuffle(replay, new Random(seed));

            YjsUpdateCrdtAdapter adapter = new YjsUpdateCrdtAdapter();
            replay.forEach(adapter::apply);
            Map<String, ?> snapshot = adapter.snapshot("main");

            assertEquals(baselineText, adapter.renderDocument("main"));
            assertEquals(goldenHash, snapshot.get("documentHash"));
        }
    }

    private CrdtOperationEnvelope yjsEnvelope(String opId, String actor, long tick, String update) {
        return new CrdtOperationEnvelope(
                opId,
                actor,
                "main",
                CrdtOperationType.REPLACE,
                Map.of(actor, tick),
                null,
                null,
                null,
                null,
                null,
                "apply yjs update",
                update,
                0L,
                List.of(),
                Instant.parse("2026-05-11T00:00:00Z")
        );
    }

    private CrdtOperationEnvelope envelope(String opId,
                                           CrdtOperationType operationType,
                                           Integer fromIndex,
                                           Integer toIndex,
                                           String insertedText,
                                           String deletedTextPreview,
                                           Map<String, Long> vectorClock) {
        return new CrdtOperationEnvelope(
                opId,
                "actor",
                "main",
                operationType,
                vectorClock,
                "/document",
                fromIndex,
                toIndex,
                insertedText,
                deletedTextPreview,
                operationType.toJsonValue(),
                null,
                0L,
                List.of(),
                Instant.parse("2026-05-11T00:00:00Z")
        );
    }

    private CrdtOperationEnvelope insertById(String opId, String actor, long seq, TextAtomId left, TextAtomId right, String text, long lamport) {
        return new CrdtOperationEnvelope(opId, actor, "main", CrdtOperationType.INSERT, Map.of(actor, lamport), "/document", null,
                null, null, null, "insert", null, 0L, List.of(),
                new InsertAfter(new TextAtomId(actor, seq), left, right, text, lamport), null,
                Instant.parse("2026-05-11T00:00:00Z"));
    }

    private CrdtOperationEnvelope deleteById(String opId, String actor, TextAtomId atomId, long lamport) {
        return new CrdtOperationEnvelope(opId, actor, "main", CrdtOperationType.DELETE, Map.of(actor, lamport), "/document", null,
                null, null, null, "delete", null, 0L, List.of(), null, new DeleteById(atomId),
                Instant.parse("2026-05-11T00:00:00Z"));
    }
}
