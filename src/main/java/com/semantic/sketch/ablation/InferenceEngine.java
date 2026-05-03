package com.semantic.sketch.ablation;

import com.semantic.sketch.model.FactorGraph;

/**
 * Input: conflict subgraph (ops + conflict edges + factors).
 * Output: candidate solution set with aggregate score and explainable score breakdown.
 */
public interface InferenceEngine {
    MergeDecision infer(FactorGraph graph);
}
