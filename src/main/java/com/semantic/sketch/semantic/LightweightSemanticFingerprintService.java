package com.semantic.sketch.semantic;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight fallback implementation.
 * In production, replace tokenize+weights with DJL ONNX embedding + keyword projection.
 */
public class LightweightSemanticFingerprintService implements SemanticFingerprintService {

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
}
