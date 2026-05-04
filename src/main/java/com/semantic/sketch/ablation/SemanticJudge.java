package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

public interface SemanticJudge {
    boolean isConflict(Message left, Message right);
}
