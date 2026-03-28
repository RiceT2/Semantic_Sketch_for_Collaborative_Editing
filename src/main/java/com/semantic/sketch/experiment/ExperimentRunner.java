package com.semantic.sketch.experiment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ExperimentRunner {

    private final WorkloadGenerator workloadGenerator;

    public ExperimentRunner(WorkloadGenerator workloadGenerator) {
        this.workloadGenerator = workloadGenerator;
    }

    public List<ExperimentMetrics> runDefaultSuite() {
        List<ExperimentalOperation> workload = workloadGenerator.generate(2_000, 20260328L);

        List<ValidationStrategy> strategies = List.of(
                new SemanticFingerprintWindowStrategy(96, 12),
                new FullHistoryKeywordOverlapStrategy(0.55),
                new Sha256FullHistoryStrategy()
        );

        List<ExperimentMetrics> metrics = new ArrayList<>();
        for (ValidationStrategy strategy : strategies) {
            metrics.add(strategy.evaluate(workload));
        }
        metrics.sort(Comparator.comparing(ExperimentMetrics::algorithm));
        return metrics;
    }

    public String toMarkdownReport(List<ExperimentMetrics> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Algorithm | Ops | Comparisons | Throughput(op/s) | Estimated Memory(bytes) | Precision | Recall | F1 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ExperimentMetrics m : metrics) {
            sb.append("| ").append(m.algorithm()).append(" | ")
                    .append(m.operations()).append(" | ")
                    .append(m.comparisons()).append(" | ")
                    .append(String.format("%.2f", m.throughputOpsPerSecond())).append(" | ")
                    .append(m.estimatedBytes()).append(" | ")
                    .append(String.format("%.4f", m.precision())).append(" | ")
                    .append(String.format("%.4f", m.recall())).append(" | ")
                    .append(String.format("%.4f", m.f1())).append(" |\n");
        }
        return sb.toString();
    }
}
