package com.semantic.sketch.ablation;

public interface HumanArbiter {
    HumanArbiterDecision arbitrate(String branchId, MergeDecision candidate, double residualScore);
}
