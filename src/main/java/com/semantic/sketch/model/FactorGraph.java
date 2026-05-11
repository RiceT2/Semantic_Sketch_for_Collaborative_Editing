package com.semantic.sketch.model;

import com.semantic.sketch.crdt.Message;

import java.util.Map;
import java.util.List;

/**
 * Minimal factor graph abstraction for conflict resolution.
 *
 * <p>nodes = operation nodes; edges = pairwise conflict edges; factors = reusable potentials.</p>
 */
public record FactorGraph(List<Message> nodes,
                          List<ConflictEdge> edges,
                          List<ConstraintFactor> factors,
                          GraphMetadata metadata) {

    public record ConstraintFactor(String name, double weight) {
    }

    public record GraphMetadata(Map<String, Double> unaryPotentialByOpId,
                                Map<String, Double> roleWeightByActor,
                                long referenceTimestamp,
                                double decayLambda,
                                double constraintThreshold,
                                double semanticDistanceWeight,
                                double constraintSatisfactionWeight,
                                double structuralRiskWeight) {
    }
}
