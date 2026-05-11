package com.semantic.sketch.semantic;

import com.semantic.sketch.crdt.CrdtOperationEnvelope;
import com.semantic.sketch.crdt.CrdtOperationType;
import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.model.SemanticVector;

import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight fallback implementation.
 * In production, replace tokenize+weights with DJL ONNX embedding + keyword projection.
 */
public class LightweightSemanticFingerprintService implements SemanticFingerprintService {
    private final SemanticEncoder encoder;

    public LightweightSemanticFingerprintService() {
        this(new LightweightSemanticEncoder());
    }

    public LightweightSemanticFingerprintService(SemanticEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public long fingerprint(String text) {
        return SimHash64.fromWeightedKeywords(extractWeightedKeywords(text));
    }

    @Override
    public long fingerprint(CrdtOperationEnvelope envelope) {
        if (envelope == null) {
            return fingerprint("");
        }
        Map<String, Double> weights = new HashMap<>(extractWeightedKeywords(SemanticFingerprintService.toSemanticInput(envelope)));
        weights.merge("op:" + envelope.getOperationType().toJsonValue().toLowerCase(Locale.ROOT), 3.0d, Double::sum);
        weights.merge("polarity:" + polarity(envelope.getOperationType()), 2.0d, Double::sum);
        return SimHash64.fromWeightedKeywords(weights);
    }

    @Override
    public Map<String, Double> extractWeightedKeywords(String text) {
        Map<String, Double> weights = new HashMap<>();
        Arrays.stream((text == null ? "" : text).toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(token -> !token.isBlank())
                .forEach(token -> weights.merge(token, 1.0d, Double::sum));
        return weights;
    }

    @Override
    public List<SemanticTriple> extractTriples(String operation) {
        String normalized = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
        String intent = normalized.isEmpty() ? "unknown" : normalized.split("\\s+")[0];
        String precondition = normalized.contains("if ") ? "conditional" : "always";
        String impactScope = normalized.contains("global") ? "global" : "local";
        String operationType = inferOperationType(normalized).toJsonValue();
        return List.of(new SemanticTriple(operationType, intent, "unspecified target", precondition, impactScope,
                polarity(CrdtOperationType.fromString(operationType))));
    }

    @Override
    public List<SemanticTriple> extractTriples(CrdtOperationEnvelope envelope) {
        if (envelope == null) {
            return extractTriples("");
        }
        String semanticInput = SemanticFingerprintService.toSemanticInput(envelope);
        String normalized = semanticInput.toLowerCase(Locale.ROOT);
        String intent = firstPresent(envelope.getIntentText(), firstToken(normalized), "unknown");
        String target = firstPresent(envelope.getTargetPath(), "unspecified target");
        String precondition = normalized.contains("if ") ? "conditional" : "always";
        String impactScope = scopeFor(envelope, normalized);
        return List.of(new SemanticTriple(
                envelope.getOperationType().toJsonValue(),
                intent,
                target,
                precondition,
                impactScope,
                polarity(envelope.getOperationType())
        ));
    }

    @Override
    public SemanticVector extractFeatures(String operation) {
        return encoder.encode(operation, extractTriples(operation));
    }

    @Override
    public SemanticVector extractFeatures(CrdtOperationEnvelope envelope) {
        return encoder.encode(SemanticFingerprintService.toSemanticInput(envelope), extractTriples(envelope));
    }

    private static CrdtOperationType inferOperationType(String normalized) {
        if (normalized.startsWith("insert")) {
            return CrdtOperationType.INSERT;
        }
        if (normalized.startsWith("delete") || normalized.startsWith("remove")) {
            return CrdtOperationType.DELETE;
        }
        if (normalized.startsWith("replace")) {
            return CrdtOperationType.REPLACE;
        }
        return CrdtOperationType.REPLACE;
    }

    private static String polarity(CrdtOperationType operationType) {
        return switch (operationType) {
            case INSERT, ANNOTATE, REDO, SNAPSHOT -> "positive";
            case DELETE, UNDO, COMPACTED -> "negative";
            case REPLACE, FORMAT, MOVE -> "neutral";
        };
    }

    private static String scopeFor(CrdtOperationEnvelope envelope, String normalized) {
        if (normalized.contains("global")) {
            return "global";
        }
        String targetPath = envelope.getTargetPath();
        if (targetPath == null || targetPath.isBlank()) {
            return "local";
        }
        String normalizedTarget = targetPath.toLowerCase(Locale.ROOT);
        if (normalizedTarget.contains("document") || normalizedTarget.equals("/") || normalizedTarget.equals("/doc")) {
            return "document";
        }
        return "local";
    }

    private static String firstToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().split("\\s+", 2)[0];
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "unknown";
    }

    private static class LightweightSemanticEncoder implements SemanticEncoder {
        @Override
        public SemanticVector encode(String operation, List<SemanticTriple> triples) {
            Map<String, Double> features = new HashMap<>();
            Arrays.stream((operation == null ? "" : operation).toLowerCase(Locale.ROOT).split("\\W+"))
                    .filter(token -> !token.isBlank())
                    .forEach(token -> features.merge("kw:" + token, 1.0d, Double::sum));

            for (SemanticTriple triple : triples) {
                features.merge("op:" + triple.operationType(), 1.0d, Double::sum);
                features.merge("intent:" + triple.intent(), 1.0d, Double::sum);
                features.merge("target:" + triple.target(), 1.0d, Double::sum);
                features.merge("pre:" + triple.precondition(), 1.0d, Double::sum);
                features.merge("scope:" + triple.impactScope(), 1.0d, Double::sum);
                features.merge("polarity:" + triple.polarity(), 1.0d, Double::sum);
            }
            return new SemanticVector(features);
        }
    }
}
