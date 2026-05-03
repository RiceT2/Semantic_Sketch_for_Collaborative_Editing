package com.semantic.sketch;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.storage.ShadowStore;

import java.util.Objects;
import java.util.Optional;

public class ShadowStoreHistoryRecoveryService implements HistoryRecoveryService {
    private final ShadowStore shadowStore;

    public ShadowStoreHistoryRecoveryService(ShadowStore shadowStore) {
        this.shadowStore = Objects.requireNonNull(shadowStore, "shadowStore");
    }

    @Override
    public Optional<MergeDecision> recover(String branchId) {
        return shadowStore.get(branchId);
    }
}
