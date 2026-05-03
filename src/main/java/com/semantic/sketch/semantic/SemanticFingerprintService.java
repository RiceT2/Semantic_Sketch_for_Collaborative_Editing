package com.semantic.sketch.semantic;

import com.semantic.sketch.model.SemanticTriple;
import com.semantic.sketch.model.SemanticVector;

import java.util.List;
import java.util.Map;

public interface SemanticFingerprintService {
    long fingerprint(String text);

    Map<String, Double> extractWeightedKeywords(String text);

    List<SemanticTriple> extractTriples(String operation);

    SemanticVector extractFeatures(String operation);
}
