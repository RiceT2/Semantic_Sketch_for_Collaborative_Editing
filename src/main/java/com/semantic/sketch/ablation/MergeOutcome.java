package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.List;

public record MergeOutcome(DecisionType decisionType, List<Message> survivors, String reason) {

    public enum DecisionType {
        APPLY_BOTH,
        KEEP_LEFT,
        KEEP_RIGHT,
        CONFLICT
    }
}
