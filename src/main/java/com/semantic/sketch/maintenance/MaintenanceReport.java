package com.semantic.sketch.maintenance;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Observable result of one maintenance pass.
 */
public record MaintenanceReport(int deletedCount,
                                int compressedCount,
                                Map<String, String> skippedReasons,
                                Instant startedAt,
                                Instant finishedAt) {
    public MaintenanceReport {
        skippedReasons = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(skippedReasons, "skippedReasons")));
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
    }
}
