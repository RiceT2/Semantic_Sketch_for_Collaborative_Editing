package com.semantic.sketch.crdt;

import java.util.Objects;

/**
 * Insert operation expressed with stable anchors and atom id.
 */
public record InsertAfter(TextAtomId atomId,
                          TextAtomId originLeft,
                          TextAtomId originRight,
                          String text,
                          long lamport) {
    public InsertAfter {
        Objects.requireNonNull(atomId, "atomId");
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        if (lamport < 0) {
            throw new IllegalArgumentException("lamport must be non-negative");
        }
    }
}
