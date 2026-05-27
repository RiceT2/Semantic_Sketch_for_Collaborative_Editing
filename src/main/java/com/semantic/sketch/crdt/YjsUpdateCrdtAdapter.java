package com.semantic.sketch.crdt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholder adapter for Yjs binary updates.
 *
 * <p>The JVM service currently stores or forwards {@code yjsUpdateBase64} envelopes without decoding
 * the Y.Doc. Rendering will be supplied later by one of these integration paths:</p>
 *
 * <ul>
 *     <li>a Node.js sidecar that applies Yjs updates and renders document JSON/text,</li>
 *     <li>a Rust {@code yrs} sidecar exposed over a local RPC boundary, or</li>
 *     <li>a browser-generated snapshot posted back after the client applies the updates.</li>
 * </ul>
 */
public class YjsUpdateCrdtAdapter implements CrdtAdapter {
    private static final String DEFAULT_BRANCH = "master";

    private final Map<String, BranchUpdates> branches = new ConcurrentHashMap<>();
    private final YjsSidecarClient sidecarClient;

    public YjsUpdateCrdtAdapter() {
        this(new InProcessYjsSidecarClient());
    }

    public YjsUpdateCrdtAdapter(YjsSidecarClient sidecarClient) {
        this.sidecarClient = Objects.requireNonNull(sidecarClient, "sidecarClient");
    }

    @Override
    public synchronized void apply(CrdtOperationEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        BranchUpdates branch = branch(envelope.getBranchId());
        String update = envelope.getYjsUpdateBase64();
        if (update == null || update.isBlank()) {
            throw new IllegalArgumentException("Yjs update envelope must include yjsUpdateBase64");
        }
        branch.updates.add(UpdateRecord.from(envelope));
        YjsSidecarClient.SidecarMergeResult mergeResult = sidecarClient.applyUpdate(normalizeBranchId(envelope.getBranchId()), update);
        branch.documentHash = mergeResult.documentHash();
        branch.stateVector.clear();
        branch.stateVector.putAll(mergeResult.stateVector());
    }

    @Override
    public synchronized String renderDocument(String branchId) {
        return sidecarClient.exportText(normalizeBranchId(branchId));
    }

    @Override
    public synchronized Map<String, ?> snapshot(String branchId) {
        String normalizedBranchId = normalizeBranchId(branchId);
        BranchUpdates branch = branch(normalizedBranchId);
        return Map.of(
                "branchId", normalizedBranchId,
                "renderingStatus", "rendered_by_sidecar",
                "document", sidecarClient.exportText(normalizedBranchId),
                "documentJson", sidecarClient.exportJson(normalizedBranchId),
                "documentHash", branch.documentHash,
                "sidecarVersion", sidecarClient.sidecarVersion(),
                "stateVector", Map.copyOf(branch.stateVector),
                "updates", branch.updates.stream().map(UpdateRecord::toView).toList()
        );
    }

    @Override
    public synchronized Map<String, Long> stateVector(String branchId) {
        return Map.copyOf(branch(branchId).stateVector);
    }

    @Override
    public synchronized void compact(String branchId, Map<String, Long> watermark) {
        if (watermark == null || watermark.isEmpty()) {
            return;
        }
        BranchUpdates branch = branch(branchId);
        branch.updates.removeIf(record -> coveredBy(record.vectorClock(), watermark));
    }

    private BranchUpdates branch(String branchId) {
        return branches.computeIfAbsent(normalizeBranchId(branchId), ignored -> new BranchUpdates());
    }

    private String normalizeBranchId(String branchId) {
        return branchId == null || branchId.isBlank() ? DEFAULT_BRANCH : branchId;
    }

    private boolean coveredBy(Map<String, Long> vectorClock, Map<String, Long> watermark) {
        for (Map.Entry<String, Long> entry : vectorClock.entrySet()) {
            if (entry.getValue() > watermark.getOrDefault(entry.getKey(), 0L)) {
                return false;
            }
        }
        return true;
    }

    private static final class BranchUpdates {
        private final List<UpdateRecord> updates = new ArrayList<>();
        private final Map<String, Long> stateVector = new HashMap<>();
        private String documentHash = "";
    }

    private record UpdateRecord(String opId,
                                String actorId,
                                Map<String, Long> vectorClock,
                                String yjsUpdateBase64,
                                Instant createdAt) {
        static UpdateRecord from(CrdtOperationEnvelope envelope) {
            return new UpdateRecord(
                    envelope.getOpId(),
                    envelope.getActorId(),
                    Map.copyOf(envelope.getVectorClock()),
                    envelope.getYjsUpdateBase64(),
                    envelope.getCreatedAt()
            );
        }

        Map<String, ?> toView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("opId", opId);
            view.put("actorId", actorId);
            view.put("vectorClock", vectorClock);
            view.put("yjsUpdateBase64", yjsUpdateBase64);
            view.put("createdAt", createdAt.toString());
            view.put("forwardingStatus", "stored_for_sidecar_or_browser_rendering");
            return view;
        }
    }
}
