package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;
import com.semantic.sketch.model.ConflictEdge;
import com.semantic.sketch.model.FactorGraph;
import com.semantic.sketch.semantic.SimHash64;

import java.util.ArrayList;
import java.util.List;

/**
 * Modeling phase: edge weight ψ is derived from fingerprint distance.
 */
public class FactorGraphBuilder {

    public FactorGraph build(List<Message> conflictSet) {
        List<ConflictEdge> edges = new ArrayList<>();
        for (int i = 0; i < conflictSet.size(); i++) {
            for (int j = i + 1; j < conflictSet.size(); j++) {
                Message left = conflictSet.get(i);
                Message right = conflictSet.get(j);
                int distance = SimHash64.hammingDistance(left.getSemanticFingerprint(), right.getSemanticFingerprint());
                double psi = 1.0 / (1 + distance);
                edges.add(new ConflictEdge(left, right, psi));
            }
        }
        return new FactorGraph(conflictSet, edges);
    }
}
