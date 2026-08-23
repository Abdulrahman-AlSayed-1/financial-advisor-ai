package com.alsayed.financial_advisor_ai.evaluation.dto;

/**
 * DTO for evaluation results containing both a numeric score and reasoning.
 * Allows for richer evaluation reporting and debugging.
 */
public record EvaluationScore(
    double score,
    String reasoning
) {
    /**
     * Creates an EvaluationScore with empty reasoning.
     */
    public EvaluationScore(double score) {
        this(score, "");
    }
}
