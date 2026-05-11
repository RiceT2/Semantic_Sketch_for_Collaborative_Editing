package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes the normalized intent residual degree R for a merge decision.
 *
 * <p>R is the retained weighted-intent ratio in [0,1]. A higher value means the
 * optimal plan preserves more original intent. A lower value means more weighted
 * intent was lost and should trigger human arbitration when {@code R < tau}.</p>
 */
public class IntentResidualCalculator {

    public double calculate(MergeDecision decision) {
        return calculate(decision, Map.of(), Map.of(), Map.of());
    }

    public double calculate(MergeDecision decision,
                            Map<String, Double> semanticWeightByOpId,
                            Map<String, Double> informationEntropyWeightByOpId,
                            Map<String, Double> roleWeightByActor) {
        Set<String> acceptedOpIds = new HashSet<>();
        for (Message accepted : decision.acceptedOps()) {
            acceptedOpIds.add(accepted.getOpId());
        }

        double retainedWeight = 0.0d;
        double totalWeight = 0.0d;
        for (Message operation : decision.acceptedOps()) {
            double weight = operationWeight(operation, semanticWeightByOpId, informationEntropyWeightByOpId, roleWeightByActor);
            retainedWeight += weight;
            totalWeight += weight;
        }
        for (Message operation : decision.rejectedOps()) {
            if (acceptedOpIds.contains(operation.getOpId())) {
                continue;
            }
            totalWeight += operationWeight(operation, semanticWeightByOpId, informationEntropyWeightByOpId, roleWeightByActor);
        }

        if (totalWeight == 0.0d) {
            return 1.0d;
        }
        return clamp(retainedWeight / totalWeight);
    }

    private double operationWeight(Message operation,
                                   Map<String, Double> semanticWeightByOpId,
                                   Map<String, Double> informationEntropyWeightByOpId,
                                   Map<String, Double> roleWeightByActor) {
        double semanticWeight = positiveOrDefault(semanticWeightByOpId.get(operation.getOpId()), 1.0d);
        double entropyWeight = positiveOrDefault(
                informationEntropyWeightByOpId.get(operation.getOpId()),
                normalizedEntropy(operation.getPayload())
        );
        double roleWeight = positiveOrDefault(roleWeightByActor.get(operation.getActorId()), 1.0d);
        return semanticWeight * entropyWeight * roleWeight;
    }

    private double normalizedEntropy(String payload) {
        if (payload == null || payload.isBlank()) {
            return 0.1d;
        }
        String normalized = payload.toLowerCase(Locale.ROOT).trim();
        Map<Integer, Long> frequencies = normalized.codePoints()
                .boxed()
                .collect(java.util.stream.Collectors.groupingBy(codePoint -> codePoint, java.util.stream.Collectors.counting()));
        double length = normalized.codePointCount(0, normalized.length());
        double entropy = 0.0d;
        for (long frequency : frequencies.values()) {
            double probability = frequency / length;
            entropy -= probability * (Math.log(probability) / Math.log(2.0d));
        }
        double maxEntropy = Math.log(Math.max(2, frequencies.size())) / Math.log(2.0d);
        return clamp(maxEntropy == 0.0d ? 0.1d : entropy / maxEntropy);
    }

    private double positiveOrDefault(Double value, double fallback) {
        if (value == null || value <= 0.0d || Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return value;
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
