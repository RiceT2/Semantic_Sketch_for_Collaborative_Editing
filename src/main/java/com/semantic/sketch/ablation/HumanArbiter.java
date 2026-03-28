package com.semantic.sketch.ablation;

public interface HumanArbiter {
    boolean accept(String branchId, MergeDecision candidate);
}
