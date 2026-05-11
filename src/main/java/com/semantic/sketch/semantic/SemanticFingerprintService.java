package com.semantic.sketch.semantic;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.model.SemanticVector;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public interface SemanticFingerprintService {
    long fingerprint(String text);

    default long fingerprint(CrdtOperationEnvelope envelope) {
        return fingerprint(toSemanticInput(envelope));
    }

    Map<String, Double> extractWeightedKeywords(String text);

    List<SemanticTriple> extractTriples(String operation);

    default List<SemanticTriple> extractTriples(CrdtOperationEnvelope envelope) {
        return extractTriples(toSemanticInput(envelope));
    }

    SemanticVector extractFeatures(String operation);

    default SemanticVector extractFeatures(CrdtOperationEnvelope envelope) {
        return extractFeatures(toSemanticInput(envelope));
    }

    static String toSemanticInput(CrdtOperationEnvelope envelope) {
        if (envelope == null) {
            return "";
        }
        CrdtOperationType operationType = envelope.getOperationType();
        StringJoiner input = new StringJoiner(" ");
        addIfPresent(input, envelope.getIntentText());
        if (operationType == CrdtOperationType.INSERT) {
            addIfPresent(input, envelope.getInsertedText());
        } else if (operationType == CrdtOperationType.DELETE) {
            addIfPresent(input, envelope.getDeletedTextPreview());
        } else if (operationType == CrdtOperationType.REPLACE) {
            addIfPresent(input, envelope.getDeletedTextPreview());
            addIfPresent(input, envelope.getInsertedText());
        } else {
            addIfPresent(input, envelope.getInsertedText());
            addIfPresent(input, envelope.getDeletedTextPreview());
        }
        addIfPresent(input, envelope.getTargetPath());
        return input.toString();
    }

    private static void addIfPresent(StringJoiner input, String value) {
        if (value != null && !value.isBlank()) {
            input.add(value.trim());
        }
    }
}
