package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.Map;
import java.util.Objects;

/**
 * Collect phase: checks vector-clock concurrency and layered merge-policy routing.
 */
public class ConflictManager {

    private final ConflictPolicyOrchestrator policyOrchestrator;

    public ConflictManager() {
        this(new ConflictPolicyOrchestrator(
                new HardConflictStrategy(),
                new SharedDependencyStrategy(),
                new SimilarityBandStrategy(0.30d, 0.70d),
                new LocalRuleSemanticJudge()));
    }

    public ConflictManager(ConflictPolicyOrchestrator policyOrchestrator) {
        this.policyOrchestrator = Objects.requireNonNull(policyOrchestrator, "policyOrchestrator");
    }

    public boolean isConcurrent(Message a, Message b) {
        return !(happensBefore(a.getVectorClock(), b.getVectorClock())
                || happensBefore(b.getVectorClock(), a.getVectorClock()));
    }

    public MergeOutcome resolve(Message left, Message right, Map<String, Double> roleWeights) {
        return policyOrchestrator.resolve(left, right, roleWeights == null ? Map.of() : roleWeights);
    }

    private boolean happensBefore(Map<String, Long> a, Map<String, Long> b) {
        boolean strictlyLess = false;
        for (Map.Entry<String, Long> entry : a.entrySet()) {
            long bValue = b.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > bValue) {
                return false;
            }
            if (entry.getValue() < bValue) {
                strictlyLess = true;
            }
        }
        return strictlyLess;
    }
}
