package com.semantic.sketch.model;

import java.util.Objects;

/**
 * Semantic tuple describing an operation's type, intent, target, guard, scope and polarity.
 */
public record SemanticTriple(String operationType,
                             String intent,
                             String target,
                             String precondition,
                             String impactScope,
                             String polarity) {

    public SemanticTriple {
        operationType = Objects.requireNonNull(operationType, "operationType");
        intent = Objects.requireNonNull(intent, "intent");
        target = Objects.requireNonNull(target, "target");
        precondition = Objects.requireNonNull(precondition, "precondition");
        impactScope = Objects.requireNonNull(impactScope, "impactScope");
        polarity = Objects.requireNonNull(polarity, "polarity");
    }

    public SemanticTriple(String intent, String precondition, String impactScope) {
        this("UNKNOWN", intent, "unspecified target", precondition, impactScope, "neutral");
    }
}
