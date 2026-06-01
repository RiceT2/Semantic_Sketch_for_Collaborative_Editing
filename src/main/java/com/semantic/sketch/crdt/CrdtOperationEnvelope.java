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
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String DEFAULT_ENCODING = "json-crdt-patch-v1";

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
    private final String crdtPayload;
    private final String encoding;
    private final int schemaVersion;
    private final long semanticFingerprint;
    private final List<SemanticTriple> semanticTriples;
    private final InsertAfter insertAfter;
    private final DeleteById deleteById;
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
                                 String crdtPayload,
                                 String encoding,
                                 int schemaVersion,
                                 long semanticFingerprint,
                                 List<SemanticTriple> semanticTriples,
                                 Instant createdAt) {
        this(opId, actorId, branchId, operationType, vectorClock, targetPath, fromIndex, toIndex, insertedText,
                deletedTextPreview, intentText, yjsUpdateBase64, crdtPayload, encoding, schemaVersion,
                semanticFingerprint, semanticTriples, null, null, createdAt);
    }

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
        this(opId, actorId, branchId, operationType, vectorClock, targetPath, fromIndex, toIndex,
                insertedText, deletedTextPreview, intentText, yjsUpdateBase64,
                /* crdtPayload */ null,
                /* encoding */ DEFAULT_ENCODING,
                /* schemaVersion */ CURRENT_SCHEMA_VERSION,
                semanticFingerprint,
                semanticTriples,
                /* insertAfter */ null,
                /* deleteById */ null,
                createdAt);
    }

    public CrdtOperationEnvelope(String opId,
                                 String actorId,
                                 String branchId,
                                 CrdtOperationType operationType,
                                 Object rawVectorClock,
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
        this(opId, actorId, branchId, operationType, normalizeVectorClockFromObject(rawVectorClock), targetPath, fromIndex, toIndex,
                insertedText, deletedTextPreview, intentText, yjsUpdateBase64,
                semanticFingerprint, semanticTriples, createdAt);
    }

    private static Map<String, Long> normalizeVectorClock(Map<?, ?> raw) {
        if (raw == null) return Map.of();
        Map<String, Long> map = new HashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (k == null) continue;
            String ks = String.valueOf(k);
            long lv;
            if (v instanceof Number n) {
                lv = n.longValue();
            } else {
                try {
                    lv = Long.parseLong(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                    lv = 0L;
                }
            }
            map.put(ks, lv);
        }
        return map;
    }

    private static Map<String, Long> normalizeVectorClockFromObject(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        return normalizeVectorClock(m);
    }

    public static CrdtOperationEnvelope fromMessage(Message message,
                                                     String branchId,
                                                     CrdtOperationType operationType) {
         Objects.requireNonNull(message, "message");
         if (message.getSchemaVersion() != CURRENT_SCHEMA_VERSION) {
             throw new IllegalArgumentException("Unsupported schemaVersion: " + message.getSchemaVersion());
         }
         if (message.getCrdtPayload() == null || message.getCrdtPayload().isBlank()) {
             throw new IllegalArgumentException("crdtPayload is required");
         }
        return new CrdtOperationEnvelope(
                message.getOpId(),
                message.getActorId(),
                branchId,
                operationType,
                message.getVectorClock(),
                /* targetPath */ null,
                /* fromIndex */ null,
                /* toIndex */ null,
                /* insertedText */ null,
                /* deletedTextPreview */ null,
                /* intentText */ message.getSemanticPayload(),
                /* yjsUpdateBase64 */ null,
                /* crdtPayload */ message.getCrdtPayload(),
                /* encoding */ message.getEncoding(),
                /* schemaVersion */ message.getSchemaVersion(),
                /* semanticFingerprint */ message.getSemanticFingerprint(),
                /* semanticTriples */ message.getSemanticTriples(),
                /* insertAfter */ null,
                /* deleteById */ null,
                Instant.now()
        );
    }

    public Message toMessage() {
        return Message.fromEnvelope(this);
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

    public String getCrdtPayload() {
        return crdtPayload;
    }

    public String getEncoding() {
        return encoding;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public long getSemanticFingerprint() {
        return semanticFingerprint;
    }

    public List<SemanticTriple> getSemanticTriples() {
        return semanticTriples;
    }

    public InsertAfter getInsertAfter() {
        return insertAfter;
    }

    public DeleteById getDeleteById() {
        return deleteById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

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
                                 String crdtPayload,
                                 String encoding,
                                 int schemaVersion,
                                 long semanticFingerprint,
                                 List<SemanticTriple> semanticTriples,
                                 InsertAfter insertAfter,
                                 DeleteById deleteById,
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
        this.crdtPayload = crdtPayload;
        this.encoding = Objects.requireNonNullElse(encoding, DEFAULT_ENCODING);
        this.schemaVersion = schemaVersion;
        this.semanticFingerprint = semanticFingerprint;
        this.semanticTriples = List.copyOf(Objects.requireNonNullElse(semanticTriples, List.of()));
        this.insertAfter = insertAfter;
        this.deleteById = deleteById;
        this.createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }
}
