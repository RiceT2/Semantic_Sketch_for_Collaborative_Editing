package com.semantic.sketch.experiment;

import java.util.List;

public final class ExperimentCli {
    private ExperimentCli() {
    }

    public static void main(String[] args) {
        ExperimentRunner runner = new ExperimentRunner(new WorkloadGenerator());
        List<ExperimentMetrics> metrics = runner.runDefaultSuite();
        System.out.println(runner.toMarkdownReport(metrics));
    }
}
