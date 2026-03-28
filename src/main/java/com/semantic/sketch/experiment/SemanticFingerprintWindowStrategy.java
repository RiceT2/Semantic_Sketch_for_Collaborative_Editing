package com.semantic.sketch.experiment;

import com.semantic.sketch.semantic.LightweightSemanticFingerprintService;
import com.semantic.sketch.semantic.SimHash64;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Proposed strategy: SimHash fingerprint + sliding window local validation.
 */
public class SemanticFingerprintWindowStrategy implements ValidationStrategy {

    private final int windowSize;
    private final int maxHammingDistance;

    public SemanticFingerprintWindowStrategy(int windowSize, int maxHammingDistance) {
        this.windowSize = windowSize;
        this.maxHammingDistance = maxHammingDistance;
    }

    @Override
    public String name() {
        return "SemanticFingerprintWindow";
    }

    @Override
    public ExperimentMetrics evaluate(List<ExperimentalOperation> operations) {
        LightweightSemanticFingerprintService service = new LightweightSemanticFingerprintService();
        Deque<StampedOp> window = new ArrayDeque<>();

        long comparisons = 0;
        long tp = 0, fp = 0, fn = 0;
        long start = System.nanoTime();

        for (ExperimentalOperation current : operations) {
            long fpCurrent = service.fingerprint(current.text());
            boolean predictedConflict = false;
            boolean actualConflict = false;

            for (StampedOp previous : window) {
                comparisons++;
                if (previous.cluster().equals(current.semanticCluster())) {
                    actualConflict = true;
                }
                int distance = SimHash64.hammingDistance(previous.fingerprint(), fpCurrent);
                if (distance <= maxHammingDistance) {
                    predictedConflict = true;
                }
            }

            updateConfusion(predictedConflict, actualConflict);
            if (predictedConflict && actualConflict) tp++;
            else if (predictedConflict) fp++;
            else if (actualConflict) fn++;

            window.addLast(new StampedOp(fpCurrent, current.semanticCluster()));
            while (window.size() > windowSize) {
                window.removeFirst();
            }
        }

        long elapsed = System.nanoTime() - start;
        long estimatedBytes = operations.size() * Long.BYTES + (long) windowSize * (Long.BYTES + 16);
        return buildMetrics(name(), operations.size(), comparisons, elapsed, estimatedBytes, tp, fp, fn);
    }

    private void updateConfusion(boolean predictedConflict, boolean actualConflict) {
        // no-op helper placeholder for future instrumentation hooks.
    }

    private ExperimentMetrics buildMetrics(String name,
                                           int operations,
                                           long comparisons,
                                           long elapsed,
                                           long estimatedBytes,
                                           long tp,
                                           long fp,
                                           long fn) {
        double precision = tp + fp == 0 ? 1.0 : tp / (double) (tp + fp);
        double recall = tp + fn == 0 ? 1.0 : tp / (double) (tp + fn);
        double f1 = precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return new ExperimentMetrics(name, operations, comparisons, elapsed, estimatedBytes, precision, recall, f1);
    }

    private record StampedOp(long fingerprint, String cluster) {
    }
}
