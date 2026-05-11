package com.semantic.sketch;

import com.semantic.sketch.ablation.AutoAblationEngine;
import com.semantic.sketch.ablation.HumanArbiter;
import com.semantic.sketch.ablation.HumanArbiterDecision;
import com.semantic.sketch.ablation.ConflictManager;
import com.semantic.sketch.ablation.FactorGraphBuilder;
import com.semantic.sketch.ablation.GreedyInferenceEngine;
import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.maintenance.GraphMaintenanceService;
import com.semantic.sketch.maintenance.MaintenanceReport;
import com.semantic.sketch.protocol.SemanticProtocolCodec;
import com.semantic.sketch.semantic.IntentResidualCalculator;
import com.semantic.sketch.semantic.LightweightSemanticFingerprintService;
import com.semantic.sketch.semantic.SimHash64;
import com.semantic.sketch.semantic.SlidingWindowSemanticValidator;
import com.semantic.sketch.storage.InMemoryShadowStore;
import com.semantic.sketch.web.CollaborationSessionHub;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkSmokeTest {

    @Test
    void simHash_isStableForSameInput() {
        var svc = new LightweightSemanticFingerprintService();
        long a = svc.fingerprint("semantic sketch collaborative editing");
        long b = svc.fingerprint("semantic sketch collaborative editing");
        assertEquals(a, b);
    }

    @Test
    void slidingWindow_detectsSemanticOverlap() {
        long fp = SimHash64.fromWeightedKeywords(Map.of("merge", 1.0, "conflict", 2.0));
        Message m1 = new Message("1", "a", "x", Map.of("a", 1L), fp);
        Message m2 = new Message("2", "b", "y", Map.of("b", 1L), fp);

        SlidingWindowSemanticValidator validator = new SlidingWindowSemanticValidator(8, 0);
        assertTrue(validator.addAndValidate(m1).valid());
        assertFalse(validator.addAndValidate(m2).valid());
    }

    @Test
    void codec_roundTripPreservesFingerprint() {
        Message msg = new Message("op-1", "u1", "draw line", Map.of("u1", 7L), 123L);
        SemanticProtocolCodec codec = new SemanticProtocolCodec();
        ByteBuf buf = codec.encode(msg, ByteBufAllocator.DEFAULT);
        try {
            Message decoded = codec.decode(buf);
            assertEquals(123L, decoded.getSemanticFingerprint());
            assertEquals("op-1", decoded.getOpId());
        } finally {
            buf.release();
        }
    }

    @Test
    void autoAblation_returnsDecisionAndSupportsRollback() {
        var engine = new AutoAblationEngine(
                new ConflictManager(),
                new FactorGraphBuilder(),
                new GreedyInferenceEngine(),
                new InMemoryShadowStore(),
                (branchId, candidate, residualScore) -> HumanArbiterDecision.rollbackRedo("test rollback", "test", "branch:" + branchId)
        );

        Message m1 = new Message("1", "a", "insert hello", Map.of("a", 2L), 11L);
        Message m2 = new Message("2", "b", "insert hi", Map.of("b", 3L), 12L);

        MergeDecision decision = engine.run("master", List.of(m1, m2));
        assertFalse(decision.acceptedOps().isEmpty());

        MergeDecision rollback = engine.rollback("master");
        assertEquals(decision.score(), rollback.score());
    }

    @Test
    void editingOrchestrator_routesThroughHumanAndRollback() {
        ShadowStoreHistoryRecoveryService recoveryService = new ShadowStoreHistoryRecoveryService(new InMemoryShadowStore() {{
            save("master", new MergeDecision(List.of(), List.of(), -1.0, List.of()));
        }});
        var orchestrator = new EditingOrchestrator(
                new LightweightSemanticFingerprintService(),
                new ConflictManager(),
                new FactorGraphBuilder(),
                new GreedyInferenceEngine(),
                new IntentResidualCalculator(),
                (branchId, candidate, residualScore) -> HumanArbiterDecision.rollbackRedo("test rollback", "test", "branch:" + branchId),
                recoveryService,
                0.95
        );

        Message incoming = new Message("3", "c", "replace sky blue", Map.of("c", 2L), 0L);
        Message pending = new Message("4", "d", "replace sky red", Map.of("d", 2L), 0L);

        MergeDecision result = orchestrator.orchestrate("master", incoming, List.of(pending));
        assertEquals(-1.0, result.score());
        assertEquals("test rollback", recoveryService.lastRecoveryAudit().orElseThrow().triggerReason());
        assertEquals("test", recoveryService.lastRecoveryAudit().orElseThrow().decidedBy());
        assertEquals("branch:master", recoveryService.lastRecoveryAudit().orElseThrow().rollbackScope());
    }


    @Test
    void intentResidualCalculator_weightsExecutionStatesOnZeroOneScale() {
        IntentResidualCalculator calculator = new IntentResidualCalculator();
        double residual = calculator.calculate(new IntentResidualCalculator.ResidualInput(
                Map.of(
                        "critical", IntentResidualCalculator.ExecutionState.SKIPPED,
                        "minor", IntentResidualCalculator.ExecutionState.EXECUTED
                ),
                Map.of("critical", 1.0d, "minor", 0.2d),
                Map.of("critical", 1.0d, "minor", 0.2d),
                Map.of("critical", 1.0d, "minor", 0.2d)
        ));

        assertTrue(residual > 0.0d);
        assertTrue(residual < 0.5d);
    }

    @Test
    void collaborationHub_promptsHumanInterventionForConcurrentEdits() {
        CollaborationSessionHub hub = new CollaborationSessionHub(
                new LightweightSemanticFingerprintService(),
                new ConflictManager(),
                new FactorGraphBuilder(),
                new GreedyInferenceEngine(),
                new IntentResidualCalculator(),
                0.80d
        );

        CollaborationSessionHub.HubResult first = hub.submit("master", "alice", "replace title with semantic sketch");
        CollaborationSessionHub.HubResult second = hub.submit("master", "bob", "replace title with collaborative sketch");

        assertEquals("applied", first.type());
        assertEquals("human_intervention_required", second.type());
        assertFalse(second.requestId().isBlank());
        assertEquals(1, second.acceptedOps().size());
        assertEquals(1, second.rejectedOps().size());
    }

    @Test
    void graphMaintenance_deletesOrphansCompressesStableNodesAndUpdatesMetadata() {
        InMemoryShadowStore store = new InMemoryShadowStore();
        Message accepted1 = new Message("a1", "alice", "draw sky", Map.of("trunk", 1L, "alice", 1L), 0b1001L);
        Message accepted2 = new Message("a2", "alice", "paint sky", Map.of("trunk", 1L, "alice", 2L), 0b1000L);
        Message orphan = new Message("r1", "bob", "stale isolated stroke", Map.of("bob", 1L), 0b1111L);
        store.save("master", new MergeDecision(List.of(accepted1, accepted2), List.of(orphan), 0.9d, List.of()));

        GraphMaintenanceService maintenance = new GraphMaintenanceService(
                store,
                "trunk",
                Map.of("trunk", 1L, "alice", 2L, "bob", 1L),
                0.1d
        );

        MaintenanceReport report = maintenance.runOnce(Set.of("master"));
        MergeDecision maintained = store.get("master").orElseThrow();

        assertEquals(1, report.deletedCount());
        assertEquals(1, report.compressedCount());
        assertEquals(1, maintained.acceptedOps().size());
        assertEquals("a1+a2", maintained.acceptedOps().get(0).getOpId());
        assertTrue(maintained.rejectedOps().isEmpty());
        assertEquals(1, store.archives("master").size());
        assertTrue(store.getMetadata("master").orElseThrow().semanticTriples().get(0).precondition().contains("deleted=1"));
    }

    @Test
    void graphMaintenance_skipsUnstableBranches() {
        InMemoryShadowStore store = new InMemoryShadowStore();
        Message unstable = new Message("u1", "alice", "future edit", Map.of("alice", 7L), 0L);
        store.save("feature", new MergeDecision(List.of(unstable), List.of(), 0.8d, List.of()));

        GraphMaintenanceService maintenance = new GraphMaintenanceService(
                store,
                "trunk",
                Map.of("alice", 6L),
                0.1d
        );

        MaintenanceReport report = maintenance.runOnce(Set.of("feature"));

        assertEquals(0, report.deletedCount());
        assertEquals(0, report.compressedCount());
        assertTrue(report.skippedReasons().get("feature").contains("causal stability pending"));
        assertFalse(store.getMetadata("feature").isPresent());
    }
}
