package com.semantic.sketch.arbitration;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.CrdtOperationEnvelope;

import java.util.List;
import java.util.Objects;

public record ArbitrationInput(String rawDocument,
                               String crdtMergedDocument,
                               List<CrdtOperationEnvelope> candidateOperations,
                               MergeDecision systemPlan,
                               double residualR) {
    public ArbitrationInput {
        rawDocument = Objects.requireNonNullElse(rawDocument, "");
        crdtMergedDocument = Objects.requireNonNullElse(crdtMergedDocument, "");
        candidateOperations = List.copyOf(Objects.requireNonNullElse(candidateOperations, List.of()));
        systemPlan = Objects.requireNonNull(systemPlan, "systemPlan");
    }
}
