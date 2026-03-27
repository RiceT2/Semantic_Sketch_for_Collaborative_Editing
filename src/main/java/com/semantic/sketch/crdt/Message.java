package com.semantic.sketch.crdt;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * CRDT operation message with semantic fingerprint extension.
 */
public final class Message {
    private final String opId;
    private final String actorId;
    private final String payload;
    private final Map<String, Long> vectorClock;
    private final long semanticFingerprint;

    public Message(String opId,
                   String actorId,
                   String payload,
                   Map<String, Long> vectorClock,
                   long semanticFingerprint) {
        this.opId = Objects.requireNonNull(opId, "opId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.vectorClock = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(vectorClock, "vectorClock")));
        this.semanticFingerprint = semanticFingerprint;
    }

    public String getOpId() {
        return opId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getPayload() {
        return payload;
    }

    public Map<String, Long> getVectorClock() {
        return vectorClock;
    }

    public long getSemanticFingerprint() {
        return semanticFingerprint;
    }
}
