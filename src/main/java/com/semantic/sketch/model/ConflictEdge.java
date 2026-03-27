package com.semantic.sketch.model;

import com.semantic.sketch.crdt.Message;

public record ConflictEdge(Message left, Message right, double psiWeight) {
}
