package com.semantic.sketch.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Shadow metadata for conflict resolution replay.
 */
public record ShadowMetadata(long semanticHash,
                             List<SemanticTriple> semanticTriples,
                             Instant timestamp,
                             String actorId) {

    public ShadowMetadata {
        semanticTriples = Collections.unmodifiableList(Objects.requireNonNull(semanticTriples, "semanticTriples"));
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        actorId = Objects.requireNonNull(actorId, "actorId");
    }
}
