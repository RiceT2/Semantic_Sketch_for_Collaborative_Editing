package com.semantic.sketch.crdt;

import java.util.Arrays;
import java.util.Locale;

/**
 * Canonical CRDT operation kinds exchanged by collaborative editing peers.
 */
public enum CrdtOperationType {
    INSERT,
    DELETE,
    REPLACE,
    FORMAT,
    MOVE,
    ANNOTATE,
    UNDO,
    REDO,
    SNAPSHOT,
    COMPACTED;

    /**
     * Returns the stable JSON token for this operation type.
     */
    public String toJsonValue() {
        return name();
    }

    /**
     * Parses a JSON token or string representation into an operation type.
     *
     * <p>The parser is case-insensitive and accepts hyphenated or spaced values
     * by normalizing them to enum-style underscores.</p>
     */
    public static CrdtOperationType fromJsonValue(String value) {
        return fromString(value);
    }

    /**
     * Parses a string representation into an operation type.
     */
    public static CrdtOperationType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CRDT operation type must not be blank");
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown CRDT operation type: " + value));
    }

    @Override
    public String toString() {
        return toJsonValue();
    }
}
