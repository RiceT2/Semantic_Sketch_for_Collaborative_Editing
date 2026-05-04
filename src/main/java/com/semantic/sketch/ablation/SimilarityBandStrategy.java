package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.List;
import java.util.Map;

public class SimilarityBandStrategy {

    public enum Band { LOW, MEDIUM, HIGH }

    private final double lowThreshold;
    private final double highThreshold;

    public SimilarityBandStrategy(double lowThreshold, double highThreshold) {
        if (lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("low threshold must be less than high threshold");
        }
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
    }

    public Band band(Message left, Message right) {
        double similarity = similarity(left.getSemanticFingerprint(), right.getSemanticFingerprint());
        if (similarity < lowThreshold) {
            return Band.LOW;
        }
        if (similarity > highThreshold) {
            return Band.HIGH;
        }
        return Band.MEDIUM;
    }

    public MergeOutcome preferByTimestampAndRole(Message left, Message right, Map<String, Double> roleWeights) {
        double leftScore = timestamp(left) + roleWeights.getOrDefault(left.getActorId(), 1.0d);
        double rightScore = timestamp(right) + roleWeights.getOrDefault(right.getActorId(), 1.0d);
        if (leftScore >= rightScore) {
            return new MergeOutcome(MergeOutcome.DecisionType.KEEP_LEFT, List.of(left), "high-similarity-priority");
        }
        return new MergeOutcome(MergeOutcome.DecisionType.KEEP_RIGHT, List.of(right), "high-similarity-priority");
    }

    private double similarity(long left, long right) {
        long max = Math.max(Math.abs(left), Math.abs(right));
        if (max == 0L) {
            return 1.0d;
        }
        return 1.0d - ((double) Math.abs(left - right) / (double) max);
    }

    private long timestamp(Message message) {
        return message.getVectorClock().getOrDefault(message.getActorId(), 0L);
    }
}
