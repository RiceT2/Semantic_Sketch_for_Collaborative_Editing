package com.semantic.sketch.semantic;

import com.semantic.sketch.ablation.MergeDecision;
import com.semantic.sketch.crdt.Message;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates the normalized intent residual score R for an inferred merge plan.
 *
 * <p>R is dimensionless and bounded to [0, 1]. Higher values mean more weighted
 * author intent is preserved by the plan. Orchestrators should trigger human
 * arbitration when {@code R < tau}.</p>
 */
public class IntentResidualCalculator {
    public static final double DEFAULT_WEIGHT = 1.0d;

    public double calculate(ResidualInput input) {
        Objects.requireNonNull(input, "input");
        if (input.executionStates().isEmpty()) {
            return 1.0d;
        }

        double weightedCompletion = 0.0d;
        double totalWeight = 0.0d;
        for (Map.Entry<String, ExecutionState> entry : input.executionStates().entrySet()) {
            String opId = entry.getKey();
            double operationWeight = operationWeight(
                    input.semanticWeights().getOrDefault(opId, DEFAULT_WEIGHT),
                    input.entropyWeights().getOrDefault(opId, DEFAULT_WEIGHT),
                    input.roleWeights().getOrDefault(opId, DEFAULT_WEIGHT)
            );
            weightedCompletion += entry.getValue().completion() * operationWeight;
            totalWeight += operationWeight;
        }
        if (totalWeight == 0.0d) {
            return 1.0d;
        }
        return clamp01(weightedCompletion / totalWeight);
    }

    public double calculate(MergeDecision decision) {
        return calculate(inputFrom(decision));
    }

    public ResidualInput inputFrom(MergeDecision decision) {
        Objects.requireNonNull(decision, "decision");
        Map<String, ExecutionState> executionStates = new LinkedHashMap<>();
        for (Message accepted : decision.acceptedOps()) {
            executionStates.put(accepted.getOpId(), ExecutionState.EXECUTED);
        }
        for (Message rejected : decision.rejectedOps()) {
            executionStates.put(rejected.getOpId(), ExecutionState.SKIPPED);
        }

        Map<String, Double> semanticWeights = new HashMap<>();
        for (MergeDecision.ScoreStep step : decision.scoreSteps()) {
            semanticWeights.put(step.opId(), clamp01(step.unaryPotential()));
        }
        return new ResidualInput(executionStates, semanticWeights, Map.of(), Map.of());
    }

    private double operationWeight(double semanticWeight, double entropyWeight, double roleWeight) {
        return (clamp01(semanticWeight) + clamp01(entropyWeight) + clamp01(roleWeight)) / 3.0d;
    }

    private double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    public enum ExecutionState {
        EXECUTED(1.0d),
        PARTIAL(0.5d),
        SKIPPED(0.0d);

        private final double completion;

        ExecutionState(double completion) {
            this.completion = completion;
        }

        public double completion() {
            return completion;
        }
    }

    public record ResidualInput(Map<String, ExecutionState> executionStates,
                                Map<String, Double> semanticWeights,
                                Map<String, Double> entropyWeights,
                                Map<String, Double> roleWeights) {
        public ResidualInput {
            executionStates = Map.copyOf(Objects.requireNonNull(executionStates, "executionStates"));
            semanticWeights = Map.copyOf(Objects.requireNonNull(semanticWeights, "semanticWeights"));
            entropyWeights = Map.copyOf(Objects.requireNonNull(entropyWeights, "entropyWeights"));
            roleWeights = Map.copyOf(Objects.requireNonNull(roleWeights, "roleWeights"));
        }

        public static ResidualInput uniform(List<String> executedOpIds, List<String> skippedOpIds) {
            Map<String, ExecutionState> states = new LinkedHashMap<>();
            for (String opId : executedOpIds) {
                states.put(opId, ExecutionState.EXECUTED);
            }
            for (String opId : skippedOpIds) {
                states.put(opId, ExecutionState.SKIPPED);
            }
            return new ResidualInput(states, Map.of(), Map.of(), Map.of());
        }
    }
}
