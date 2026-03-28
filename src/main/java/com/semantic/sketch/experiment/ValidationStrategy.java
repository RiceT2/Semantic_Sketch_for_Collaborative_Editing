package com.semantic.sketch.experiment;

import java.util.List;

public interface ValidationStrategy {
    String name();

    ExperimentMetrics evaluate(List<ExperimentalOperation> operations);
}
