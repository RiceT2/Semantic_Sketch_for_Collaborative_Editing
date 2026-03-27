package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.FactorGraph;
import com.semantic.sketch.storage.ShadowStore;

import java.util.List;

/**
 * End-to-end pipeline: collect -> model -> infer -> shadow store -> commit/rollback.
 */
public class AutoAblationEngine {
    private final ConflictManager conflictManager;
    private final FactorGraphBuilder graphBuilder;
    private final InferenceEngine inferenceEngine;
    private final ShadowStore shadowStore;
    private final HumanArbiter humanArbiter;

    public AutoAblationEngine(ConflictManager conflictManager,
                              FactorGraphBuilder graphBuilder,
                              InferenceEngine inferenceEngine,
                              ShadowStore shadowStore,
                              HumanArbiter humanArbiter) {
        this.conflictManager = conflictManager;
        this.graphBuilder = graphBuilder;
        this.inferenceEngine = inferenceEngine;
        this.shadowStore = shadowStore;
        this.humanArbiter = humanArbiter;
    }

    public MergeDecision run(String branchId, List<Message> candidates) {
        List<Message> conflictSet = candidates.stream()
                .filter(current -> candidates.stream().anyMatch(other -> !other.getOpId().equals(current.getOpId())
                        && conflictManager.isConcurrent(current, other)))
                .toList();

        FactorGraph graph = graphBuilder.build(conflictSet.isEmpty() ? candidates : conflictSet);
        MergeDecision decision = inferenceEngine.infer(graph);
        shadowStore.save(branchId, decision);

        if (humanArbiter.accept(branchId, decision)) {
            shadowStore.clear(branchId);
            return decision;
        }

        return rollback(branchId);
    }

    public MergeDecision rollback(String branchId) {
        return shadowStore.get(branchId)
                .orElseThrow(() -> new IllegalStateException("No shadow decision for branch " + branchId));
    }
}
