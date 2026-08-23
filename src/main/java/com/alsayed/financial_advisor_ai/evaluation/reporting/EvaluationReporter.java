package com.alsayed.financial_advisor_ai.evaluation.reporting;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Handles evaluation result reporting and summary generation.
 * Provides formatted output for evaluation results.
 */
@Component
public class EvaluationReporter {

    private static final String RELEVANCY = "relevancy";
    private static final String ANSWER_CORRECTNESS = "answerCorrectness";
    private static final String FAITHFULNESS = "faithfulness";
    private static final String CONTEXT_RECALL = "contextRecall";

    /**
     * Print evaluation summary statistics.
     *
     * @param results The evaluation results
     * @param metricsRun The metrics that were run
     */
    public void printSummary(List<EvaluationResult> results, Set<String> metricsRun) {
        System.out.println("\n=== Evaluation Summary ===");
        System.out.printf("Total questions evaluated: %d%n", results.size());

        // Layer 1: Retrieval metrics
        System.out.println("\n--- Layer 1: Retrieval ---");
        printRetrievalSummary(results);
        
        if (metricsRun.contains(RELEVANCY)) {
            printMetricSummary(results, "  Relevancy", EvaluationResult::getRelevancyPass, EvaluationResult::getRelevancyScore);
        }

        if (metricsRun.contains(CONTEXT_RECALL)) {
            printMetricSummary(results, "  Context Recall", EvaluationResult::getContextRecallPass, EvaluationResult::getContextRecallScore);
        }

        // Layer 2: Generation metrics
        System.out.println("\n--- Layer 2: Generation ---");
        
        if (metricsRun.contains(ANSWER_CORRECTNESS)) {
            printMetricSummary(results, "  Answer Correctness", EvaluationResult::getAnswerCorrectnessPass, EvaluationResult::getAnswerCorrectnessScore);
        }

        if (metricsRun.contains(FAITHFULNESS)) {
            printMetricSummary(results, "  Faithfulness", EvaluationResult::getFaithfulnessPass, EvaluationResult::getFaithfulnessScore);
        }

        double avgLatency = results.stream()
                .mapToLong(EvaluationResult::getLatency)
                .average()
                .orElse(0.0);
        System.out.printf("\nAverage latency: %.0f ms%n", avgLatency);
    }

    /**
     * Print retrieval metrics summary (Layer 1).
     */
    private void printRetrievalSummary(List<EvaluationResult> results) {
        long hitAt5Passed = results.stream()
                .filter(r -> r.getHitAt5Pass() != null && r.getHitAt5Pass())
                .count();
        long hitAt5Total = results.stream()
                .filter(r -> r.getHitAt5Pass() != null)
                .count();

        long hitAt10Passed = results.stream()
                .filter(r -> r.getHitAt10Pass() != null && r.getHitAt10Pass())
                .count();
        long hitAt10Total = results.stream()
                .filter(r -> r.getHitAt10Pass() != null)
                .count();

        // If no Hit@K results were computed, show as "not run"
        if (hitAt5Total == 0 && hitAt10Total == 0) {
            System.out.println("  Hit@5: NOT RUN (metric not in evaluation set)");
            System.out.println("  Hit@10: NOT RUN (metric not in evaluation set)");
        } else {
            System.out.printf("  Hit@5: %d/%d passed (%.1f%%)%n",
                    hitAt5Passed, hitAt5Total,
                    hitAt5Total > 0 ? (hitAt5Passed * 100.0 / hitAt5Total) : 0.0);
            System.out.printf("  Hit@10: %d/%d passed (%.1f%%)%n",
                    hitAt10Passed, hitAt10Total,
                    hitAt10Total > 0 ? (hitAt10Passed * 100.0 / hitAt10Total) : 0.0);
        }
    }

    /**
     * Print individual evaluation results.
     *
     * @param results The evaluation results
     * @param metricsRun The metrics that were run
     */
    public void printIndividualResults(List<EvaluationResult> results, Set<String> metricsRun) {
        System.out.println("\n=== Individual Results ===");
        for (int i = 0; i < results.size(); i++) {
            EvaluationResult r = results.get(i);
            System.out.printf("%d. %s%n", i + 1, r.getQuestionText());
            System.out.printf("   Answer: %s%n", r.getUserAnswer() != null ? r.getUserAnswer() : "N/A");

            // Layer 1: Retrieval metrics
            if (r.getHitAt5Pass() != null) {
                System.out.printf("   Hit@5: %s%n", r.getHitAt5Pass() ? "PASS" : "FAIL");
            } else {
                System.out.printf("   Hit@5: NOT RUN%n");
            }
            if (r.getHitAt10Pass() != null) {
                System.out.printf("   Hit@10: %s%n", r.getHitAt10Pass() ? "PASS" : "FAIL");
            } else {
                System.out.printf("   Hit@10: NOT RUN%n");
            }

            // Print retrieved chunks for debugging/maintenance
            if (r.getContexts() != null && !r.getContexts().isEmpty()) {
                System.out.printf("   Retrieved Chunks (%d):%n", r.getContexts().size());
                for (int j = 0; j < r.getContexts().size(); j++) {
                    String chunk = r.getContexts().get(j);
                    String cleanedChunk = chunk.replaceAll("\\s+", " ").trim(); // Clean whitespace
                    String preview = cleanedChunk.length() > 150 ? cleanedChunk.substring(0, 150) + "..." : cleanedChunk;
                    System.out.printf("     [%d] %s%n", j + 1, preview);
                }
            }

            if (metricsRun.contains(RELEVANCY) && r.getRelevancyPass() != null) {
                System.out.printf("   Relevancy: %s (%.2f)%n", r.getRelevancyPass(), r.getRelevancyScore());
            }
            if (metricsRun.contains(ANSWER_CORRECTNESS) && r.getAnswerCorrectnessPass() != null) {
                System.out.printf("   Answer Correctness: %s (%.2f)%n", r.getAnswerCorrectnessPass(), r.getAnswerCorrectnessScore());
            }
            if (metricsRun.contains(FAITHFULNESS) && r.getFaithfulnessPass() != null) {
                System.out.printf("   Faithfulness: %s (%.2f)%n", r.getFaithfulnessPass(), r.getFaithfulnessScore());
            }
            if (metricsRun.contains(CONTEXT_RECALL) && r.getContextRecallPass() != null) {
                System.out.printf("   Context Recall: %s (%.2f)%n", r.getContextRecallPass(), r.getContextRecallScore());
            }
            System.out.printf("   Latency: %d ms%n", r.getLatency());
        }
    }

    private void printMetricSummary(List<EvaluationResult> results, String metricName,
                                   java.util.function.Function<EvaluationResult, Boolean> passGetter,
                                   java.util.function.Function<EvaluationResult, Float> scoreGetter) {
        long passed = results.stream()
                .filter(r -> passGetter.apply(r) != null && passGetter.apply(r))
                .count();
        long total = results.stream()
                .filter(r -> passGetter.apply(r) != null)
                .count();
        double avgScore = results.stream()
                .filter(r -> scoreGetter.apply(r) > 0)
                .mapToDouble(scoreGetter::apply)
                .average()
                .orElse(0.0);

        System.out.printf("%s: %d/%d passed (%.1f%%), avg score: %.2f%n",
                metricName, passed, total,
                total > 0 ? (passed * 100.0 / total) : 0.0,
                avgScore);
    }
}
