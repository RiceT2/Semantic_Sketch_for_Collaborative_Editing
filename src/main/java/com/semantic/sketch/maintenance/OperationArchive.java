package com.semantic.sketch.maintenance;

import com.semantic.sketch.crdt.Message;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit entry for operations physically removed by maintenance.
 */
public record OperationArchive(String branchId,
                               Message operation,
                               String reason,
                               Instant archivedAt) {
    public OperationArchive {
        branchId = Objects.requireNonNull(branchId, "branchId");
        operation = Objects.requireNonNull(operation, "operation");
        reason = Objects.requireNonNull(reason, "reason");
        archivedAt = Objects.requireNonNull(archivedAt, "archivedAt");
    }
}
