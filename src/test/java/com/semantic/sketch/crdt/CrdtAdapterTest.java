package com.semantic.sketch.crdt;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

        assertEquals("", adapter.renderDocument("main"));
        assertEquals(Map.of("alice", 7L), adapter.stateVector("main"));
        assertTrue(String.valueOf(snapshot.get("renderingStatus")).contains("Node sidecar"));
        assertTrue(String.valueOf(snapshot.get("updates")).contains("AQIDBA=="));
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
}
