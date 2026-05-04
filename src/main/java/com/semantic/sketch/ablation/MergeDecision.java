package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.List;

public record MergeDecision(List<Message> acceptedOps,
                            List<Message> rejectedOps,
                            double score,
                            List<ScoreStep> scoreSteps) {

    public record ScoreStep(String opId,
                            double unaryPotential,
                            double pairwisePenalty,
                            double cumulativeScore,
                            String decision) {
    }
}
