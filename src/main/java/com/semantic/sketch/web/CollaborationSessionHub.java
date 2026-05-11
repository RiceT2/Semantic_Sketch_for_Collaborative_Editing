package com.semantic.sketch.web;

import com.semantic.sketch.semantic.IntentResidualCalculator;
import com.semantic.sketch.ablation.ConflictManager;
import com.semantic.sketch.ablation.FactorGraphBuilder;
import com.semantic.sketch.ablation.GreedyInferenceEngine;
import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.semantic.SemanticFingerprintService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory demo hub used by the browser simulator to relay concurrent edits and human decisions.
 */
public class CollaborationSessionHub {
    private final SemanticFingerprintService fingerprintService;
    private final ConflictManager conflictManager;
    private final FactorGraphBuilder factorGraphBuilder;
    private final GreedyInferenceEngine inferenceEngine;
    private final IntentResidualCalculator residueCalculator;
    private final double residueThreshold;
    private final Map<String, BranchState> branches = new ConcurrentHashMap<>();

    public CollaborationSessionHub(SemanticFingerprintService fingerprintService,
                                   ConflictManager conflictManager,
                                   FactorGraphBuilder factorGraphBuilder,
                                   GreedyInferenceEngine inferenceEngine,
                                   IntentResidualCalculator residueCalculator,
                                   double residueThreshold) {
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.conflictManager = Objects.requireNonNull(conflictManager, "conflictManager");
        this.factorGraphBuilder = Objects.requireNonNull(factorGraphBuilder, "factorGraphBuilder");
        this.inferenceEngine = Objects.requireNonNull(inferenceEngine, "inferenceEngine");
        this.residueCalculator = Objects.requireNonNull(residueCalculator, "residueCalculator");
        this.residueThreshold = residueThreshold;
    }

    public synchronized HubResult submit(String branchId, String actorId, String payload) {
        BranchState state = branch(branchId);
        long actorTick = state.actorClock.merge(actorId, 1L, Long::sum);
        Message incoming = new Message(
                "op-" + UUID.randomUUID(),
                actorId,
                payload,
                Map.of(actorId, actorTick),
                fingerprintService.fingerprint(payload)
        );

        List<Message> concurrent = state.operations.stream()
                .filter(existing -> conflictManager.isConcurrent(incoming, existing))
                .toList();
        if (concurrent.isEmpty()) {
            state.operations.add(incoming);
            return HubResult.applied(branchId, incoming, List.of(incoming), List.of(), 1.0d, 1.0d,
                    "未发现并发语义冲突，操作已直接应用。", null);
        }

        List<Message> candidates = new ArrayList<>(concurrent);
        candidates.add(incoming);
        candidates.sort(Comparator.comparing(Message::getOpId));
        MergeDecision decision = inferenceEngine.infer(factorGraphBuilder.build(candidates));
        double residue = residueCalculator.calculate(decision);
        boolean requiresHuman = residue < residueThreshold || !decision.rejectedOps().isEmpty();
        if (!requiresHuman) {
            applyDecision(state, decision);
            return HubResult.applied(branchId, incoming, decision.acceptedOps(), decision.rejectedOps(), decision.score(), residue,
                    "检测到并发但最优方案残留度达标，系统已自动应用。", null);
        }

        String requestId = "hr-" + UUID.randomUUID();
        state.pendingRequests.put(requestId, new InterventionRequest(requestId, branchId, incoming, decision, residue, Instant.now()));
        return HubResult.humanRequired(branchId, incoming, decision.acceptedOps(), decision.rejectedOps(), decision.score(), residue,
                "检测到并发语义冲突且意图残留度低于阈值，已发起人工介入请求。", requestId);
    }

    public synchronized HubResult decide(String branchId, String requestId, String decisionType) {
        BranchState state = branch(branchId);
        InterventionRequest request = state.pendingRequests.remove(requestId);
        if (request == null) {
            return HubResult.system(branchId, "人工介入请求不存在或已处理：" + requestId);
        }
        if ("rollback".equalsIgnoreCase(decisionType)) {
            return HubResult.system(branchId, "用户选择回溯，冲突候选操作未写入主文档。", requestId);
        }
        applyDecision(state, request.decision());
        return HubResult.applied(branchId, request.incoming(), request.decision().acceptedOps(), request.decision().rejectedOps(),
                request.decision().score(), request.residue(), "用户接受最优方案，已写入主文档。", requestId);
    }

    public synchronized Map<String, ?> snapshot(String branchId) {
        BranchState state = branch(branchId);
        return Map.of(
                "branchId", branchId,
                "operations", state.operations.stream().map(this::messageView).toList(),
                "pendingRequests", state.pendingRequests.values().stream().map(this::requestView).toList()
        );
    }

    private void applyDecision(BranchState state, MergeDecision decision) {
        for (Message rejected : decision.rejectedOps()) {
            state.operations.removeIf(existing -> existing.getOpId().equals(rejected.getOpId()));
        }
        for (Message accepted : decision.acceptedOps()) {
            boolean exists = state.operations.stream().anyMatch(existing -> existing.getOpId().equals(accepted.getOpId()));
            if (!exists) {
                state.operations.add(accepted);
            }
        }
    }

    private BranchState branch(String branchId) {
        return branches.computeIfAbsent(branchId == null || branchId.isBlank() ? "master" : branchId, ignored -> new BranchState());
    }

    private Map<String, ?> requestView(InterventionRequest request) {
        return Map.of(
                "requestId", request.requestId(),
                "incoming", messageView(request.incoming()),
                "acceptedOps", request.decision().acceptedOps().stream().map(this::messageView).toList(),
                "rejectedOps", request.decision().rejectedOps().stream().map(this::messageView).toList(),
                "residue", request.residue(),
                "createdAt", request.createdAt().toString()
        );
    }

    Map<String, ?> resultView(HubResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", result.type());
        view.put("branchId", result.branchId());
        view.put("message", result.message());
        view.put("requestId", result.requestId());
        view.put("score", result.score());
        view.put("residue", result.residue());
        view.put("incoming", result.incoming() == null ? null : messageView(result.incoming()));
        view.put("acceptedOps", result.acceptedOps().stream().map(this::messageView).toList());
        view.put("rejectedOps", result.rejectedOps().stream().map(this::messageView).toList());
        view.put("snapshot", snapshot(result.branchId()));
        return view;
    }

    private Map<String, ?> messageView(Message message) {
        return Map.of(
                "opId", message.getOpId(),
                "actorId", message.getActorId(),
                "payload", message.getPayload(),
                "vectorClock", message.getVectorClock(),
                "semanticFingerprint", Long.toUnsignedString(message.getSemanticFingerprint())
        );
    }

    private static final class BranchState {
        private final List<Message> operations = new ArrayList<>();
        private final Map<String, Long> actorClock = new ConcurrentHashMap<>();
        private final Map<String, InterventionRequest> pendingRequests = new LinkedHashMap<>();
    }

    private record InterventionRequest(String requestId,
                                       String branchId,
                                       Message incoming,
                                       MergeDecision decision,
                                       double residue,
                                       Instant createdAt) {
    }

    public record HubResult(String type,
                            String branchId,
                            Message incoming,
                            List<Message> acceptedOps,
                            List<Message> rejectedOps,
                            double score,
                            double residue,
                            String message,
                            String requestId) {
        static HubResult applied(String branchId,
                                 Message incoming,
                                 List<Message> acceptedOps,
                                 List<Message> rejectedOps,
                                 double score,
                                 double residue,
                                 String message,
                                 String requestId) {
            return new HubResult("applied", branchId, incoming, acceptedOps, rejectedOps, score, residue, message, requestId);
        }

        static HubResult humanRequired(String branchId,
                                       Message incoming,
                                       List<Message> acceptedOps,
                                       List<Message> rejectedOps,
                                       double score,
                                       double residue,
                                       String message,
                                       String requestId) {
            return new HubResult("human_intervention_required", branchId, incoming, acceptedOps, rejectedOps, score, residue, message, requestId);
        }

        static HubResult system(String branchId, String message) {
            return new HubResult("system", branchId, null, List.of(), List.of(), 0.0d, 1.0d, message, null);
        }

        static HubResult system(String branchId, String message, String requestId) {
            return new HubResult("system", branchId, null, List.of(), List.of(), 0.0d, 1.0d, message, requestId);
        }
    }
}
