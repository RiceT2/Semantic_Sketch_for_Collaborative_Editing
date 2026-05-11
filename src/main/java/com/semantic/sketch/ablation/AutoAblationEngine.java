package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.FactorGraph;
import com.semantic.sketch.model.ShadowMetadata;
import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.storage.ShadowStore;

import java.time.Instant;
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
        Message seed = candidates.isEmpty() ? null : candidates.get(0);
        ShadowMetadata metadata = new ShadowMetadata(
                seed == null ? 0L : seed.getSemanticFingerprint(),
                List.of(new SemanticTriple("merge", "vector-clock-concurrent", "branch:" + branchId)),
                Instant.now(),
                seed == null ? "system" : seed.getActorId());
        shadowStore.save(branchId, decision, metadata);

        HumanArbiterDecision humanDecision = humanArbiter.arbitrate(branchId, decision, decision.score());
        if (humanDecision.decisionType() == HumanArbiterDecision.DecisionType.ACCEPT_SYSTEM_PLAN) {
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
