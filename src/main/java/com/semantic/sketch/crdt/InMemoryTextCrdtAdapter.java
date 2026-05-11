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
 * Simple in-memory text CRDT adapter for the simulator and tests.
 *
 * <p>This adapter intentionally implements deterministic text splices rather than a production CRDT
 * algorithm. It provides the same boundary that a real CRDT backend would implement, while preserving
 * the envelope fields used by browser/editor clients: targetPath, fromIndex, toIndex, insertedText and
 * deletedTextPreview.</p>
 */
public class InMemoryTextCrdtAdapter implements CrdtAdapter {
    private static final String DEFAULT_BRANCH = "master";
    private static final String DEFAULT_TARGET_PATH = "/document";

    private final Map<String, BranchDocument> branches = new ConcurrentHashMap<>();

    @Override
    public synchronized void apply(CrdtOperationEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        BranchDocument branch = branch(envelope.getBranchId());
        String targetPath = normalizeTargetPath(envelope.getTargetPath());
        StringBuilder document = branch.documents.computeIfAbsent(targetPath, ignored -> new StringBuilder());

        switch (envelope.getOperationType()) {
            case INSERT -> applyInsert(document, envelope);
            case DELETE -> applyDelete(document, envelope);
            case REPLACE -> applyReplace(document, envelope);
            default -> throw new IllegalArgumentException("Unsupported in-memory text operation: " + envelope.getOperationType());
        }

        mergeStateVector(branch.stateVector, envelope.getVectorClock());
        branch.appliedOperations.add(OperationRecord.from(envelope, targetPath));
    }

    @Override
    public synchronized String renderDocument(String branchId) {
        BranchDocument branch = branch(branchId);
        StringBuilder defaultDocument = branch.documents.get(DEFAULT_TARGET_PATH);
        if (defaultDocument != null) {
            return defaultDocument.toString();
        }
        return branch.documents.values().stream()
                .findFirst()
                .map(StringBuilder::toString)
                .orElse("");
    }

    @Override
    public synchronized Map<String, ?> snapshot(String branchId) {
        String normalizedBranchId = normalizeBranchId(branchId);
        BranchDocument branch = branch(normalizedBranchId);
        Map<String, String> renderedDocuments = new LinkedHashMap<>();
        branch.documents.forEach((path, text) -> renderedDocuments.put(path, text.toString()));
        return Map.of(
                "branchId", normalizedBranchId,
                "document", renderDocument(normalizedBranchId),
                "documents", renderedDocuments,
                "stateVector", Map.copyOf(branch.stateVector),
                "appliedOperations", branch.appliedOperations.stream().map(OperationRecord::toView).toList()
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
        BranchDocument branch = branch(branchId);
        branch.appliedOperations.removeIf(record -> coveredBy(record.vectorClock(), watermark));
    }

    private void applyInsert(StringBuilder document, CrdtOperationEnvelope envelope) {
        int index = clampIndex(envelope.getFromIndex(), document.length());
        document.insert(index, Objects.requireNonNullElse(envelope.getInsertedText(), ""));
    }

    private void applyDelete(StringBuilder document, CrdtOperationEnvelope envelope) {
        int fromIndex = clampIndex(envelope.getFromIndex(), document.length());
        int toIndex = clampIndex(resolveToIndex(envelope, fromIndex), document.length());
        if (toIndex < fromIndex) {
            throw new IllegalArgumentException("DELETE toIndex must be greater than or equal to fromIndex");
        }
        document.delete(fromIndex, toIndex);
    }

    private void applyReplace(StringBuilder document, CrdtOperationEnvelope envelope) {
        int fromIndex = clampIndex(envelope.getFromIndex(), document.length());
        int toIndex = clampIndex(resolveToIndex(envelope, fromIndex), document.length());
        if (toIndex < fromIndex) {
            throw new IllegalArgumentException("REPLACE toIndex must be greater than or equal to fromIndex");
        }
        document.replace(fromIndex, toIndex, Objects.requireNonNullElse(envelope.getInsertedText(), ""));
    }

    private int resolveToIndex(CrdtOperationEnvelope envelope, int fromIndex) {
        if (envelope.getToIndex() != null) {
            return envelope.getToIndex();
        }
        String deletedPreview = envelope.getDeletedTextPreview();
        if (deletedPreview != null) {
            return fromIndex + deletedPreview.length();
        }
        return fromIndex;
    }

    private int clampIndex(Integer requestedIndex, int length) {
        if (requestedIndex == null) {
            return length;
        }
        if (requestedIndex < 0) {
            throw new IllegalArgumentException("Text operation index must not be negative");
        }
        return Math.min(requestedIndex, length);
    }

    private BranchDocument branch(String branchId) {
        return branches.computeIfAbsent(normalizeBranchId(branchId), ignored -> new BranchDocument());
    }

    private String normalizeBranchId(String branchId) {
        return branchId == null || branchId.isBlank() ? DEFAULT_BRANCH : branchId;
    }

    private String normalizeTargetPath(String targetPath) {
        return targetPath == null || targetPath.isBlank() ? DEFAULT_TARGET_PATH : targetPath;
    }

    private void mergeStateVector(Map<String, Long> current, Map<String, Long> incoming) {
        incoming.forEach((actor, tick) -> current.merge(actor, tick, Math::max));
    }

    private boolean coveredBy(Map<String, Long> vectorClock, Map<String, Long> watermark) {
        for (Map.Entry<String, Long> entry : vectorClock.entrySet()) {
            if (entry.getValue() > watermark.getOrDefault(entry.getKey(), 0L)) {
                return false;
            }
        }
        return true;
    }

    private static final class BranchDocument {
        private final Map<String, StringBuilder> documents = new LinkedHashMap<>();
        private final Map<String, Long> stateVector = new HashMap<>();
        private final List<OperationRecord> appliedOperations = new ArrayList<>();
    }

    private record OperationRecord(String opId,
                                   String actorId,
                                   CrdtOperationType operationType,
                                   String targetPath,
                                   Integer fromIndex,
                                   Integer toIndex,
                                   Map<String, Long> vectorClock,
                                   Instant createdAt) {
        static OperationRecord from(CrdtOperationEnvelope envelope, String targetPath) {
            return new OperationRecord(
                    envelope.getOpId(),
                    envelope.getActorId(),
                    envelope.getOperationType(),
                    targetPath,
                    envelope.getFromIndex(),
                    envelope.getToIndex(),
                    Map.copyOf(envelope.getVectorClock()),
                    envelope.getCreatedAt()
            );
        }

        Map<String, ?> toView() {
            return Map.of(
                    "opId", opId,
                    "actorId", actorId,
                    "operationType", operationType.toJsonValue(),
                    "targetPath", targetPath,
                    "fromIndex", fromIndex == null ? "" : fromIndex,
                    "toIndex", toIndex == null ? "" : toIndex,
                    "vectorClock", vectorClock,
                    "createdAt", createdAt.toString()
            );
        }
    }
}
