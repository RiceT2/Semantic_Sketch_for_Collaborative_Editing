package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.model.ConflictEdge;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactorGraphBuilderTest {

    private final FactorGraphBuilder builder = new FactorGraphBuilder();

    @Test
    void envelopeStructuralMetadataCapturesPathOverlapDeleteAndFormatFeatures() {
        ConflictEdge insertDelete = singleEdge(
                envelope("insert", CrdtOperationType.INSERT, "/doc/title", 5, null),
                envelope("delete", CrdtOperationType.DELETE, "/doc/title", 0, 10)
        );
        ConflictEdge formatDelete = singleEdge(
                envelope("format", CrdtOperationType.FORMAT, "/doc/title", 4, 8),
                envelope("delete", CrdtOperationType.DELETE, "/doc/title", 6, 12)
        );

        assertEquals(0.0d, insertDelete.semanticDistance());
        assertTrue(insertDelete.structuralRisk() > 0.9d);
        assertEquals(true, insertDelete.metadata().get("sameTargetPath"));
        assertEquals(true, insertDelete.metadata().get("rangeOverlaps"));
        assertEquals(true, insertDelete.metadata().get("deleteCoversInsert"));
        assertEquals(0.0d, insertDelete.metadata().get("semanticDistance"));

        assertTrue(formatDelete.structuralRisk() > 0.8d);
        assertEquals(true, formatDelete.metadata().get("formatOnDeletedRange"));
    }

    @Test
    void operationCompatibilityMatrixOrdersMajorCombinationsByRisk() {
        ConflictEdge formatInsert = singleEdge(
                envelope("format", CrdtOperationType.FORMAT, "/doc/body", 1, 3),
                envelope("insert", CrdtOperationType.INSERT, "/doc/body", 2, null)
        );
        ConflictEdge insertInsert = singleEdge(
                envelope("insert-a", CrdtOperationType.INSERT, "/doc/body", 2, null),
                envelope("insert-b", CrdtOperationType.INSERT, "/doc/body", 2, null)
        );
        ConflictEdge insertDelete = singleEdge(
                envelope("insert", CrdtOperationType.INSERT, "/doc/body", 2, null),
                envelope("delete", CrdtOperationType.DELETE, "/doc/body", 0, 4)
        );
        ConflictEdge replaceReplace = singleEdge(
                envelope("replace-a", CrdtOperationType.REPLACE, "/doc/body", 0, 4),
                envelope("replace-b", CrdtOperationType.REPLACE, "/doc/body", 1, 5)
        );
        ConflictEdge moveDelete = singleEdge(
                envelope("move", CrdtOperationType.MOVE, "/doc/body", 0, 4),
                envelope("delete", CrdtOperationType.DELETE, "/doc/body", 1, 3)
        );

        assertTrue(formatInsert.structuralRisk() < insertInsert.structuralRisk());
        assertTrue(insertInsert.structuralRisk() < insertDelete.structuralRisk());
        assertTrue(insertDelete.structuralRisk() <= replaceReplace.structuralRisk());
        assertTrue(insertDelete.structuralRisk() <= moveDelete.structuralRisk());
        assertTrue(formatInsert.conflictWeight() < insertDelete.conflictWeight());
        assertTrue(insertInsert.conflictWeight() < replaceReplace.conflictWeight());
    }

    @Test
    void differentTargetPathsReduceStructuralRisk() {
        ConflictEdge samePath = singleEdge(
                envelope("insert-a", CrdtOperationType.INSERT, "/doc/a", 4, null),
                envelope("insert-b", CrdtOperationType.INSERT, "/doc/a", 4, null)
        );
        ConflictEdge differentPath = singleEdge(
                envelope("insert-a", CrdtOperationType.INSERT, "/doc/a", 4, null),
                envelope("insert-b", CrdtOperationType.INSERT, "/doc/b", 4, null)
        );

        assertTrue(differentPath.structuralRisk() < samePath.structuralRisk());
        assertEquals(false, differentPath.metadata().get("sameTargetPath"));
    }

    private ConflictEdge singleEdge(CrdtOperationEnvelope left, CrdtOperationEnvelope right) {
        return builder.buildFromEnvelopes(List.of(left, right)).edges().get(0);
    }

    private CrdtOperationEnvelope envelope(String opId,
                                           CrdtOperationType operationType,
                                           String targetPath,
                                           Integer fromIndex,
                                           Integer toIndex) {
        return new CrdtOperationEnvelope(
                opId,
                "actor-" + opId,
                "main",
                operationType,
                Map.of(opId, 1L),
                targetPath,
                fromIndex,
                toIndex,
                operationType == CrdtOperationType.INSERT ? "text" : null,
                operationType == CrdtOperationType.DELETE ? "text" : null,
                operationType.toJsonValue().toLowerCase(),
                null,
                0L,
                List.of(),
                Instant.parse("2026-05-11T00:00:00Z")
        );
    }
}
