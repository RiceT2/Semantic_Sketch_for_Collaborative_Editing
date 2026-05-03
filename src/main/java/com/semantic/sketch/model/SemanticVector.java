package com.semantic.sketch.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encoder-agnostic semantic feature vector.
 */
public record SemanticVector(Map<String, Double> features) {

    public SemanticVector {
        features = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(features, "features")));
    }
}
