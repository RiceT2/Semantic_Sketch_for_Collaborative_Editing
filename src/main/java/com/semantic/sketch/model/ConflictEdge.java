package com.semantic.sketch.model;

import com.semantic.sketch.crdt.Message;

/**
 * Conflict edge between two operation nodes.
 *
 * @param left one endpoint op node
 * @param right another endpoint op node
 * @param semanticDistance normalized semantic distance in [0, 1]
 * @param conflictWeight pairwise conflict strength (larger = stronger conflict)
 */
public record ConflictEdge(Message left, Message right, double semanticDistance, double conflictWeight) {
}
