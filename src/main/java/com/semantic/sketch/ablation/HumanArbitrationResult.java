package com.semantic.sketch.ablation;

import java.util.Objects;

/**
 * Human arbitration outcome with rollback audit fields reserved for recovery flows.
 */
public record HumanArbitrationResult(Action action,
                                     String reason,
                                     String decidedBy,
                                     String rollbackScope) {
    public HumanArbitrationResult {
        Objects.requireNonNull(action, "action");
        reason = reason == null ? "" : reason;
        decidedBy = decidedBy == null || decidedBy.isBlank() ? "unknown" : decidedBy;
        rollbackScope = rollbackScope == null ? "" : rollbackScope;
    }

    public static HumanArbitrationResult accept(String decidedBy, String reason) {
        return new HumanArbitrationResult(Action.ACCEPT_SYSTEM_PLAN, reason, decidedBy, "");
    }

    public static HumanArbitrationResult rollback(String decidedBy, String reason, String rollbackScope) {
        return new HumanArbitrationResult(Action.ROLLBACK_AND_REDO, reason, decidedBy, rollbackScope);
    }

    public boolean acceptsSystemPlan() {
        return action == Action.ACCEPT_SYSTEM_PLAN;
    }

    public enum Action {
        ACCEPT_SYSTEM_PLAN,
        ROLLBACK_AND_REDO
    }
}
