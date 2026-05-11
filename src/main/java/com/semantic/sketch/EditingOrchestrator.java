package com.semantic.sketch;

import com.semantic.sketch.ablation.ConflictManager;
import com.semantic.sketch.ablation.FactorGraphBuilder;
import com.semantic.sketch.ablation.GreedyInferenceEngine;
import com.semantic.sketch.ablation.HumanArbiter;
import com.semantic.sketch.ablation.HumanArbiterDecision;
import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.semantic.IntentResidualCalculator;
import com.semantic.sketch.semantic.SemanticFingerprintService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * End-to-end orchestration entry for semantic collaborative editing.
 */
public class EditingOrchestrator {
    private final SemanticFingerprintService fingerprintService;
    private final ConflictManager conflictManager;
    private final FactorGraphBuilder factorGraphBuilder;
    private final GreedyInferenceEngine greedyInferenceEngine;
    private final IntentResidualCalculator intentResidualCalculator;
    private final HumanArbiter humanArbiter;
    private final HistoryRecoveryService historyRecoveryService;
    private final double residueThreshold;

    public EditingOrchestrator(SemanticFingerprintService fingerprintService,
                               ConflictManager conflictManager,
                               FactorGraphBuilder factorGraphBuilder,
                               GreedyInferenceEngine greedyInferenceEngine,
                               IntentResidualCalculator intentResidualCalculator,
                               HumanArbiter humanArbiter,
                               HistoryRecoveryService historyRecoveryService,
                               double residueThreshold) {
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.conflictManager = Objects.requireNonNull(conflictManager, "conflictManager");
        this.factorGraphBuilder = Objects.requireNonNull(factorGraphBuilder, "factorGraphBuilder");
        this.greedyInferenceEngine = Objects.requireNonNull(greedyInferenceEngine, "greedyInferenceEngine");
        this.intentResidualCalculator = Objects.requireNonNull(intentResidualCalculator, "intentResidualCalculator");
        this.humanArbiter = Objects.requireNonNull(humanArbiter, "humanArbiter");
        this.historyRecoveryService = Objects.requireNonNull(historyRecoveryService, "historyRecoveryService");
        this.residueThreshold = residueThreshold;
    }

    public MergeDecision orchestrate(String branchId, Message incoming, List<Message> pending) {
        Message enrichedIncoming = enrichFingerprint(incoming);
        List<Message> enrichedPending = pending.stream().map(this::enrichFingerprint).toList();

        List<Message> conflictSet = collectConcurrent(enrichedIncoming, enrichedPending);
        if (conflictSet.isEmpty()) {
            return new MergeDecision(List.of(enrichedIncoming), List.of(), 1.0d, List.of());
        }

        List<Message> candidates = new ArrayList<>(conflictSet);
        candidates.add(enrichedIncoming);
        MergeDecision optimal = greedyInferenceEngine.infer(factorGraphBuilder.build(candidates));

        double residualScore = intentResidualCalculator.calculate(optimal);
        if (residualScore >= residueThreshold) {
            return optimal;
        }

        HumanArbiterDecision humanDecision = humanArbiter.arbitrate(branchId, optimal, residualScore);
        if (humanDecision.decisionType() == HumanArbiterDecision.DecisionType.ACCEPT_SYSTEM_PLAN) {
            return optimal;
        }

        RecoveryAudit audit = RecoveryAudit.of(
                humanDecision.triggerReason(),
                humanDecision.decidedBy(),
                humanDecision.rollbackScope()
        );
        return historyRecoveryService.recover(branchId, audit).orElse(optimal);
    }

    private Message enrichFingerprint(Message message) {
        long h = fingerprintService.fingerprint(message.getPayload());
        if (h == message.getSemanticFingerprint()) {
            return message;
        }
        return new Message(
                message.getOpId(),
                message.getActorId(),
                message.getPayload(),
                Map.copyOf(message.getVectorClock()),
                h
        );
    }

    private List<Message> collectConcurrent(Message incoming, List<Message> pending) {
        List<Message> concurrent = new ArrayList<>();
        for (Message candidate : pending) {
            if (conflictManager.isConcurrent(incoming, candidate)) {
                concurrent.add(candidate);
            }
        }
        return concurrent;
    }
}
