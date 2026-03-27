package com.semantic.sketch.ablation;

import com.semantic.sketch.crdt.Message;

import java.util.Map;

/**
 * Collect phase: checks vector-clock concurrency.
 */
public class ConflictManager {

    public boolean isConcurrent(Message a, Message b) {
        return !(happensBefore(a.getVectorClock(), b.getVectorClock())
                || happensBefore(b.getVectorClock(), a.getVectorClock()));
    }

    private boolean happensBefore(Map<String, Long> a, Map<String, Long> b) {
        boolean strictlyLess = false;
        for (Map.Entry<String, Long> entry : a.entrySet()) {
            long bValue = b.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > bValue) {
                return false;
            }
            if (entry.getValue() < bValue) {
                strictlyLess = true;
            }
        }
        return strictlyLess;
    }
}
