package com.alsayed.financial_advisor_ai.evaluation.config;

import lombok.Builder;
import lombok.Getter;

import java.util.Set;

/**
 * Configuration for evaluation runs.
 * Provides a clean alternative to overloaded methods with many parameters.
 */
@Getter
@Builder
public class EvaluationConfig {

    public static final String RELEVANCY = "relevancy";
    public static final String ANSWER_CORRECTNESS = "answerCorrectness";
    public static final String FAITHFULNESS = "faithfulness";
    public static final String CONTEXT_RECALL = "contextRecall";
    public static final String HIT_AT_5 = "hitAt5";
    public static final String HIT_AT_10 = "hitAt10";

    public static final Set<String> ALL_METRICS = Set.of(RELEVANCY, ANSWER_CORRECTNESS, FAITHFULNESS, CONTEXT_RECALL, HIT_AT_5, HIT_AT_10);
    public static final Set<String> QUICK_METRICS = Set.of(ANSWER_CORRECTNESS);

    @Builder.Default
    private Set<String> metricsToRun = ALL_METRICS;
    @Builder.Default
    private int questionLimit = Integer.MAX_VALUE;
    @Builder.Default
    private boolean skipEvaluators = false;
    private Integer questionIndex;
}
