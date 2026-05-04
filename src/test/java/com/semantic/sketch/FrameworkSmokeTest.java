package com.semantic.sketch;

import com.semantic.sketch.ablation.AutoAblationEngine;
import com.semantic.sketch.ablation.HumanArbiter;
import com.semantic.sketch.ablation.ConflictManager;
import com.semantic.sketch.ablation.FactorGraphBuilder;
import com.semantic.sketch.ablation.GreedyInferenceEngine;
import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.protocol.SemanticProtocolCodec;
import com.semantic.sketch.semantic.LightweightSemanticFingerprintService;
import com.semantic.sketch.semantic.SimHash64;
import com.semantic.sketch.semantic.SlidingWindowSemanticValidator;
import com.semantic.sketch.storage.InMemoryShadowStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                (branchId, candidate) -> false
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
        var orchestrator = new EditingOrchestrator(
                new LightweightSemanticFingerprintService(),
                new ConflictManager(),
                new FactorGraphBuilder(),
                new GreedyInferenceEngine(),
                new IntentResidueCalculator(),
                (branchId, candidate) -> false,
                new ShadowStoreHistoryRecoveryService(new InMemoryShadowStore() {{
                    save("master", new MergeDecision(List.of(), List.of(), -1.0, List.of()));
                }}),
                0.95
        );

        Message incoming = new Message("3", "c", "replace sky blue", Map.of("c", 2L), 0L);
        Message pending = new Message("4", "d", "replace sky red", Map.of("d", 2L), 0L);

        MergeDecision result = orchestrator.orchestrate("master", incoming, List.of(pending));
        assertEquals(-1.0, result.score());
    }
}
