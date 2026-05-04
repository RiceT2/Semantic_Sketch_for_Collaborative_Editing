package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.ConflictEdge;
import com.semantic.sketch.model.FactorGraph;
import com.semantic.sketch.semantic.SimHash64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modeling phase: edge weight ψ is derived from fingerprint distance.
 */
public class FactorGraphBuilder {

    private static final double DEFAULT_DECAY_LAMBDA = 0.05;
    private static final double DEFAULT_CONSTRAINT_THRESHOLD = 0.35;
    private static final double DEFAULT_SEMANTIC_DISTANCE_WEIGHT = 0.7;
    private static final double DEFAULT_CONSTRAINT_SATISFACTION_WEIGHT = 0.3;

    private final Map<String, Double> roleWeightByActor;

    public FactorGraphBuilder() {
        this(Map.of());
    }

    public FactorGraphBuilder(Map<String, Double> roleWeightByActor) {
        this.roleWeightByActor = Map.copyOf(roleWeightByActor);
    }

    public FactorGraph build(List<Message> conflictSet) {
        long referenceTimestamp = conflictSet.stream()
                .mapToLong(this::extractLogicalTimestamp)
                .max()
                .orElse(0L);

        Map<String, Double> unaryPotentials = new HashMap<>();
        for (Message node : conflictSet) {
            double unaryPotential = firstClassPotential(node, referenceTimestamp);
            unaryPotentials.put(node.getOpId(), unaryPotential);
        }

        List<ConflictEdge> edges = new ArrayList<>();
        for (int i = 0; i < conflictSet.size(); i++) {
            for (int j = i + 1; j < conflictSet.size(); j++) {
                Message left = conflictSet.get(i);
                Message right = conflictSet.get(j);
                int distance = SimHash64.hammingDistance(left.getSemanticFingerprint(), right.getSemanticFingerprint());
                double semanticDistance = distance / 64.0;
                double pairwisePotential = secondClassPotential(semanticDistance);
                edges.add(new ConflictEdge(left, right, semanticDistance, pairwisePotential));
            }
        }

        List<FactorGraph.ConstraintFactor> factors = List.of(
                new FactorGraph.ConstraintFactor("time_decay_role_weight", 1.0),
                new FactorGraph.ConstraintFactor("semantic_distance_constraint_satisfaction", 1.0)
        );

        return new FactorGraph(
                conflictSet,
                edges,
                factors,
                new FactorGraph.GraphMetadata(
                        Map.copyOf(unaryPotentials),
                        roleWeightByActor,
                        referenceTimestamp,
                        DEFAULT_DECAY_LAMBDA,
                        DEFAULT_CONSTRAINT_THRESHOLD,
                        DEFAULT_SEMANTIC_DISTANCE_WEIGHT,
                        DEFAULT_CONSTRAINT_SATISFACTION_WEIGHT
                )
        );
    }

    private double firstClassPotential(Message node, long referenceTimestamp) {
        long nodeTs = extractLogicalTimestamp(node);
        long age = Math.max(0L, referenceTimestamp - nodeTs);
        double timeDecay = Math.exp(-DEFAULT_DECAY_LAMBDA * age);
        double roleWeight = roleWeightByActor.getOrDefault(node.getActorId(), 1.0);
        return timeDecay * roleWeight;
    }

    private double secondClassPotential(double semanticDistance) {
        double semanticSimilarity = 1.0 - semanticDistance;
        double constraintSatisfied = semanticDistance >= DEFAULT_CONSTRAINT_THRESHOLD ? 1.0 : 0.0;
        return (DEFAULT_SEMANTIC_DISTANCE_WEIGHT * semanticSimilarity)
                + (DEFAULT_CONSTRAINT_SATISFACTION_WEIGHT * constraintSatisfied);
    }

    private long extractLogicalTimestamp(Message node) {
        return node.getVectorClock().values().stream().mapToLong(Long::longValue).max().orElse(0L);
    }
}
