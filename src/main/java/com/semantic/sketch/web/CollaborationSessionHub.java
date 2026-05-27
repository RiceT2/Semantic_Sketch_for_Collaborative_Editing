package com.semantic.sketch.web;

import com.semantic.sketch.semantic.IntentResidualCalculator;
import com.semantic.sketch.ablation.ConflictManager;
import com.semantic.sketch.ablation.FactorGraphBuilder;
import com.semantic.sketch.ablation.GreedyInferenceEngine;
import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.CrdtAdapter;
import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.crdt.InMemoryTextCrdtAdapter;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.semantic.SemanticFingerprintService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final CrdtAdapter crdtAdapter;
    private final Map<String, BranchState> branches = new ConcurrentHashMap<>();

    public CollaborationSessionHub(SemanticFingerprintService fingerprintService,
                                   ConflictManager conflictManager,
                                   FactorGraphBuilder factorGraphBuilder,
                                   GreedyInferenceEngine inferenceEngine,
                                   IntentResidualCalculator residueCalculator,
                                   double residueThreshold) {
        this(
                fingerprintService,
                conflictManager,
                factorGraphBuilder,
                inferenceEngine,
                residueCalculator,
                residueThreshold,
                new InMemoryTextCrdtAdapter()
        );
    }

    public CollaborationSessionHub(SemanticFingerprintService fingerprintService,
                                   ConflictManager conflictManager,
                                   FactorGraphBuilder factorGraphBuilder,
                                   GreedyInferenceEngine inferenceEngine,
                                   IntentResidualCalculator residueCalculator,
                                   double residueThreshold,
                                   CrdtAdapter crdtAdapter) {
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
        this.conflictManager = Objects.requireNonNull(conflictManager, "conflictManager");
        this.factorGraphBuilder = Objects.requireNonNull(factorGraphBuilder, "factorGraphBuilder");
        this.inferenceEngine = Objects.requireNonNull(inferenceEngine, "inferenceEngine");
        this.residueCalculator = Objects.requireNonNull(residueCalculator, "residueCalculator");
        this.residueThreshold = residueThreshold;
        this.crdtAdapter = Objects.requireNonNull(crdtAdapter, "crdtAdapter");
    }

    public synchronized HubResult submit(String branchId, String actorId, String payload) {
        return submit(defaultEnvelope(branchId, actorId, payload));
    }

    public synchronized HubResult submit(CrdtOperationEnvelope envelope) {
        BranchState state = branch(envelope.getBranchId());
        long actorTick = state.actorClock.merge(envelope.getActorId(), 1L, Long::sum);
        CrdtOperationEnvelope stampedEnvelope = stampEnvelope(envelope, Map.of(envelope.getActorId(), actorTick));
        Message incoming = stampedEnvelope.toMessage();
        state.operationEnvelopes.put(incoming.getOpId(), stampedEnvelope);

        List<Message> concurrent = state.operations.stream()
                .filter(existing -> conflictManager.isConcurrent(incoming, existing))
                .toList();
        if (concurrent.isEmpty()) {
            applyAcceptedEnvelope(state, stampedEnvelope);
            return HubResult.applied(stampedEnvelope.getBranchId(), incoming, List.of(incoming), List.of(), 1.0d, 1.0d,
                    "未发现并发语义冲突，操作已直接应用。", null);
        }

        List<Message> candidates = new ArrayList<>(concurrent);
        candidates.add(incoming);
        candidates.sort(Comparator.comparing(Message::getOpId));
        List<CrdtOperationEnvelope> candidateEnvelopes = candidates.stream()
                .map(message -> state.operationEnvelopes.getOrDefault(
                        message.getOpId(),
                        CrdtOperationEnvelope.fromMessage(message, stampedEnvelope.getBranchId(), inferOperationType(message.getPayload()))))
                .toList();
        MergeDecision decision = inferenceEngine.infer(factorGraphBuilder.buildFromEnvelopes(candidateEnvelopes));
        double residue = residueCalculator.calculate(decision);
        boolean requiresHuman = residue < residueThreshold || !decision.rejectedOps().isEmpty();
        if (!requiresHuman) {
            applyDecision(state, decision);
            return HubResult.applied(stampedEnvelope.getBranchId(), incoming, decision.acceptedOps(), decision.rejectedOps(), decision.score(), residue,
                    "检测到并发但最优方案残留度达标，系统已自动应用。", null);
        }

        String requestId = "hr-" + UUID.randomUUID();
        state.pendingRequests.put(requestId, new InterventionRequest(requestId, stampedEnvelope.getBranchId(), incoming, decision, residue, Instant.now()));
        return HubResult.humanRequired(stampedEnvelope.getBranchId(), incoming, decision.acceptedOps(), decision.rejectedOps(), decision.score(), residue,
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
        String normalizedBranchId = branchId == null || branchId.isBlank() ? "master" : branchId;
        String rawCrdtDocument = crdtAdapter.renderDocument(normalizedBranchId);
        List<Map<String, Object>> operationsList = state.operations.stream().map(message -> operationView(normalizedBranchId, message)).toList();
        List<Map<String, Object>> pendingList = (List<Map<String, Object>>) state.pendingRequests.values().stream().map((java.util.function.Function<? super InterventionRequest, ? extends Map<String, Object>>) this::requestView).toList();
        return Map.of(
                "branchId", normalizedBranchId,
                "document", crdtAdapter.renderDocument(normalizedBranchId),
                "rawCrdtDocument", rawCrdtDocument,
                "semanticMergedDocument", buildSemanticMergedDocument(state, normalizedBranchId),
                "crdt", crdtAdapter.snapshot(normalizedBranchId),
                "stateVector", crdtAdapter.stateVector(normalizedBranchId),
                "operations", operationsList,
                "pendingRequests", pendingList
        );
    }

    private void applyDecision(BranchState state, MergeDecision decision) {
        for (Message rejected : decision.rejectedOps()) {
            state.operations.removeIf(existing -> existing.getOpId().equals(rejected.getOpId()));
        }
        for (Message accepted : decision.acceptedOps()) {
            boolean exists = state.operations.stream().anyMatch(existing -> existing.getOpId().equals(accepted.getOpId()));
            if (!exists) {
                CrdtOperationEnvelope envelope = state.operationEnvelopes.get(accepted.getOpId());
                if (envelope != null) {
                    applyAcceptedEnvelope(state, envelope);
                } else {
                    state.operations.add(accepted);
                }
            }
        }
    }

    private void applyAcceptedEnvelope(BranchState state, CrdtOperationEnvelope envelope) {
        crdtAdapter.apply(envelope);
        state.operations.add(envelope.toMessage());
    }

    private BranchState branch(String branchId) {
        return branches.computeIfAbsent(branchId == null || branchId.isBlank() ? "master" : branchId, ignored -> new BranchState());
    }

    private Map<String, Object> requestView(InterventionRequest request) {
        List<Map<String, Object>> accepted = request.decision().acceptedOps().stream().map(message -> operationView(request.branchId(), message)).toList();
        List<Map<String, Object>> rejected = request.decision().rejectedOps().stream().map(message -> operationView(request.branchId(), message)).toList();
        return Map.of(
                "requestId", request.requestId(),
                "incoming", operationView(request.branchId(), request.incoming()),
                "acceptedOps", accepted,
                "rejectedOps", rejected,
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
        view.put("incoming", result.incoming() == null ? null : operationView(result.branchId(), result.incoming()));
        List<Map<String, Object>> acceptedOpsList = result.acceptedOps().stream().map(message -> operationView(result.branchId(), message)).toList();
        List<Map<String, Object>> rejectedOpsList = result.rejectedOps().stream().map(message -> operationView(result.branchId(), message)).toList();
        view.put("acceptedOps", acceptedOpsList);
        view.put("rejectedOps", rejectedOpsList);
        view.put("arbitrationExplanation", arbitrationExplanation(result));
        view.put("snapshot", snapshot(result.branchId()));
        return view;
    }

    private Map<String, ?> arbitrationExplanation(HubResult result) {
        List<Map<String, Object>> accepted = result.acceptedOps().stream()
                .map(message -> operationView(result.branchId(), message))
                .toList();
        List<Map<String, Object>> suppressed = result.rejectedOps().stream()
                .map(message -> operationView(result.branchId(), message))
                .toList();
        String decisionSource = "applied".equals(result.type()) ? "system-auto" : "human-required";
        String reason = result.requestId() == null
                ? "DeepSeek/本地规则自动决策：残留度满足阈值，采用 accepted 并抑制 suppressed。"
                : "DeepSeek/本地规则建议 + 人工介入：等待人工确认 accepted/suppressed。";
        return Map.of(
                "accepted", accepted,
                "suppressed", suppressed,
                "compensated", List.of(),
                "decisionSource", decisionSource,
                "reason", reason
        );
    }

    private String buildSemanticMergedDocument(BranchState state, String branchId) {
        String projected = state.operations.stream()
                .map(message -> {
                    CrdtOperationEnvelope envelope = state.operationEnvelopes.get(message.getOpId());
                    if (envelope == null) {
                        return message.getPayload();
                    }
                    return envelope.getIntentText() == null || envelope.getIntentText().isBlank()
                            ? message.getPayload()
                            : envelope.getIntentText();
                })
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return projected.isBlank() ? crdtAdapter.renderDocument(branchId) : projected;
    }

    private Map<String, Object> operationView(String branchId, Message message) {
        CrdtOperationEnvelope envelope = branch(branchId).operationEnvelopes.get(message.getOpId());
        if (envelope == null) {
            envelope = CrdtOperationEnvelope.fromMessage(message, branchId, inferOperationType(message.getPayload()));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("opId", message.getOpId());
        view.put("actorId", message.getActorId());
        view.put("payload", message.getPayload());
        view.put("vectorClock", message.getVectorClock());
        view.put("semanticFingerprint", Long.toUnsignedString(message.getSemanticFingerprint()));
        view.put("operationType", envelope.getOperationType().toJsonValue());
        view.put("targetPath", envelope.getTargetPath());
        view.put("fromIndex", envelope.getFromIndex());
        view.put("toIndex", envelope.getToIndex());
        view.put("intentText", envelope.getIntentText());
        view.put("semanticTriples", envelope.getSemanticTriples().stream().map(this::semanticTripleView).toList());
        boolean hasYjsUpdateBase64 = envelope.getYjsUpdateBase64() != null && !envelope.getYjsUpdateBase64().isBlank();
        view.put("hasYjsUpdateBase64", hasYjsUpdateBase64);
        view.put("yjsUpdateBase64Present", hasYjsUpdateBase64);
        return view;
    }

    private Map<String, ?> semanticTripleView(SemanticTriple triple) {
        return Map.of(
                "operationType", triple.operationType(),
                "intent", triple.intent(),
                "target", triple.target(),
                "precondition", triple.precondition(),
                "impactScope", triple.impactScope(),
                "polarity", triple.polarity()
        );
    }

    private CrdtOperationEnvelope defaultEnvelope(String branchId, String actorId, String payload) {
        String safePayload = payload == null || payload.isBlank() ? "empty edit" : payload;
        CrdtOperationType operationType = inferOperationType(safePayload);
        return new CrdtOperationEnvelope(
                "op-" + UUID.randomUUID(),
                actorId == null || actorId.isBlank() ? "anonymous" : actorId,
                branchId == null || branchId.isBlank() ? "master" : branchId,
                operationType,
                Map.of(),
                null,
                null,
                null,
                operationType == CrdtOperationType.DELETE ? null : safePayload,
                operationType == CrdtOperationType.DELETE ? safePayload : null,
                safePayload,
                null,
                fingerprintService.fingerprint(new CrdtOperationEnvelope(
                        "semantic-preview",
                        actorId == null || actorId.isBlank() ? "anonymous" : actorId,
                        branchId == null || branchId.isBlank() ? "master" : branchId,
                        operationType,
                        Map.of(),
                        null,
                        null,
                        null,
                        operationType == CrdtOperationType.DELETE ? null : safePayload,
                        operationType == CrdtOperationType.DELETE ? safePayload : null,
                        safePayload,
                        null,
                        safePayload,
                        CrdtOperationEnvelope.DEFAULT_ENCODING,
                        CrdtOperationEnvelope.CURRENT_SCHEMA_VERSION,
                        0L,
                        List.of(),
                        Instant.now()
                )),
                safePayload,
                CrdtOperationEnvelope.DEFAULT_ENCODING,
                CrdtOperationEnvelope.CURRENT_SCHEMA_VERSION,
                List.of(),
                Instant.now()
        );
    }

    private CrdtOperationEnvelope stampEnvelope(CrdtOperationEnvelope envelope, Map<String, Long> vectorClock) {
        String intentText = envelope.getIntentText() == null || envelope.getIntentText().isBlank()
                ? envelope.toMessage().getPayload()
                : envelope.getIntentText();
        CrdtOperationEnvelope normalizedEnvelope = new CrdtOperationEnvelope(
                envelope.getOpId(),
                envelope.getActorId(),
                envelope.getBranchId() == null || envelope.getBranchId().isBlank() ? "master" : envelope.getBranchId(),
                envelope.getOperationType(),
                vectorClock,
                envelope.getTargetPath(),
                envelope.getFromIndex(),
                envelope.getToIndex(),
                envelope.getInsertedText(),
                envelope.getDeletedTextPreview(),
                intentText,
                envelope.getYjsUpdateBase64(),
                envelope.getCrdtPayload(),
                envelope.getEncoding(),
                envelope.getSchemaVersion(),
                0L,
                envelope.getSemanticTriples(),
                envelope.getCreatedAt()
        );
        return new CrdtOperationEnvelope(
                normalizedEnvelope.getOpId(),
                normalizedEnvelope.getActorId(),
                normalizedEnvelope.getBranchId(),
                normalizedEnvelope.getOperationType(),
                normalizedEnvelope.getVectorClock(),
                normalizedEnvelope.getTargetPath(),
                normalizedEnvelope.getFromIndex(),
                normalizedEnvelope.getToIndex(),
                normalizedEnvelope.getInsertedText(),
                normalizedEnvelope.getDeletedTextPreview(),
                normalizedEnvelope.getIntentText(),
                normalizedEnvelope.getYjsUpdateBase64(),
                normalizedEnvelope.getCrdtPayload(),
                normalizedEnvelope.getEncoding(),
                normalizedEnvelope.getSchemaVersion(),
                fingerprintService.fingerprint(normalizedEnvelope),
                normalizedEnvelope.getSemanticTriples().isEmpty()
                        ? fingerprintService.extractTriples(normalizedEnvelope)
                        : normalizedEnvelope.getSemanticTriples(),
                normalizedEnvelope.getCreatedAt()
        );
    }

    private CrdtOperationType inferOperationType(String payload) {
        if (payload != null) {
            String firstToken = payload.strip().split("\\s+", 2)[0];
            try {
                return CrdtOperationType.fromString(firstToken);
            } catch (IllegalArgumentException ignored) {
                // Fall through to the compatibility default used by the legacy text payload path.
            }
        }
        return CrdtOperationType.REPLACE;
    }

    private static final class BranchState {
        private final List<Message> operations = new ArrayList<>();
        private final Map<String, Long> actorClock = new ConcurrentHashMap<>();
        private final Map<String, CrdtOperationEnvelope> operationEnvelopes = new HashMap<>();
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
