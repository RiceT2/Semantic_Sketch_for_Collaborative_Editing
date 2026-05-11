package com.semantic.sketch.model;

import com.semantic.sketch.crdt.Message;

import java.util.Map;

/**
 * Conflict edge between two operation nodes.
 *
 * @param left one endpoint op node
 * @param right another endpoint op node
 * @param semanticDistance normalized semantic distance in [0, 1]
 * @param structuralRisk normalized CRDT structural compatibility risk in [0, 1]
 * @param conflictWeight pairwise conflict strength (larger = stronger conflict)
 * @param metadata explainable pairwise semantic and structural features
 */
public record ConflictEdge(Message left,
                           Message right,
                           double semanticDistance,
                           double structuralRisk,
                           double conflictWeight,
                           Map<String, Object> metadata) {
    public ConflictEdge(Message left, Message right, double semanticDistance, double conflictWeight) {
        this(left, right, semanticDistance, 0.0d, conflictWeight, Map.of("semanticDistance", semanticDistance));
    }

    public ConflictEdge {
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
