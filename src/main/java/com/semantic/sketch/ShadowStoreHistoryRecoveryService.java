package com.semantic.sketch;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.storage.ShadowStore;

import java.util.Objects;
import java.util.Optional;

public class ShadowStoreHistoryRecoveryService implements HistoryRecoveryService {
    private final ShadowStore shadowStore;
    private RecoveryAudit lastRecoveryAudit;

    public ShadowStoreHistoryRecoveryService(ShadowStore shadowStore) {
        this.shadowStore = Objects.requireNonNull(shadowStore, "shadowStore");
    }

    @Override
    public Optional<MergeDecision> recover(String branchId) {
        return shadowStore.get(branchId);
    }

    @Override
    public Optional<MergeDecision> recover(String branchId, RecoveryAudit audit) {
        this.lastRecoveryAudit = Objects.requireNonNull(audit, "audit");
        return recover(branchId);
    }

    public Optional<RecoveryAudit> lastRecoveryAudit() {
        return Optional.ofNullable(lastRecoveryAudit);
    }
}
