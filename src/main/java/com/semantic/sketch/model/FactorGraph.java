package com.semantic.sketch.model;

import com.semantic.sketch.crdt.Message;

import java.util.List;

public record FactorGraph(List<Message> nodes, List<ConflictEdge> edges) {
}
