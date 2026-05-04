package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SharedDependencyStrategy {

    public MergeOutcome evaluate(Message left, Message right) {
        Set<String> leftDeps = dependencies(left);
        Set<String> rightDeps = dependencies(right);

        boolean hasCommon = leftDeps.stream().anyMatch(rightDeps::contains);
        if (!hasCommon) {
            return new MergeOutcome(MergeOutcome.DecisionType.APPLY_BOTH, List.of(left, right), "no-shared-dependency");
        }
        return null;
    }

    private Set<String> dependencies(Message message) {
        return message.getVectorClock().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
