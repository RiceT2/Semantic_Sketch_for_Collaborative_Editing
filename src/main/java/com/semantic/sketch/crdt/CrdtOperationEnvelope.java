package com.semantic.sketch.crdt;

import com.semantic.sketch.model.SemanticTriple;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rich CRDT operation envelope carrying transport, semantic and Yjs metadata.
 */
public final class CrdtOperationEnvelope {
    private final String opId;
    private final String actorId;
    private final String branchId;
    private final CrdtOperationType operationType;
    private final Map<String, Long> vectorClock;
    private final String targetPath;
    private final Integer fromIndex;
    private final Integer toIndex;
    private final String insertedText;
    private final String deletedTextPreview;
    private final String intentText;
    private final String yjsUpdateBase64;
    private final long semanticFingerprint;
    private final List<SemanticTriple> semanticTriples;
    private final Instant createdAt;

    public CrdtOperationEnvelope(String opId,
                                 String actorId,
                                 String branchId,
                                 CrdtOperationType operationType,
                                 Map<String, Long> vectorClock,
                                 String targetPath,
                                 Integer fromIndex,
                                 Integer toIndex,
                                 String insertedText,
                                 String deletedTextPreview,
                                 String intentText,
                                 String yjsUpdateBase64,
                                 long semanticFingerprint,
                                 List<SemanticTriple> semanticTriples,
                                 Instant createdAt) {
        this.opId = Objects.requireNonNull(opId, "opId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.branchId = branchId;
        this.operationType = Objects.requireNonNull(operationType, "operationType");
        this.vectorClock = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(vectorClock, "vectorClock")));
        this.targetPath = targetPath;
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
        this.insertedText = insertedText;
        this.deletedTextPreview = deletedTextPreview;
        this.intentText = intentText;
        this.yjsUpdateBase64 = yjsUpdateBase64;
        this.semanticFingerprint = semanticFingerprint;
        this.semanticTriples = List.copyOf(Objects.requireNonNullElse(semanticTriples, List.of()));
        this.createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    public static CrdtOperationEnvelope fromMessage(Message message,
                                                    String branchId,
                                                    CrdtOperationType operationType) {
        Objects.requireNonNull(message, "message");
        return new CrdtOperationEnvelope(
                message.getOpId(),
                message.getActorId(),
                branchId,
                operationType,
                message.getVectorClock(),
                null,
                null,
                null,
                operationType == CrdtOperationType.INSERT ? message.getPayload() : null,
                operationType == CrdtOperationType.DELETE ? message.getPayload() : null,
                message.getPayload(),
                null,
                message.getSemanticFingerprint(),
                List.of(),
                Instant.now()
        );
    }

    public Message toMessage() {
        return Message.fromEnvelope(this);
    }

    String toLegacyPayload() {
        if (insertedText != null) {
            return insertedText;
        }
        if (intentText != null) {
            return intentText;
        }
        if (yjsUpdateBase64 != null) {
            return yjsUpdateBase64;
        }
        if (deletedTextPreview != null) {
            return deletedTextPreview;
        }
        return operationType.toJsonValue();
    }

    public String getOpId() {
        return opId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getBranchId() {
        return branchId;
    }

    public CrdtOperationType getOperationType() {
        return operationType;
    }

    public Map<String, Long> getVectorClock() {
        return vectorClock;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public Integer getFromIndex() {
        return fromIndex;
    }

    public Integer getToIndex() {
        return toIndex;
    }

    public String getInsertedText() {
        return insertedText;
    }

    public String getDeletedTextPreview() {
        return deletedTextPreview;
    }

    public String getIntentText() {
        return intentText;
    }

    public String getYjsUpdateBase64() {
        return yjsUpdateBase64;
    }

    public long getSemanticFingerprint() {
        return semanticFingerprint;
    }

    public List<SemanticTriple> getSemanticTriples() {
        return semanticTriples;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
