package com.semantic.sketch.benchmark;

record BenchmarkTraceEvent(
        String opId,
        String actorId,
        long seq,
        String mode,
        String opType,
        int index,
        String text,
        int deleteLen,
        long logicalTs
) {
}
