package com.semantic.sketch.semantic;

import java.util.Map;

public interface SemanticFingerprintService {
    long fingerprint(String text);

    Map<String, Double> extractWeightedKeywords(String text);
}
