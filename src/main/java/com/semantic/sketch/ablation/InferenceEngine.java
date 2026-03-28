package com.semantic.sketch.ablation;

import com.semantic.sketch.model.FactorGraph;

public interface InferenceEngine {
    MergeDecision infer(FactorGraph graph);
}
