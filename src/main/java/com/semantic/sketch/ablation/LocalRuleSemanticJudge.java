package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

public class LocalRuleSemanticJudge implements SemanticJudge {
    @Override
    public boolean isConflict(Message left, Message right) {
        String l = normalize(left.getPayload());
        String r = normalize(right.getPayload());
        return l.equals(r) || l.contains("exclusive") && r.contains("exclusive");
    }

    private String normalize(String payload) {
        return payload == null ? "" : payload.trim().toLowerCase();
    }
}
