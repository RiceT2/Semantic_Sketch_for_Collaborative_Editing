package com.semantic.sketch.storage;

import com.semantic.sketch.ablation.MergeDecision;

import java.util.Optional;

public interface ShadowStore {
    void save(String branchId, MergeDecision decision);

    Optional<MergeDecision> get(String branchId);

    void clear(String branchId);
}
