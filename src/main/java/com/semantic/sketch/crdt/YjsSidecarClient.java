package com.semantic.sketch.crdt;

import java.util.Map;

/**
 * Sidecar boundary for applying Yjs binary updates and exporting rendered document views.
 */
public interface YjsSidecarClient {
    SidecarMergeResult applyUpdate(String branchId, String yjsUpdateBase64);

    String exportText(String branchId);

    Map<String, ?> exportJson(String branchId);

    String sidecarVersion();

    record SidecarMergeResult(Map<String, Long> stateVector, String documentHash) {
    }
}
