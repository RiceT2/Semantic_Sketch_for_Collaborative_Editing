package com.semantic.sketch.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentRunnerTest {

    @Test
    void defaultSuite_containsAllAlgorithms_andShowsWindowAdvantage() {
        ExperimentRunner runner = new ExperimentRunner(new WorkloadGenerator());
        List<ExperimentMetrics> metrics = runner.runDefaultSuite();

        assertEquals(3, metrics.size());

        ExperimentMetrics semantic = byName(metrics, "SemanticFingerprintWindow");
        ExperimentMetrics fullHistory = byName(metrics, "FullHistoryKeywordOverlap");
        ExperimentMetrics sha = byName(metrics, "SHA256FullHistory");

        assertTrue(semantic.comparisons() < fullHistory.comparisons(),
                "windowed strategy should use fewer comparisons than full-history scan");
        assertTrue(semantic.estimatedBytes() < fullHistory.estimatedBytes(),
                "semantic fingerprint should use less memory than full-text storage");
        assertTrue(semantic.recall() > sha.recall(),
                "semantic strategy should detect more near-semantic conflicts than exact SHA digest");
    }

    @Test
    void markdownReport_containsHeaderAndRows() {
        ExperimentRunner runner = new ExperimentRunner(new WorkloadGenerator());
        String report = runner.toMarkdownReport(runner.runDefaultSuite());

        assertTrue(report.contains("| Algorithm | Ops | Comparisons"));
        assertTrue(report.contains("SemanticFingerprintWindow"));
    }

    private ExperimentMetrics byName(List<ExperimentMetrics> metrics, String name) {
        return metrics.stream().filter(m -> m.algorithm().equals(name)).findFirst().orElseThrow();
    }
}
