package com.semantic.sketch.benchmark;

/**
 * One index-addressed text edit used by the benchmark harness.
 *
 * <p>The shape mirrors the Automerge edit-by-index trace used by crdt-benchmarks:
 * {@code [index, deleteCount]} for deletion and {@code [index, 0, text]} for insertion.</p>
 */
public record CrdtBenchmarkOperation(int index, int deleteCount, String insertedText) {
    public CrdtBenchmarkOperation {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (deleteCount < 0) {
            throw new IllegalArgumentException("deleteCount must be non-negative");
        }
        insertedText = insertedText == null ? "" : insertedText;
    }

    public static CrdtBenchmarkOperation insert(int index, String text) {
        return new CrdtBenchmarkOperation(index, 0, text);
    }

    public static CrdtBenchmarkOperation delete(int index, int deleteCount) {
        return new CrdtBenchmarkOperation(index, deleteCount, "");
    }

    public boolean isInsert() {
        return !insertedText.isEmpty();
    }

    public boolean isDelete() {
        return deleteCount > 0;
    }
}
