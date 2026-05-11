package com.semantic.sketch.ablation;

public interface HumanArbiter {
    HumanArbitrationResult arbitrate(String branchId, MergeDecision candidate);
}
