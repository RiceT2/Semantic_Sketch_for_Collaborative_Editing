package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.Map;
import java.util.Objects;

public class ConflictPolicyOrchestrator {
    private final HardConflictStrategy hardConflictStrategy;
    private final SharedDependencyStrategy sharedDependencyStrategy;
    private final SimilarityBandStrategy similarityBandStrategy;
    private final SemanticJudge semanticJudge;

    public ConflictPolicyOrchestrator(HardConflictStrategy hardConflictStrategy,
                                      SharedDependencyStrategy sharedDependencyStrategy,
                                      SimilarityBandStrategy similarityBandStrategy,
                                      SemanticJudge semanticJudge) {
        this.hardConflictStrategy = Objects.requireNonNull(hardConflictStrategy, "hardConflictStrategy");
        this.sharedDependencyStrategy = Objects.requireNonNull(sharedDependencyStrategy, "sharedDependencyStrategy");
        this.similarityBandStrategy = Objects.requireNonNull(similarityBandStrategy, "similarityBandStrategy");
        this.semanticJudge = Objects.requireNonNull(semanticJudge, "semanticJudge");
    }

    public MergeOutcome resolve(Message left, Message right, Map<String, Double> roleWeights) {
        MergeOutcome hardDecision = hardConflictStrategy.evaluate(left, right);
        if (hardDecision != null) {
            return hardDecision;
        }

        MergeOutcome dependencyDecision = sharedDependencyStrategy.evaluate(left, right);
        if (dependencyDecision != null) {
            return dependencyDecision;
        }

        SimilarityBandStrategy.Band band = similarityBandStrategy.band(left, right);
        if (band == SimilarityBandStrategy.Band.LOW) {
            return new MergeOutcome(MergeOutcome.DecisionType.APPLY_BOTH, java.util.List.of(left, right), "low-similarity");
        }
        if (band == SimilarityBandStrategy.Band.HIGH) {
            return similarityBandStrategy.preferByTimestampAndRole(left, right, roleWeights);
        }

        boolean semanticConflict = semanticJudge.isConflict(left, right);
        if (semanticConflict) {
            return new MergeOutcome(MergeOutcome.DecisionType.CONFLICT, java.util.List.of(), "semantic-judge-conflict");
        }
        return new MergeOutcome(MergeOutcome.DecisionType.APPLY_BOTH, java.util.List.of(left, right), "semantic-judge-compatible");
    }
}
