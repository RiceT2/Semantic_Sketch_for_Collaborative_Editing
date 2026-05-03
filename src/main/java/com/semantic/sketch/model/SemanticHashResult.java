package com.semantic.sketch.model;

import com.semantic.sketch.semantic.SimHash64;

/**
 * Comparable semantic hash projection output.
 */
public record SemanticHashResult(long hash, int dimensions) {

    public int hammingDistanceTo(SemanticHashResult other) {
        return SimHash64.hammingDistance(hash, other.hash);
    }
}
