package com.semantic.sketch;

import com.semantic.sketch.ablation.MergeDecision;

/**
 * Computes intent residue (R) for merge outcomes.
 */
public class IntentResidueCalculator {

    /**
     * Placeholder metric: acceptance ratio in [0,1].
     */
    public double calculate(MergeDecision decision) {
        int accepted = decision.acceptedOps().size();
        int rejected = decision.rejectedOps().size();
        int total = accepted + rejected;
        if (total == 0) {
            return 1.0d;
        }
        return accepted / (double) total;
    }
}
