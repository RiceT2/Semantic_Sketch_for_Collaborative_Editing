package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.ConflictEdge;
import com.semantic.sketch.model.FactorGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inference phase: simple greedy approximation without external PGM dependency.
 */
public class GreedyInferenceEngine implements InferenceEngine {
    @Override
    public MergeDecision infer(FactorGraph graph) {
        List<Message> accepted = new ArrayList<>();
        Set<String> blocked = new HashSet<>();
        List<MergeDecision.ScoreStep> steps = new ArrayList<>();
        Map<String, Double> unaryByOp = graph.metadata().unaryPotentialByOpId();

        List<ConflictEdge> sorted = new ArrayList<>(graph.edges());
        sorted.sort(Comparator.comparingDouble(ConflictEdge::conflictWeight).reversed());

        double totalScore = 0.0;

        for (Message node : graph.nodes()) {
            double unaryPotential = unaryByOp.getOrDefault(node.getOpId(), 1.0);
            if (!blocked.contains(node.getOpId())) {
                accepted.add(node);
                double pairwisePenalty = 0.0;
                for (ConflictEdge edge : sorted) {
                    if (edge.left().getOpId().equals(node.getOpId())) {
                        blocked.add(edge.right().getOpId());
                        pairwisePenalty += edge.conflictWeight();
                    } else if (edge.right().getOpId().equals(node.getOpId())) {
                        blocked.add(edge.left().getOpId());
                        pairwisePenalty += edge.conflictWeight();
                    }
                }
                totalScore += unaryPotential - pairwisePenalty;
                steps.add(new MergeDecision.ScoreStep(
                        node.getOpId(),
                        unaryPotential,
                        pairwisePenalty,
                        totalScore,
                        "ACCEPT"
                ));
            } else {
                totalScore -= unaryPotential * 0.1;
                steps.add(new MergeDecision.ScoreStep(
                        node.getOpId(),
                        unaryPotential,
                        0.0,
                        totalScore,
                        "REJECT"
                ));
            }
        }

        List<Message> rejected = graph.nodes().stream()
                .filter(node -> accepted.stream().noneMatch(keep -> keep.getOpId().equals(node.getOpId())))
                .toList();

        return new MergeDecision(accepted, rejected, totalScore, List.copyOf(steps));
    }
}
