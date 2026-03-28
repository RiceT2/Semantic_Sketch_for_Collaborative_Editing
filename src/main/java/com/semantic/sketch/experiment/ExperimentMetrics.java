package com.semantic.sketch.experiment;

public record ExperimentMetrics(
        String algorithm,
        int operations,
        long comparisons,
        long totalNanos,
        long estimatedBytes,
        double precision,
        double recall,
        double f1
) {
    public double throughputOpsPerSecond() {
        if (totalNanos == 0) {
            return 0d;
        }
        return operations / (totalNanos / 1_000_000_000d);
    }
}
