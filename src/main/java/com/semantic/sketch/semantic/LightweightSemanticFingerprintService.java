package com.semantic.sketch.semantic;

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
    public Map<String, Double> extractWeightedKeywords(String text) {
        Map<String, Double> weights = new HashMap<>();
        Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\W+"))
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
        return List.of(new SemanticTriple(intent, precondition, impactScope));
    }

    @Override
    public SemanticVector extractFeatures(String operation) {
        return encoder.encode(operation, extractTriples(operation));
    }

    private static class LightweightSemanticEncoder implements SemanticEncoder {
        @Override
        public SemanticVector encode(String operation, List<SemanticTriple> triples) {
            Map<String, Double> features = new HashMap<>();
            Arrays.stream((operation == null ? "" : operation).toLowerCase(Locale.ROOT).split("\\W+"))
                    .filter(token -> !token.isBlank())
                    .forEach(token -> features.merge("kw:" + token, 1.0d, Double::sum));

            for (SemanticTriple triple : triples) {
                features.merge("intent:" + triple.intent(), 1.0d, Double::sum);
                features.merge("pre:" + triple.precondition(), 1.0d, Double::sum);
                features.merge("scope:" + triple.impactScope(), 1.0d, Double::sum);
            }
            return new SemanticVector(features);
        }
    }
}
