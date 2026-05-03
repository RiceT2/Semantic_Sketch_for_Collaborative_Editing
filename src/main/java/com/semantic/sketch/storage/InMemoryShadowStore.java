package com.semantic.sketch.storage;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.model.ShadowMetadata;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local substitute for Redis MVCC-backed shadow table.
 */
public class InMemoryShadowStore implements ShadowStore {
    private final Map<String, MergeDecision> data = new ConcurrentHashMap<>();
    private final Map<String, ShadowMetadata> metadata = new ConcurrentHashMap<>();

    @Override
    public void save(String branchId, MergeDecision decision) {
        data.put(branchId, decision);
    }

    @Override
    public void save(String branchId, MergeDecision decision, ShadowMetadata metadata) {
        data.put(branchId, decision);
        this.metadata.put(branchId, metadata);
    }

    @Override
    public Optional<MergeDecision> get(String branchId) {
        return Optional.ofNullable(data.get(branchId));
    }

    @Override
    public Optional<ShadowMetadata> getMetadata(String branchId) {
        return Optional.ofNullable(metadata.get(branchId));
    }

    @Override
    public void clear(String branchId) {
        data.remove(branchId);
        metadata.remove(branchId);
    }
}
