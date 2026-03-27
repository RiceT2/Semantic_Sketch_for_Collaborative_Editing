package com.semantic.sketch.storage;

import com.semantic.sketch.ablation.MergeDecision;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local substitute for Redis MVCC-backed shadow table.
 */
public class InMemoryShadowStore implements ShadowStore {
    private final Map<String, MergeDecision> data = new ConcurrentHashMap<>();

    @Override
    public void save(String branchId, MergeDecision decision) {
        data.put(branchId, decision);
    }

    @Override
    public Optional<MergeDecision> get(String branchId) {
        return Optional.ofNullable(data.get(branchId));
    }

    @Override
    public void clear(String branchId) {
        data.remove(branchId);
    }
}
