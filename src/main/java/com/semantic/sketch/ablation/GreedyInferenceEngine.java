package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.ConflictEdge;
import com.semantic.sketch.model.FactorGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Inference phase: simple greedy approximation without external PGM dependency.
 */
public class GreedyInferenceEngine implements InferenceEngine {
    @Override
    public MergeDecision infer(FactorGraph graph) {
        List<Message> accepted = new ArrayList<>();
        Set<String> blocked = new HashSet<>();

        List<ConflictEdge> sorted = new ArrayList<>(graph.edges());
        sorted.sort(Comparator.comparingDouble(ConflictEdge::psiWeight).reversed());

        for (Message node : graph.nodes()) {
            if (!blocked.contains(node.getOpId())) {
                accepted.add(node);
                for (ConflictEdge edge : sorted) {
                    if (edge.left().getOpId().equals(node.getOpId())) {
                        blocked.add(edge.right().getOpId());
                    } else if (edge.right().getOpId().equals(node.getOpId())) {
                        blocked.add(edge.left().getOpId());
                    }
                }
            }
        }

        List<Message> rejected = graph.nodes().stream()
                .filter(node -> accepted.stream().noneMatch(keep -> keep.getOpId().equals(node.getOpId())))
                .toList();

        double score = accepted.size() - rejected.size() * 0.5;
        return new MergeDecision(accepted, rejected, score);
    }
}
