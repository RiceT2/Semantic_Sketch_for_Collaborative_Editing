package com.semantic.sketch;

import com.semantic.sketch.ablation.MergeDecision;

import java.util.Optional;

/**
 * Abstraction for merge history lookup/restore, later wired to storage layer.
 */
public interface HistoryRecoveryService {
    Optional<MergeDecision> recover(String branchId);
}
