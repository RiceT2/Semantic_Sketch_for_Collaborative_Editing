package com.semantic.sketch;

import com.semantic.sketch.ablation.ConflictManager;
import com.semantic.sketch.ablation.FactorGraphBuilder;
import com.semantic.sketch.ablation.GreedyInferenceEngine;
import com.semantic.sketch.ablation.HumanArbiter;
import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.Message;
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
    private final IntentResidueCalculator intentResidueCalculator;
    private final HumanArbiter humanArbiter;
    private final HistoryRecoveryService historyRecoveryService;
    private final double residueThreshold;

    public EditingOrchestrator(SemanticFingerprintService fingerprintService,
                               ConflictManager conflictManager,
                               FactorGraphBuilder factorGraphBuilder,
                               GreedyInferenceEngine greedyInferenceEngine,
                               IntentResidueCalculator intentResidueCalculator,
                               HumanArbiter humanArbiter,
                               HistoryRecoveryService historyRecoveryService,
                               double residueThreshold) {
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.conflictManager = Objects.requireNonNull(conflictManager, "conflictManager");
        this.factorGraphBuilder = Objects.requireNonNull(factorGraphBuilder, "factorGraphBuilder");
        this.greedyInferenceEngine = Objects.requireNonNull(greedyInferenceEngine, "greedyInferenceEngine");
        this.intentResidueCalculator = Objects.requireNonNull(intentResidueCalculator, "intentResidueCalculator");
        this.humanArbiter = Objects.requireNonNull(humanArbiter, "humanArbiter");
        this.historyRecoveryService = Objects.requireNonNull(historyRecoveryService, "historyRecoveryService");
        this.residueThreshold = residueThreshold;
    }

    public MergeDecision orchestrate(String branchId, Message incoming, List<Message> pending) {
        Message enrichedIncoming = enrichFingerprint(incoming);
        List<Message> enrichedPending = pending.stream().map(this::enrichFingerprint).toList();

        List<Message> conflictSet = collectConcurrent(enrichedIncoming, enrichedPending);
        if (conflictSet.isEmpty()) {
            return new MergeDecision(List.of(enrichedIncoming), List.of(), 1.0d);
        }

        List<Message> candidates = new ArrayList<>(conflictSet);
        candidates.add(enrichedIncoming);
        MergeDecision optimal = greedyInferenceEngine.infer(factorGraphBuilder.build(candidates));

        double residue = intentResidueCalculator.calculate(optimal);
        if (residue >= residueThreshold) {
            return optimal;
        }

        boolean acceptedByHuman = humanArbiter.accept(branchId, optimal);
        if (acceptedByHuman) {
            return optimal;
        }

        return historyRecoveryService.recover(branchId).orElse(optimal);
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
