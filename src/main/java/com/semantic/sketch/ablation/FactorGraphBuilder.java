package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.ConflictEdge;
import com.semantic.sketch.model.FactorGraph;
import com.semantic.sketch.semantic.SimHash64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Modeling phase: edge weight ψ is derived from fingerprint distance and CRDT structural risk.
 */
public class FactorGraphBuilder {

    private static final double DEFAULT_DECAY_LAMBDA = 0.05;
    private static final double DEFAULT_CONSTRAINT_THRESHOLD = 0.35;
    private static final double DEFAULT_SEMANTIC_DISTANCE_WEIGHT = 0.45;
    private static final double DEFAULT_CONSTRAINT_SATISFACTION_WEIGHT = 0.20;
    private static final double DEFAULT_STRUCTURAL_RISK_WEIGHT = 0.35;

    private final Map<String, Double> roleWeightByActor;

    public FactorGraphBuilder() {
        this(Map.of());
    }

    public FactorGraphBuilder(Map<String, Double> roleWeightByActor) {
        this.roleWeightByActor = Map.copyOf(roleWeightByActor);
    }

    public FactorGraph build(List<Message> conflictSet) {
        return buildInternal(conflictSet, conflictSet.stream()
                .map(message -> message.toEnvelope(null, CrdtOperationType.REPLACE))
                .toList());
    }

    public FactorGraph buildFromEnvelopes(List<CrdtOperationEnvelope> conflictSet) {
        Objects.requireNonNull(conflictSet, "conflictSet");
        return buildInternal(
                conflictSet.stream().map(CrdtOperationEnvelope::toMessage).toList(),
                List.copyOf(conflictSet)
        );
    }

    private FactorGraph buildInternal(List<Message> nodes, List<CrdtOperationEnvelope> envelopes) {
        long referenceTimestamp = nodes.stream()
                .mapToLong(this::extractLogicalTimestamp)
                .max()
                .orElse(0L);

        Map<String, Double> unaryPotentials = new HashMap<>();
        for (Message node : nodes) {
            double unaryPotential = firstClassPotential(node, referenceTimestamp);
            unaryPotentials.put(node.getOpId(), unaryPotential);
        }

        List<ConflictEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Message left = nodes.get(i);
                Message right = nodes.get(j);
                CrdtOperationEnvelope leftEnvelope = envelopes.get(i);
                CrdtOperationEnvelope rightEnvelope = envelopes.get(j);
                int distance = SimHash64.hammingDistance(left.getSemanticFingerprint(), right.getSemanticFingerprint());
                double semanticDistance = distance / 64.0;
                StructuralCompatibility structuralCompatibility = structuralCompatibility(leftEnvelope, rightEnvelope);
                double pairwisePotential = secondClassPotential(semanticDistance, structuralCompatibility.structuralRisk());
                edges.add(new ConflictEdge(
                        left,
                        right,
                        semanticDistance,
                        structuralCompatibility.structuralRisk(),
                        pairwisePotential,
                        structuralCompatibility.metadata(semanticDistance)
                ));
            }
        }

        List<FactorGraph.ConstraintFactor> factors = List.of(
                new FactorGraph.ConstraintFactor("time_decay_role_weight", 1.0),
                new FactorGraph.ConstraintFactor("semantic_distance_constraint_satisfaction", 1.0),
                new FactorGraph.ConstraintFactor("crdt_structural_compatibility", 1.0)
        );

        return new FactorGraph(
                nodes,
                edges,
                factors,
                new FactorGraph.GraphMetadata(
                        Map.copyOf(unaryPotentials),
                        roleWeightByActor,
                        referenceTimestamp,
                        DEFAULT_DECAY_LAMBDA,
                        DEFAULT_CONSTRAINT_THRESHOLD,
                        DEFAULT_SEMANTIC_DISTANCE_WEIGHT,
                        DEFAULT_CONSTRAINT_SATISFACTION_WEIGHT,
                        DEFAULT_STRUCTURAL_RISK_WEIGHT
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

    private double secondClassPotential(double semanticDistance, double structuralRisk) {
        double semanticSimilarity = 1.0 - semanticDistance;
        double constraintSatisfied = semanticDistance >= DEFAULT_CONSTRAINT_THRESHOLD ? 1.0 : 0.0;
        return (DEFAULT_SEMANTIC_DISTANCE_WEIGHT * semanticSimilarity)
                + (DEFAULT_CONSTRAINT_SATISFACTION_WEIGHT * constraintSatisfied)
                + (DEFAULT_STRUCTURAL_RISK_WEIGHT * structuralRisk);
    }

    private long extractLogicalTimestamp(Message node) {
        return node.getVectorClock().values().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    private StructuralCompatibility structuralCompatibility(CrdtOperationEnvelope left, CrdtOperationEnvelope right) {
        boolean sameTargetPath = hasText(left.getTargetPath())
                && left.getTargetPath().equals(right.getTargetPath());
        boolean rangeOverlaps = sameTargetPath && rangesOverlap(left, right);
        boolean deleteCoversInsert = sameTargetPath && deleteCoversInsert(left, right);
        boolean formatOnDeletedRange = sameTargetPath && formatOnDeletedRange(left, right);
        double matrixRisk = compatibilityRisk(left.getOperationType(), right.getOperationType());

        double structuralRisk = matrixRisk;
        if (sameTargetPath) {
            structuralRisk += 0.10;
        } else if (hasText(left.getTargetPath()) && hasText(right.getTargetPath())) {
            structuralRisk -= 0.15;
        }
        if (rangeOverlaps) {
            structuralRisk += 0.15;
        }
        if (deleteCoversInsert) {
            structuralRisk += 0.20;
        }
        if (formatOnDeletedRange) {
            structuralRisk += 0.20;
        }
        structuralRisk = clamp(structuralRisk, 0.0, 1.0);

        return new StructuralCompatibility(
                sameTargetPath,
                rangeOverlaps,
                deleteCoversInsert,
                formatOnDeletedRange,
                matrixRisk,
                structuralRisk,
                left.getOperationType().toJsonValue() + "+" + right.getOperationType().toJsonValue()
        );
    }

    private double compatibilityRisk(CrdtOperationType left, CrdtOperationType right) {
        if (isPair(left, right, CrdtOperationType.INSERT, CrdtOperationType.INSERT)) {
            return 0.35;
        }
        if (isPair(left, right, CrdtOperationType.INSERT, CrdtOperationType.DELETE)) {
            return 0.80;
        }
        if (isPair(left, right, CrdtOperationType.REPLACE, CrdtOperationType.REPLACE)) {
            return 0.85;
        }
        if (isPair(left, right, CrdtOperationType.FORMAT, CrdtOperationType.INSERT)) {
            return 0.20;
        }
        if (isPair(left, right, CrdtOperationType.MOVE, CrdtOperationType.DELETE)) {
            return 0.80;
        }
        return 0.50;
    }

    private boolean isPair(CrdtOperationType left,
                           CrdtOperationType right,
                           CrdtOperationType expectedLeft,
                           CrdtOperationType expectedRight) {
        return (left == expectedLeft && right == expectedRight)
                || (left == expectedRight && right == expectedLeft);
    }

    private boolean rangesOverlap(CrdtOperationEnvelope left, CrdtOperationEnvelope right) {
        Range leftRange = Range.from(left);
        Range rightRange = Range.from(right);
        return leftRange.known() && rightRange.known() && leftRange.overlaps(rightRange);
    }

    private boolean deleteCoversInsert(CrdtOperationEnvelope left, CrdtOperationEnvelope right) {
        return deleteCoversInsertInOrder(left, right) || deleteCoversInsertInOrder(right, left);
    }

    private boolean deleteCoversInsertInOrder(CrdtOperationEnvelope delete, CrdtOperationEnvelope insert) {
        if (delete.getOperationType() != CrdtOperationType.DELETE || insert.getOperationType() != CrdtOperationType.INSERT) {
            return false;
        }
        Range deleteRange = Range.from(delete);
        Range insertRange = Range.from(insert);
        return deleteRange.known() && insertRange.known() && deleteRange.contains(insertRange.start());
    }

    private boolean formatOnDeletedRange(CrdtOperationEnvelope left, CrdtOperationEnvelope right) {
        return formatOnDeletedRangeInOrder(left, right) || formatOnDeletedRangeInOrder(right, left);
    }

    private boolean formatOnDeletedRangeInOrder(CrdtOperationEnvelope format, CrdtOperationEnvelope delete) {
        if (format.getOperationType() != CrdtOperationType.FORMAT || delete.getOperationType() != CrdtOperationType.DELETE) {
            return false;
        }
        return rangesOverlap(format, delete);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record StructuralCompatibility(boolean sameTargetPath,
                                           boolean rangeOverlaps,
                                           boolean deleteCoversInsert,
                                           boolean formatOnDeletedRange,
                                           double compatibilityMatrixRisk,
                                           double structuralRisk,
                                           String operationPair) {
        Map<String, Object> metadata(double semanticDistance) {
            return Map.of(
                    "operationPair", operationPair,
                    "sameTargetPath", sameTargetPath,
                    "rangeOverlaps", rangeOverlaps,
                    "deleteCoversInsert", deleteCoversInsert,
                    "formatOnDeletedRange", formatOnDeletedRange,
                    "compatibilityMatrixRisk", compatibilityMatrixRisk,
                    "structuralRisk", structuralRisk,
                    "semanticDistance", semanticDistance
            );
        }
    }

    private record Range(int start, int end, boolean known) {
        static Range from(CrdtOperationEnvelope envelope) {
            Integer start = envelope.getFromIndex();
            Integer end = envelope.getToIndex();
            if (start == null && end == null) {
                return new Range(0, 0, false);
            }
            int resolvedStart = start == null ? end : start;
            int resolvedEnd = end == null ? resolvedStart : end;
            int normalizedStart = Math.min(resolvedStart, resolvedEnd);
            int normalizedEnd = Math.max(resolvedStart, resolvedEnd);
            return new Range(normalizedStart, normalizedEnd, true);
        }

        boolean overlaps(Range other) {
            return start <= other.end && other.start <= end;
        }

        boolean contains(int position) {
            return start <= position && position <= end;
        }
    }
}
