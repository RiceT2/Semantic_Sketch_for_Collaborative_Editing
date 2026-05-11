package com.semantic.sketch.ablation;

import java.util.Objects;

/**
 * Human arbitration result and rollback audit reservation.
 */
public record HumanArbiterDecision(DecisionType decisionType,
                                   String triggerReason,
                                   String decidedBy,
                                   String rollbackScope) {
    public HumanArbiterDecision {
        Objects.requireNonNull(decisionType, "decisionType");
        triggerReason = normalize(triggerReason, "R below threshold");
        decidedBy = normalize(decidedBy, "human-arbiter");
        rollbackScope = normalize(rollbackScope, "current-conflict-set");
    }

    public static HumanArbiterDecision acceptSystemPlan(String triggerReason, String decidedBy) {
        return new HumanArbiterDecision(DecisionType.ACCEPT_SYSTEM_PLAN, triggerReason, decidedBy, "none");
    }

    public static HumanArbiterDecision rollbackRedo(String triggerReason, String decidedBy, String rollbackScope) {
        return new HumanArbiterDecision(DecisionType.ROLLBACK_REDO, triggerReason, decidedBy, rollbackScope);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public enum DecisionType {
        ACCEPT_SYSTEM_PLAN,
        ROLLBACK_REDO
    }
}
