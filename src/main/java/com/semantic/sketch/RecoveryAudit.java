package com.semantic.sketch;

import java.time.Instant;
import java.util.Objects;

/**
 * Audit reservation passed to log/history recovery when a human asks to redo a merge.
 */
public record RecoveryAudit(String triggerReason,
                            String decidedBy,
                            String rollbackScope,
                            Instant decidedAt) {
    public RecoveryAudit {
        triggerReason = normalize(triggerReason, "R below threshold");
        decidedBy = normalize(decidedBy, "human-arbiter");
        rollbackScope = normalize(rollbackScope, "current-conflict-set");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public static RecoveryAudit of(String triggerReason, String decidedBy, String rollbackScope) {
        return new RecoveryAudit(triggerReason, decidedBy, rollbackScope, Instant.now());
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
