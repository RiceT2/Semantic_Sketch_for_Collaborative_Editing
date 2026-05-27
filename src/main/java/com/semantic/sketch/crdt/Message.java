package com.semantic.sketch.crdt;

import com.semantic.sketch.model.SemanticTriple;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CRDT operation message with semantic fingerprint extension.
 */
public final class Message {
    private final String opId;
    private final String actorId;
    private final String payload;
    private final String crdtPayload;
    private final String semanticPayload;
    private final List<SemanticTriple> semanticTriples;
    private final String encoding;
    private final int schemaVersion;
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
        this.crdtPayload = payload;
        this.semanticPayload = null;
        this.semanticTriples = List.of();
        this.encoding = CrdtOperationEnvelope.DEFAULT_ENCODING;
        this.schemaVersion = CrdtOperationEnvelope.CURRENT_SCHEMA_VERSION;
        this.vectorClock = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(vectorClock, "vectorClock")));
        this.semanticFingerprint = semanticFingerprint;
    }

    public Message(String opId,
                   String actorId,
                   String crdtPayload,
                   String semanticPayload,
                   List<SemanticTriple> semanticTriples,
                   String encoding,
                   int schemaVersion,
                   Map<String, Long> vectorClock,
                   long semanticFingerprint) {
        this.opId = Objects.requireNonNull(opId, "opId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.crdtPayload = Objects.requireNonNull(crdtPayload, "crdtPayload");
        this.semanticPayload = semanticPayload;
        this.semanticTriples = List.copyOf(Objects.requireNonNullElse(semanticTriples, List.of()));
        this.encoding = Objects.requireNonNullElse(encoding, CrdtOperationEnvelope.DEFAULT_ENCODING);
        this.schemaVersion = schemaVersion;
        this.payload = semanticPayload != null ? semanticPayload : crdtPayload;
        this.vectorClock = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(vectorClock, "vectorClock")));
        this.semanticFingerprint = semanticFingerprint;
    }

    public Message(CrdtOperationEnvelope envelope) {
        this(
                Objects.requireNonNull(envelope, "envelope").getOpId(),
                envelope.getActorId(),
                Objects.requireNonNull(envelope.getCrdtPayload(), "envelope.crdtPayload"),
                envelope.getIntentText(),
                envelope.getSemanticTriples(),
                envelope.getEncoding(),
                envelope.getSchemaVersion(),
                envelope.getVectorClock(),
                envelope.getSemanticFingerprint()
        );
    }

    public static Message fromEnvelope(CrdtOperationEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.getSchemaVersion() != CrdtOperationEnvelope.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + envelope.getSchemaVersion());
        }
        return new Message(envelope);
    }

    public CrdtOperationEnvelope toEnvelope(String branchId, CrdtOperationType operationType) {
        return CrdtOperationEnvelope.fromMessage(this, branchId, operationType);
    }

    public String getCrdtPayload() {
        return crdtPayload;
    }

    public String getSemanticPayload() {
        return semanticPayload;
    }

    public List<SemanticTriple> getSemanticTriples() {
        return semanticTriples;
    }

    public String getEncoding() {
        return encoding;
    }

    public int getSchemaVersion() {
        return schemaVersion;
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
