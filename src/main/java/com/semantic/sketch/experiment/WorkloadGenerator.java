package com.semantic.sketch.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates deterministic synthetic collaborative operations for reproducible ablation experiments.
 */
public class WorkloadGenerator {

    private static final String[] CLUSTERS = {"layout", "table", "title", "comment", "shape"};

    public List<ExperimentalOperation> generate(int operations, long seed) {
        Random random = new Random(seed);
        List<ExperimentalOperation> result = new ArrayList<>(operations);
        for (int i = 0; i < operations; i++) {
            String cluster = CLUSTERS[random.nextInt(CLUSTERS.length)];
            int variant = random.nextInt(4);
            String text = switch (cluster) {
                case "layout" -> "adjust page margin and spacing variant " + variant;
                case "table" -> "update table column width and header variant " + variant;
                case "title" -> "rewrite title semantics and subtitle variant " + variant;
                case "comment" -> "resolve comment thread mention owner variant " + variant;
                default -> "move shape anchor and connector variant " + variant;
            };
            result.add(new ExperimentalOperation("op-" + i, text, cluster));
        }
        return result;
    }
}
