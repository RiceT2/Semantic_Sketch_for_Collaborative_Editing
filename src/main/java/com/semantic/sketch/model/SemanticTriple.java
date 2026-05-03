package com.semantic.sketch.model;

import java.util.Objects;

/**
 * Semantic triple describing operation intent, precondition and impact scope.
 */
public record SemanticTriple(String intent, String precondition, String impactScope) {

    public SemanticTriple {
        intent = Objects.requireNonNull(intent, "intent");
        precondition = Objects.requireNonNull(precondition, "precondition");
        impactScope = Objects.requireNonNull(impactScope, "impactScope");
    }
}
