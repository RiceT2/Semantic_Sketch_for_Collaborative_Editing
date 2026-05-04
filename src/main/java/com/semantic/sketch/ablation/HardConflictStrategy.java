package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.List;

public class HardConflictStrategy {

    public MergeOutcome evaluate(Message left, Message right) {
        if (isStructuralConflict(left, right) || isMutuallyExclusive(left, right)) {
            return new MergeOutcome(MergeOutcome.DecisionType.CONFLICT, List.of(), "hard-conflict");
        }
        return null;
    }

    private boolean isStructuralConflict(Message left, Message right) {
        String l = left.getPayload();
        String r = right.getPayload();
        return l.startsWith("delete:") && r.startsWith("update:")
                || l.startsWith("update:") && r.startsWith("delete:");
    }

    private boolean isMutuallyExclusive(Message left, Message right) {
        String l = left.getPayload();
        String r = right.getPayload();
        return l.startsWith("lock:") && r.startsWith("lock:") && !l.equals(r);
    }
}
