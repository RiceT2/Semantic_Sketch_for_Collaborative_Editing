package com.semantic.sketch.experiment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Baseline #2: SHA-256 digest + full-history scan (compact but weak for semantic near-duplicates).
 */
public class Sha256FullHistoryStrategy implements ValidationStrategy {

    @Override
    public String name() {
        return "SHA256FullHistory";
    }

    @Override
    public ExperimentMetrics evaluate(List<ExperimentalOperation> operations) {
        List<DigestedOp> history = new ArrayList<>();
        MessageDigest digest = sha256();
        long comparisons = 0;
        long tp = 0, fp = 0, fn = 0;
        long start = System.nanoTime();

        for (ExperimentalOperation current : operations) {
            byte[] currentDigest = digest.digest(current.text().getBytes(StandardCharsets.UTF_8));
            boolean predictedConflict = false;
            boolean actualConflict = false;

            for (DigestedOp previous : history) {
                comparisons++;
                if (previous.cluster().equals(current.semanticCluster())) {
                    actualConflict = true;
                }
                if (MessageDigest.isEqual(previous.digest(), currentDigest)) {
                    predictedConflict = true;
                }
            }

            if (predictedConflict && actualConflict) tp++;
            else if (predictedConflict) fp++;
            else if (actualConflict) fn++;

            history.add(new DigestedOp(currentDigest, current.semanticCluster()));
        }

        long elapsed = System.nanoTime() - start;
        long estimatedBytes = operations.size() * (32L + 16L);
        return buildMetrics(name(), operations.size(), comparisons, elapsed, estimatedBytes, tp, fp, fn);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 should always exist", e);
        }
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

    private record DigestedOp(byte[] digest, String cluster) {
    }
}
