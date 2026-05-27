package com.semantic.sketch.maintenance;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.ShadowMetadata;
import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.storage.ShadowStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background maintenance for shadow graph snapshots.
 *
 * <p>The first implementation is intentionally in-memory-store friendly: it scans current
 * branch snapshots, checks causal stability before mutating, archives orphaned rejected
 * operations, and compresses adjacent low-drift accepted operations.</p>
 */
public class GraphMaintenanceService {
    private static final String SYSTEM_ACTOR = "graph-maintenance";

    private final ShadowStore shadowStore;
    private final Clock clock;
    private final String trunkActorId;
    private final Map<String, Long> causalStableWatermark;
    private final double driftEpsilon;

    public GraphMaintenanceService(ShadowStore shadowStore,
                                   String trunkActorId,
                                   Map<String, Long> causalStableWatermark,
                                   double driftEpsilon) {
        this(shadowStore, Clock.systemUTC(), trunkActorId, causalStableWatermark, driftEpsilon);
    }

    public GraphMaintenanceService(ShadowStore shadowStore,
                                   Clock clock,
                                   String trunkActorId,
                                   Map<String, Long> causalStableWatermark,
                                   double driftEpsilon) {
        this.shadowStore = Objects.requireNonNull(shadowStore, "shadowStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.trunkActorId = Objects.requireNonNull(trunkActorId, "trunkActorId");
        this.causalStableWatermark = Map.copyOf(Objects.requireNonNull(causalStableWatermark, "causalStableWatermark"));
        if (driftEpsilon < 0.0d || driftEpsilon > 1.0d) {
            throw new IllegalArgumentException("driftEpsilon must be in [0, 1]");
        }
        this.driftEpsilon = driftEpsilon;
    }

    public ScheduledFuture<?> startPeriodic(ScheduledExecutorService executor, Duration interval) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return executor.scheduleWithFixedDelay(this::runOnce, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public MaintenanceReport runOnce() {
        return runOnce(shadowStore.branchIds());
    }

    public MaintenanceReport runOnce(Set<String> branchIds) {
        Instant startedAt = clock.instant();
        Map<String, String> skippedReasons = new LinkedHashMap<>();
        int deletedCount = 0;
        int compressedCount = 0;

        for (String branchId : branchIds) {
            Optional<MergeDecision> current = shadowStore.get(branchId);
            if (current.isEmpty()) {
                skippedReasons.put(branchId, "branch has no shadow decision");
                continue;
            }

            MergeDecision decision = current.get();
            List<Message> allOps = allOperations(decision);
            List<Message> unstableOps = allOps.stream().filter(op -> !isCausallyStable(op)).toList();
            if (!unstableOps.isEmpty()) {
                skippedReasons.put(branchId, "causal stability pending for ops " + unstableOps.stream().map(Message::getOpId).toList());
                continue;
            }

            shadowStore.save(branchId, decision, maintenanceMetadata(branchId, decision, "maintenance:start"));

            BranchMaintenanceResult result = maintainBranch(branchId, decision);
            deletedCount += result.deletedCount();
            compressedCount += result.compressedCount();

            shadowStore.save(branchId, result.decision(), maintenanceMetadata(branchId, result.decision(),
                    "maintenance:finish deleted=" + result.deletedCount() + " compressed=" + result.compressedCount()));
        }

        return new MaintenanceReport(deletedCount, compressedCount, skippedReasons, startedAt, clock.instant());
    }

    private BranchMaintenanceResult maintainBranch(String branchId, MergeDecision decision) {
        List<Message> retainedRejected = new ArrayList<>();
        int deletedCount = 0;
        for (Message rejected : decision.rejectedOps()) {
            if (isOrphanWithoutTrunkPredecessor(rejected, decision.acceptedOps())) {
                shadowStore.archive(branchId, rejected, "orphan rejected op without trunk predecessor");
                deletedCount++;
            } else {
                retainedRejected.add(rejected);
            }
        }

        CompressionResult compression = compressAcceptedOps(decision.acceptedOps());
        MergeDecision maintained = new MergeDecision(compression.operations(), retainedRejected, decision.score(), decision.scoreSteps());
        return new BranchMaintenanceResult(maintained, deletedCount, compression.compressedCount());
    }

    private boolean isCausallyStable(Message operation) {
        for (Map.Entry<String, Long> clockEntry : operation.getVectorClock().entrySet()) {
            long stableClock = causalStableWatermark.getOrDefault(clockEntry.getKey(), Long.MIN_VALUE);
            if (clockEntry.getValue() > stableClock) {
                return false;
            }
        }
        return true;
    }

    private boolean isOrphanWithoutTrunkPredecessor(Message candidate, List<Message> acceptedOps) {
        if (candidate.getVectorClock().getOrDefault(trunkActorId, 0L) > 0L) {
            return false;
        }
        long candidateClock = candidate.getVectorClock().getOrDefault(candidate.getActorId(), Long.MIN_VALUE);
        for (Message accepted : acceptedOps) {
            if (accepted.getVectorClock().getOrDefault(candidate.getActorId(), Long.MIN_VALUE) >= candidateClock) {
                return false;
            }
        }
        return true;
    }

    private CompressionResult compressAcceptedOps(List<Message> acceptedOps) {
        if (acceptedOps.isEmpty()) {
            return new CompressionResult(List.of(), 0);
        }

        List<Message> compressed = new ArrayList<>();
        MaintainedOperation pending = toMaintainedOperation(acceptedOps.get(0));
        int compressedCount = 0;
        for (int i = 1; i < acceptedOps.size(); i++) {
            MaintainedOperation next = toMaintainedOperation(acceptedOps.get(i));
            if (canCompress(pending, next)) {
                pending = merge(pending, next);
                compressedCount++;
            } else {
                compressed.add(pending.message());
                pending = next;
            }
        }
        compressed.add(pending.message());
        return new CompressionResult(compressed, compressedCount);
    }

    private boolean canCompress(MaintainedOperation left, MaintainedOperation right) {
        return left.message().getActorId().equals(right.message().getActorId())
                && isCausallyStable(left.message())
                && isCausallyStable(right.message())
                && targetCompatible(left.envelope(), right.envelope())
                && operationTypeCompatible(left.envelope().getOperationType(), right.envelope().getOperationType())
                && !hasPendingArbitration(left.envelope())
                && !hasPendingArbitration(right.envelope())
                && !hasLiveUndoRedoReference(left.envelope())
                && !hasLiveUndoRedoReference(right.envelope())
                && normalizedSemanticDrift(left.message().getSemanticFingerprint(), right.message().getSemanticFingerprint()) < driftEpsilon;
    }

    private double normalizedSemanticDrift(long left, long right) {
        return Long.bitCount(left ^ right) / 64.0d;
    }

    private MaintainedOperation merge(MaintainedOperation left, MaintainedOperation right) {
        Map<String, Long> mergedClock = new LinkedHashMap<>(left.message().getVectorClock());
        right.message().getVectorClock().forEach((actor, clockValue) -> mergedClock.merge(actor, clockValue, Math::max));

        CrdtOperationType mergedType = resolveMergedType(left.envelope().getOperationType(), right.envelope().getOperationType());
        String archiveRef = "archive://" + left.message().getOpId() + "," + right.message().getOpId();
        List<SemanticTriple> mergedTriples = new ArrayList<>();
        mergedTriples.addAll(left.envelope().getSemanticTriples());
        mergedTriples.addAll(right.envelope().getSemanticTriples());
        mergedTriples.add(new SemanticTriple("summary", "compacted", semanticSummary(left, right)));
        mergedTriples.add(new SemanticTriple("archive", "original_ops", archiveRef));

        CrdtOperationEnvelope mergedEnvelope = new CrdtOperationEnvelope(
                left.message().getOpId() + "+" + right.message().getOpId(),
                left.message().getActorId(),
                left.envelope().getBranchId(),
                mergedType,
                mergedClock,
                firstNonBlank(left.envelope().getTargetPath(), right.envelope().getTargetPath()),
                left.envelope().getFromIndex(),
                right.envelope().getToIndex(),
                mergedInsertedText(left, right, mergedType),
                mergedDeletedText(left, right, mergedType),
                "COMPACTED:" + semanticSummary(left, right),
                null,
                "COMPACTED:" + semanticSummary(left, right),
                CrdtOperationEnvelope.DEFAULT_ENCODING,
                CrdtOperationEnvelope.CURRENT_SCHEMA_VERSION,
                left.message().getSemanticFingerprint(),
                mergedTriples,
                clock.instant()
        );
        return new MaintainedOperation(mergedEnvelope.toMessage(), mergedEnvelope);
    }

    private MaintainedOperation toMaintainedOperation(Message message) {
        CrdtOperationType type = inferType(message);
        CrdtOperationEnvelope envelope = message.toEnvelope(null, type);
        return new MaintainedOperation(message, envelope);
    }

    private CrdtOperationType inferType(Message message) {
        try {
            return CrdtOperationType.fromString(message.getPayload());
        } catch (IllegalArgumentException ignored) {
            return CrdtOperationType.INSERT;
        }
    }

    private boolean targetCompatible(CrdtOperationEnvelope left, CrdtOperationEnvelope right) {
        return Objects.equals(left.getTargetPath(), right.getTargetPath()) || left.getTargetPath() == null || right.getTargetPath() == null;
    }

    private boolean operationTypeCompatible(CrdtOperationType left, CrdtOperationType right) {
        if (left == right) {
            return true;
        }
        return (left == CrdtOperationType.DELETE && right == CrdtOperationType.INSERT)
                || (left == CrdtOperationType.INSERT && right == CrdtOperationType.DELETE);
    }

    private CrdtOperationType resolveMergedType(CrdtOperationType left, CrdtOperationType right) {
        if ((left == CrdtOperationType.DELETE && right == CrdtOperationType.INSERT)
                || (left == CrdtOperationType.INSERT && right == CrdtOperationType.DELETE)) {
            return CrdtOperationType.REPLACE;
        }
        if (left == CrdtOperationType.SNAPSHOT || right == CrdtOperationType.SNAPSHOT) {
            return CrdtOperationType.SNAPSHOT;
        }
        return CrdtOperationType.COMPACTED;
    }

    private boolean hasPendingArbitration(CrdtOperationEnvelope envelope) { return false; }
    private boolean hasLiveUndoRedoReference(CrdtOperationEnvelope envelope) {
        return envelope.getOperationType() == CrdtOperationType.UNDO || envelope.getOperationType() == CrdtOperationType.REDO;
    }
    private String semanticSummary(MaintainedOperation left, MaintainedOperation right) {
        return left.message().getOpId() + "->" + right.message().getOpId();
    }
    private String mergedInsertedText(MaintainedOperation left, MaintainedOperation right, CrdtOperationType mergedType) {
        if (mergedType == CrdtOperationType.REPLACE) {
            return right.message().getPayload();
        }
        return left.message().getPayload() + "\n" + right.message().getPayload();
    }
    private String mergedDeletedText(MaintainedOperation left, MaintainedOperation right, CrdtOperationType mergedType) {
        if (mergedType == CrdtOperationType.REPLACE) {
            return left.message().getPayload();
        }
        return null;
    }
    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) return left;
        return right;
    }

    private ShadowMetadata maintenanceMetadata(String branchId, MergeDecision decision, String phase) {
        Message seed = allOperations(decision).stream().findFirst().orElse(null);
        return new ShadowMetadata(
                seed == null ? 0L : seed.getSemanticFingerprint(),
                List.of(new SemanticTriple("graph-maintenance", phase, "branch:" + branchId)),
                clock.instant(),
                SYSTEM_ACTOR
        );
    }

    private List<Message> allOperations(MergeDecision decision) {
        List<Message> allOps = new ArrayList<>(decision.acceptedOps());
        allOps.addAll(decision.rejectedOps());
        return allOps;
    }

    private record BranchMaintenanceResult(MergeDecision decision, int deletedCount, int compressedCount) {
    }

    private record CompressionResult(List<Message> operations, int compressedCount) {
    }

    private record MaintainedOperation(Message message, CrdtOperationEnvelope envelope) {
    }
}
