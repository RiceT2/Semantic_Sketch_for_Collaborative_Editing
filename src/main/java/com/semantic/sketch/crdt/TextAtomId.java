package com.semantic.sketch.crdt;

import java.util.Objects;

/**
 * Stable element identifier for text atoms/fragments.
 */
public record TextAtomId(String clientId, long seq) implements Comparable<TextAtomId> {

    public TextAtomId {
        Objects.requireNonNull(clientId, "clientId");
        if (clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive");
        }
    }

    @Override
    public int compareTo(TextAtomId other) {
        int actorCompare = clientId.compareTo(other.clientId);
        if (actorCompare != 0) {
            return actorCompare;
        }
        return Long.compare(seq, other.seq);
    }
}
