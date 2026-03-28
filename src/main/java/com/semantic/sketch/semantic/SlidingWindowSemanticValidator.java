package com.semantic.sketch.semantic;

import com.semantic.sketch.crdt.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * TCP-like semantic validation window.
 * Only validates currently affected local semantic fragments.
 */
public class SlidingWindowSemanticValidator {
    private final int windowSize;
    private final int maxHammingDistance;
    private final Deque<Message> activeWindow = new ArrayDeque<>();

    public SlidingWindowSemanticValidator(int windowSize, int maxHammingDistance) {
        this.windowSize = windowSize;
        this.maxHammingDistance = maxHammingDistance;
    }

    public ValidationResult addAndValidate(Message message) {
        List<Message> conflicts = new ArrayList<>();
        for (Message existing : activeWindow) {
            int distance = SimHash64.hammingDistance(existing.getSemanticFingerprint(), message.getSemanticFingerprint());
            if (distance <= maxHammingDistance) {
                conflicts.add(existing);
            }
        }

        activeWindow.addLast(message);
        while (activeWindow.size() > windowSize) {
            activeWindow.removeFirst();
        }

        return new ValidationResult(conflicts.isEmpty(), conflicts);
    }

    public record ValidationResult(boolean valid, List<Message> overlappingMessages) {
    }
}
