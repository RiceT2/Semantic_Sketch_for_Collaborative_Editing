package com.semantic.sketch.crdt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTextCrdtAdapter implements CrdtAdapter {
    private static final String DEFAULT_BRANCH = "master";
    private static final String DEFAULT_TARGET_PATH = "/document";

    private final Map<String, BranchDocument> branches = new ConcurrentHashMap<>();

    @Override
    public synchronized void apply(CrdtOperationEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        BranchDocument branch = branch(envelope.getBranchId());
        String targetPath = normalizeTargetPath(envelope.getTargetPath());
        DocumentState state = branch.documents.computeIfAbsent(targetPath, ignored -> new DocumentState());

        if (!isCausallyReady(branch.stateVector, envelope.getVectorClock(), envelope.getActorId())) {
            branch.pendingOperations.add(PendingOperation.of(envelope, targetPath));
            return;
        }

        applyReadyOperation(branch, state, targetPath, envelope);
        drainPending(branch);
    }

    private void applyReadyOperation(BranchDocument branch, DocumentState state, String targetPath, CrdtOperationEnvelope envelope) {
        switch (envelope.getOperationType()) {
            case INSERT -> applyInsert(state, envelope);
            case DELETE -> applyDelete(state, envelope);
            case REPLACE -> applyReplace(state, envelope);
            default -> throw new IllegalArgumentException("Unsupported in-memory text operation: " + envelope.getOperationType());
        }
        mergeStateVector(branch.stateVector, envelope.getVectorClock());
        branch.appliedOperations.add(OperationRecord.from(envelope, targetPath));
    }

    private void drainPending(BranchDocument branch) {
        boolean progressed;
        do {
            progressed = false;
            for (int i = 0; i < branch.pendingOperations.size(); i++) {
                PendingOperation pending = branch.pendingOperations.get(i);
                if (!isCausallyReady(branch.stateVector, pending.envelope().getVectorClock(), pending.envelope().getActorId())) continue;
                DocumentState state = branch.documents.computeIfAbsent(pending.targetPath(), ignored -> new DocumentState());
                applyReadyOperation(branch, state, pending.targetPath(), pending.envelope());
                branch.pendingOperations.remove(i);
                progressed = true;
                break;
            }
        } while (progressed);
    }

    private boolean isCausallyReady(Map<String, Long> current, Map<String, Long> incoming, String actorId) {
        for (Map.Entry<String, Long> entry : incoming.entrySet()) {
            long seen = current.getOrDefault(entry.getKey(), 0L);
            long required = entry.getValue();
            if (entry.getKey().equals(actorId)) {
                if (required > seen + 1) return false;
            } else if (required > seen) {
                return false;
            }
        }
        return true;
    }

    private void applyInsert(DocumentState state, CrdtOperationEnvelope envelope) {
        InsertAfter op = envelope.getInsertAfter();
        if (op == null) {
            op = fromLegacyInsert(state, envelope);
        }
        state.insert(op);
    }

    private void applyDelete(DocumentState state, CrdtOperationEnvelope envelope) {
        DeleteById op = envelope.getDeleteById();
        if (op != null) {
            state.addTombstone(op.atomId(), envelope.getVectorClock());
            return;
        }
        int from = clampIndex(envelope.getFromIndex(), state.visible().size());
        int to = clampIndex(resolveToIndex(envelope, from), state.visible().size());
        for (int i = from; i < to; i++) {
            state.addTombstone(state.visible().get(i).atomId, envelope.getVectorClock());
        }
    }

    private void applyReplace(DocumentState state, CrdtOperationEnvelope envelope) {
        applyDelete(state, envelope);
        if (envelope.getInsertedText() != null && !envelope.getInsertedText().isEmpty()) {
            applyInsert(state, envelope);
        }
    }

    private InsertAfter fromLegacyInsert(DocumentState state, CrdtOperationEnvelope envelope) {
        int index = clampIndex(envelope.getFromIndex(), state.visible().size());
        List<AtomNode> visible = state.visible();
        TextAtomId left = index == 0 ? null : visible.get(index - 1).atomId;
        TextAtomId right = index >= visible.size() ? null : visible.get(index).atomId;
        long seq = state.nextSeq.merge(envelope.getActorId(), 1L, Long::sum);
        long lamport = envelope.getVectorClock().values().stream().mapToLong(Long::longValue).max().orElse(0L);
        return new InsertAfter(new TextAtomId(envelope.getActorId(), seq), left, right,
                Objects.requireNonNullElse(envelope.getInsertedText(), ""), lamport);
    }

    @Override
    public synchronized String renderDocument(String branchId) {
        BranchDocument branch = branch(branchId);
        DocumentState state = branch.documents.get(DEFAULT_TARGET_PATH);
        if (state != null) {
            return state.render();
        }
        return branch.documents.values().stream().findFirst().map(DocumentState::render).orElse("");
    }

    @Override
    public synchronized Map<String, ?> snapshot(String branchId) {
        String normalizedBranchId = normalizeBranchId(branchId);
        BranchDocument branch = branch(normalizedBranchId);
        Map<String, String> renderedDocuments = new LinkedHashMap<>();
        branch.documents.forEach((path, text) -> renderedDocuments.put(path, text.render()));
        return Map.of("branchId", normalizedBranchId, "document", renderDocument(normalizedBranchId), "documents", renderedDocuments,
                "stateVector", Map.copyOf(branch.stateVector),
                "appliedOperations", branch.appliedOperations.stream().map(OperationRecord::toView).toList());
    }

    @Override
    public synchronized Map<String, Long> stateVector(String branchId) { return Map.copyOf(branch(branchId).stateVector); }

    @Override
    public synchronized void compact(String branchId, Map<String, Long> watermark) {
        if (watermark == null || watermark.isEmpty()) return;
        BranchDocument branch = branch(branchId);
        branch.appliedOperations.removeIf(record -> coveredBy(record.vectorClock(), watermark));
        if (!isObservedByAllKnownReplicas(branch.stateVector, watermark)) return;
        branch.documents.values().forEach(state -> state.compactStableTombstones(watermark));
    }

    private boolean isObservedByAllKnownReplicas(Map<String, Long> stateVector, Map<String, Long> watermark) {
        for (Map.Entry<String, Long> entry : stateVector.entrySet()) {
            if (watermark.getOrDefault(entry.getKey(), 0L) < entry.getValue()) return false;
        }
        return true;
    }

    private int resolveToIndex(CrdtOperationEnvelope envelope, int fromIndex) {
        if (envelope.getToIndex() != null) return envelope.getToIndex();
        String deletedPreview = envelope.getDeletedTextPreview();
        if (deletedPreview != null) return fromIndex + deletedPreview.length();
        return fromIndex;
    }

    private int clampIndex(Integer requestedIndex, int length) {
        if (requestedIndex == null) return length;
        if (requestedIndex < 0) throw new IllegalArgumentException("Text operation index must not be negative");
        return Math.min(requestedIndex, length);
    }
    private BranchDocument branch(String branchId) { return branches.computeIfAbsent(normalizeBranchId(branchId), ignored -> new BranchDocument()); }
    private String normalizeBranchId(String branchId) { return branchId == null || branchId.isBlank() ? DEFAULT_BRANCH : branchId; }
    private String normalizeTargetPath(String targetPath) { return targetPath == null || targetPath.isBlank() ? DEFAULT_TARGET_PATH : targetPath; }
    private void mergeStateVector(Map<String, Long> current, Map<String, Long> incoming) { incoming.forEach((actor, tick) -> current.merge(actor, tick, Math::max)); }
    private static boolean coveredBy(Map<String, Long> vectorClock, Map<String, Long> watermark) {
        for (Map.Entry<String, Long> entry : vectorClock.entrySet()) if (entry.getValue() > watermark.getOrDefault(entry.getKey(), 0L)) return false;
        return true;
    }

    private static final class BranchDocument {
        private final Map<String, DocumentState> documents = new LinkedHashMap<>();
        private final Map<String, Long> stateVector = new HashMap<>();
        private final List<OperationRecord> appliedOperations = new ArrayList<>();
        private final List<PendingOperation> pendingOperations = new ArrayList<>();
    }

    private static final class DocumentState {
        private final List<AtomNode> atoms = new ArrayList<>();
        private final Set<TextAtomId> tombstones = ConcurrentHashMap.newKeySet();
        private final Map<TextAtomId, Map<String, Long>> tombstoneVectorClock = new ConcurrentHashMap<>();
        private final Map<String, Long> nextSeq = new HashMap<>();

        void insert(InsertAfter op) {
            String txt = op.text();
            if (txt.isEmpty()) return;
            int idx = insertionIndex(op);
            atoms.add(idx, new AtomNode(op.atomId(), txt, op.originLeft(), op.originRight(), op.lamport()));
        }

        int insertionIndex(InsertAfter op) {
            int leftBound = 0;
            int rightBound = atoms.size();
            if (op.originLeft() != null) {
                int left = indexOf(op.originLeft());
                if (left >= 0) leftBound = left + 1;
            }
            if (op.originRight() != null) {
                int right = indexOf(op.originRight());
                if (right >= 0) rightBound = right;
            }
            if (leftBound > rightBound) rightBound = leftBound;
            int idx = leftBound;
            while (idx < rightBound && compareOrdering(atoms.get(idx), op) <= 0) idx++;
            return idx;
        }

        private int compareOrdering(AtomNode existing, InsertAfter incoming) {
            int lamport = Long.compare(existing.lamport, incoming.lamport());
            if (lamport != 0) return lamport;
            return existing.atomId.compareTo(incoming.atomId());
        }

        private int indexOf(TextAtomId id) {
            for (int i = 0; i < atoms.size(); i++) if (atoms.get(i).atomId.equals(id)) return i;
            return -1;
        }

        List<AtomNode> visible() {
            return atoms.stream().filter(n -> !tombstones.contains(n.atomId)).toList();
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            for (AtomNode atom : atoms) if (!tombstones.contains(atom.atomId)) sb.append(atom.text);
            return sb.toString();
        }

        void addTombstone(TextAtomId atomId, Map<String, Long> vectorClock) {
            tombstones.add(atomId);
            tombstoneVectorClock.put(atomId, Map.copyOf(vectorClock));
        }

        void compactStableTombstones(Map<String, Long> watermark) {
            tombstoneVectorClock.entrySet().removeIf(entry -> {
                if (!coveredBy(entry.getValue(), watermark)) return false;
                tombstones.remove(entry.getKey());
                atoms.removeIf(atom -> atom.atomId.equals(entry.getKey()));
                return true;
            });
        }
    }

    private record AtomNode(TextAtomId atomId, String text, TextAtomId originLeft, TextAtomId originRight, long lamport) {}

    private record OperationRecord(String opId, String actorId, CrdtOperationType operationType, String targetPath, Integer fromIndex,
                                   Integer toIndex, Map<String, Long> vectorClock, Instant createdAt) {
        static OperationRecord from(CrdtOperationEnvelope envelope, String targetPath) {
            return new OperationRecord(envelope.getOpId(), envelope.getActorId(), envelope.getOperationType(), targetPath,
                    envelope.getFromIndex(), envelope.getToIndex(), Map.copyOf(envelope.getVectorClock()), envelope.getCreatedAt());
        }

        Map<String, ?> toView() {
            return Map.of("opId", opId, "actorId", actorId, "operationType", operationType.toJsonValue(), "targetPath", targetPath,
                    "fromIndex", fromIndex == null ? "" : fromIndex, "toIndex", toIndex == null ? "" : toIndex,
                    "vectorClock", vectorClock, "createdAt", createdAt.toString());
        }
    }

    private record PendingOperation(CrdtOperationEnvelope envelope, String targetPath) {
        static PendingOperation of(CrdtOperationEnvelope envelope, String targetPath) {
            return new PendingOperation(envelope, targetPath);
        }
    }
}
