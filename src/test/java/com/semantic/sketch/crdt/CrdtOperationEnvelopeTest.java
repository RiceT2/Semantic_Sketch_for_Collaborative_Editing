package com.semantic.sketch.crdt;

import com.semantic.sketch.model.SemanticTriple;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrdtOperationEnvelopeTest {

    @Test
    void operationType_mapsStableJsonAndStringValues() {
        assertEquals("INSERT", CrdtOperationType.INSERT.toJsonValue());
        assertEquals("DELETE", CrdtOperationType.DELETE.toString());
        assertEquals(CrdtOperationType.REPLACE, CrdtOperationType.fromJsonValue("REPLACE"));
        assertEquals(CrdtOperationType.FORMAT, CrdtOperationType.fromJsonValue("format"));
        assertEquals(CrdtOperationType.COMPACTED, CrdtOperationType.fromString("compacted"));
        assertEquals(CrdtOperationType.SNAPSHOT, CrdtOperationType.fromString("snap-shot"));
        assertThrows(IllegalArgumentException.class, () -> CrdtOperationType.fromJsonValue("unknown"));
    }

    @Test
    void message_convertsToEnvelopeWithLegacyPayloadAsIntent() {
        Message message = new Message("op-1", "alice", "insert hello", Map.of("alice", 2L), 42L);

        CrdtOperationEnvelope envelope = message.toEnvelope("main", CrdtOperationType.INSERT);

        assertEquals("op-1", envelope.getOpId());
        assertEquals("alice", envelope.getActorId());
        assertEquals("main", envelope.getBranchId());
        assertEquals(CrdtOperationType.INSERT, envelope.getOperationType());
        assertEquals(Map.of("alice", 2L), envelope.getVectorClock());
        assertEquals("insert hello", envelope.getInsertedText());
        assertEquals("insert hello", envelope.getIntentText());
        assertEquals(42L, envelope.getSemanticFingerprint());
    }

    @Test
    void envelope_convertsToLegacyMessagePreservingCoreFields() {
        CrdtOperationEnvelope envelope = new CrdtOperationEnvelope(
                "op-2",
                "bob",
                "feature",
                CrdtOperationType.REPLACE,
                Map.of("bob", 5L),
                "/doc/blocks/0",
                3,
                8,
                "new text",
                "old text",
                "replace title",
                "AQID",
                99L,
                List.of(new SemanticTriple("replace title", "title exists", "heading")),
                Instant.parse("2026-05-11T00:00:00Z")
        );

        Message message = Message.fromEnvelope(envelope);

        assertEquals("op-2", message.getOpId());
        assertEquals("bob", message.getActorId());
        assertEquals("new text", message.getPayload());
        assertEquals(Map.of("bob", 5L), message.getVectorClock());
        assertEquals(99L, message.getSemanticFingerprint());
    }
}
