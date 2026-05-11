package com.semantic.sketch.semantic;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.model.SemanticTriple;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SemanticFingerprintServiceEnvelopeTest {

    private final LightweightSemanticFingerprintService service = new LightweightSemanticFingerprintService();

    @Test
    void insertAndDeleteWithSameTextProduceDifferentSemanticFingerprintsAndTriples() {
        CrdtOperationEnvelope insert = envelope(CrdtOperationType.INSERT, "shared text", null);
        CrdtOperationEnvelope delete = envelope(CrdtOperationType.DELETE, null, "shared text");

        long insertFingerprint = service.fingerprint(insert);
        long deleteFingerprint = service.fingerprint(delete);
        List<SemanticTriple> insertTriples = service.extractTriples(insert);
        List<SemanticTriple> deleteTriples = service.extractTriples(delete);

        assertNotEquals(insertFingerprint, deleteFingerprint);
        assertNotEquals(insertTriples, deleteTriples);
        assertEquals("INSERT", insertTriples.get(0).operationType());
        assertEquals("DELETE", deleteTriples.get(0).operationType());
        assertEquals("positive", insertTriples.get(0).polarity());
        assertEquals("negative", deleteTriples.get(0).polarity());
    }

    @Test
    void replaceSemanticInputIncludesOldPreviewNewTextAndTargetPath() {
        CrdtOperationEnvelope replace = envelope(CrdtOperationType.REPLACE, "new heading", "old heading");

        String semanticInput = SemanticFingerprintService.toSemanticInput(replace);

        assertEquals("update heading old heading new heading /doc/title", semanticInput);
    }

    @Test
    void onnxServiceFallsBackWhenConfiguredModelIsUnavailable() {
        OnnxDistilBertSemanticFingerprintService onnxService = new OnnxDistilBertSemanticFingerprintService();
        CrdtOperationEnvelope insert = envelope(CrdtOperationType.INSERT, "shared text", null);

        assertFalse(onnxService.isModelAvailable());
        assertEquals(service.fingerprint(insert), onnxService.fingerprint(insert));
        assertEquals(service.extractTriples(insert), onnxService.extractTriples(insert));
    }

    private CrdtOperationEnvelope envelope(CrdtOperationType operationType, String insertedText, String deletedTextPreview) {
        return new CrdtOperationEnvelope(
                "op-" + operationType.toJsonValue().toLowerCase(),
                "alice",
                "main",
                operationType,
                Map.of("alice", 1L),
                "/doc/title",
                0,
                11,
                insertedText,
                deletedTextPreview,
                "update heading",
                null,
                0L,
                List.of(),
                Instant.parse("2026-05-11T00:00:00Z")
        );
    }
}
