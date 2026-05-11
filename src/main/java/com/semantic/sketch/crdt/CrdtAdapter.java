package com.semantic.sketch.crdt;

import java.util.Map;

/**
 * Boundary between collaboration/session orchestration and the concrete CRDT document store.
 */
public interface CrdtAdapter {
    /**
     * Applies one canonical CRDT operation envelope to its branch-local document state.
     */
    void apply(CrdtOperationEnvelope envelope);

    /**
     * Renders the best available document text for a branch.
     */
    String renderDocument(String branchId);

    /**
     * Returns a serializable branch snapshot for clients or diagnostics.
     */
    Map<String, ?> snapshot(String branchId);

    /**
     * Returns the branch state vector known to this adapter.
     */
    Map<String, Long> stateVector(String branchId);

    /**
     * Compacts adapter-owned history that is covered by the supplied watermark.
     */
    void compact(String branchId, Map<String, Long> watermark);
}
