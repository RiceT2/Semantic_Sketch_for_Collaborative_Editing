package com.semantic.sketch.storage;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.model.ShadowMetadata;

import java.util.Optional;

public interface ShadowStore {
    void save(String branchId, MergeDecision decision);

    void save(String branchId, MergeDecision decision, ShadowMetadata metadata);

    Optional<MergeDecision> get(String branchId);

    Optional<ShadowMetadata> getMetadata(String branchId);

    void clear(String branchId);
}
