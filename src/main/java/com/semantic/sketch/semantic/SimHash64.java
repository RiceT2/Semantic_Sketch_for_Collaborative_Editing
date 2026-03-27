package com.semantic.sketch.semantic;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 64-bit SimHash for weighted keywords.
 */
public final class SimHash64 {

    private SimHash64() {
    }

    public static long fromWeightedKeywords(Map<String, Double> weightedKeywords) {
        int[] bitAccumulator = new int[64];
        for (Map.Entry<String, Double> entry : weightedKeywords.entrySet()) {
            long h = fnv1a64(entry.getKey());
            int weight = (int) Math.round(entry.getValue() * 1000.0d);
            for (int i = 0; i < 64; i++) {
                if (((h >>> i) & 1L) == 1L) {
                    bitAccumulator[i] += weight;
                } else {
                    bitAccumulator[i] -= weight;
                }
            }
        }

        long fingerprint = 0L;
        for (int i = 0; i < 64; i++) {
            if (bitAccumulator[i] > 0) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static long fnv1a64(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
