package com.semantic.sketch.arbitration;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;

import java.util.List;
import java.util.Objects;

public record ArbitrationOutput(ArbitrationAction action,
                                String reason,
                                double confidence,
                                List<CrdtOperationEnvelope> compensationOperations) {
    public ArbitrationOutput {
        action = Objects.requireNonNull(action, "action");
        reason = Objects.requireNonNullElse(reason, "");
        confidence = Math.max(0.0d, Math.min(1.0d, confidence));
        compensationOperations = List.copyOf(Objects.requireNonNullElse(compensationOperations, List.of()));
    }

    public static ArbitrationOutput askHumanFallback(String reason) {
        return new ArbitrationOutput(ArbitrationAction.ASK_HUMAN, reason, 0.0d, List.of());
    }
}
