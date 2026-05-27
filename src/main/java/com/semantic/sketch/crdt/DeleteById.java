package com.semantic.sketch.crdt;

import java.util.Objects;

/**
 * Delete operation that targets existing atom ids.
 */
public record DeleteById(TextAtomId atomId) {
    public DeleteById {
        Objects.requireNonNull(atomId, "atomId");
    }
}
