package com.semantic.sketch.experiment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Baseline #1: full-history keyword overlap scan (representative of expensive global checks).
 */
public class FullHistoryKeywordOverlapStrategy implements ValidationStrategy {

    private final double jaccardThreshold;

    public FullHistoryKeywordOverlapStrategy(double jaccardThreshold) {
        this.jaccardThreshold = jaccardThreshold;
    }

    @Override
    public String name() {
        return "FullHistoryKeywordOverlap";
    }

    @Override
    public ExperimentMetrics evaluate(List<ExperimentalOperation> operations) {
        List<TokenizedOp> history = new ArrayList<>();
        long comparisons = 0;
        long tp = 0, fp = 0, fn = 0;
        long start = System.nanoTime();

        for (ExperimentalOperation current : operations) {
            TokenizedOp tokenizedCurrent = new TokenizedOp(tokens(current.text()), current.semanticCluster(), current.text().length());

            boolean predictedConflict = false;
            boolean actualConflict = false;
            for (TokenizedOp previous : history) {
                comparisons++;
                if (previous.cluster().equals(tokenizedCurrent.cluster())) {
                    actualConflict = true;
                }
                double jaccard = jaccard(previous.tokens(), tokenizedCurrent.tokens());
                if (jaccard >= jaccardThreshold) {
                    predictedConflict = true;
                }
            }

            if (predictedConflict && actualConflict) tp++;
            else if (predictedConflict) fp++;
            else if (actualConflict) fn++;

            history.add(tokenizedCurrent);
        }

        long elapsed = System.nanoTime() - start;
        long estimatedBytes = history.stream().mapToLong(TokenizedOp::bytes).sum();
        return buildMetrics(name(), operations.size(), comparisons, elapsed, estimatedBytes, tp, fp, fn);
    }

    private Set<String> tokens(String text) {
        Set<String> tokenSet = new HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (!token.isBlank()) {
                tokenSet.add(token);
            }
        }
        return tokenSet;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return intersection.size() / (double) union.size();
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

    private record TokenizedOp(Set<String> tokens, String cluster, int textLength) {
        long bytes() {
            return 24L + textLength * 2L + tokens.size() * 20L;
        }
    }
}
