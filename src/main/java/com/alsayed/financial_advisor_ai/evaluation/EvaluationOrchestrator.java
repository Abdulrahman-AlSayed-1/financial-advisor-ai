package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.config.EvaluationConfig;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResult;
import com.alsayed.financial_advisor_ai.evaluation.reporting.EvaluationReporter;
import com.alsayed.financial_advisor_ai.service.IngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the evaluation process with strictly sequential execution and rate-limit throttling.
 * Ensures document ingestion is 100% complete before executing any evaluation.
 */
@Slf4j
@Component
public class EvaluationOrchestrator {

    private final EvaluationDatasetLoader datasetLoader;
    private final EvaluationService evaluationService;
    private final EvaluationReporter evaluationReporter;
    private final IngestionService ingestionService;

    @Value("${app.evaluation.skip-evaluators:false}")
    private boolean defaultSkipEvaluators;

    @Value("${app.evaluation.throttle-ms:3000}")
    private long throttleMs;

    public EvaluationOrchestrator(EvaluationDatasetLoader datasetLoader,
                                  EvaluationService evaluationService,
                                  EvaluationReporter evaluationReporter,
                                  IngestionService ingestionService) {
        this.datasetLoader = datasetLoader;
        this.evaluationService = evaluationService;
        this.evaluationReporter = evaluationReporter;
        this.ingestionService = ingestionService;
    }

    public List<EvaluationResult> runEvaluations() {
        return runEvaluation(EvaluationConfig.builder()
                .skipEvaluators(defaultSkipEvaluators)
                .build());
    }

    public List<EvaluationResult> runEvaluations(Set<String> metricsToRun, int questionLimit) {
        return runEvaluation(
                EvaluationConfig.builder()
                        .metricsToRun(metricsToRun)
                        .questionLimit(questionLimit)
                        .skipEvaluators(defaultSkipEvaluators)
                        .build()
        );
    }

    public List<EvaluationResult> runEvaluations(Set<String> metricsToRun, int questionLimit, boolean skipEvaluatorsOverride) {
        return runEvaluation(
                EvaluationConfig.builder()
                        .metricsToRun(metricsToRun)
                        .questionLimit(questionLimit)
                        .skipEvaluators(skipEvaluatorsOverride)
                        .build()
        );
    }

    public List<EvaluationResult> runEvaluations(Set<String> metricsToRun, int questionLimit, boolean skipEvaluatorsOverride, Integer questionIndex) {
        return runEvaluation(
                EvaluationConfig.builder()
                        .metricsToRun(metricsToRun)
                        .questionLimit(questionLimit)
                        .skipEvaluators(skipEvaluatorsOverride)
                        .questionIndex(questionIndex)
                        .build()
        );
    }

    private List<EvaluationResult> runEvaluation(EvaluationConfig config) {
        ensureIngestionCompleted();

        log.info("=== Starting RAG Evaluation (Sequential Processing with {}ms Throttle) ===", throttleMs);
        log.info("Metrics: {}", config.getMetricsToRun());
        log.info("Skip evaluators: {}", config.isSkipEvaluators());

        List<EvaluationQuestion> questions = selectQuestions(config);
        log.info("Evaluating {} of {} questions sequentially", questions.size(), datasetLoader.loadDataset().size());

        List<EvaluationResult> results = evaluateQuestionsSequentially(questions, config);

        evaluationReporter.printSummary(results, config.getMetricsToRun());
        evaluationReporter.printIndividualResults(results, config.getMetricsToRun());

        log.info("=== RAG Evaluation Complete ===");
        return results;
    }

    /**
     * Strictly verifies ingestion has finished before starting evaluation.
     * Blocks if ingestion is currently running.
     */
    private void ensureIngestionCompleted() {
        if (ingestionService.isIngestionComplete()) {
            return;
        }

        if (ingestionService.isIngesting()) {
            log.info("Document ingestion is currently in progress. Awaiting completion before running evaluation...");
            try {
                boolean completed = ingestionService.awaitIngestionComplete(10, TimeUnit.MINUTES);
                if (!completed) {
                    throw new IllegalStateException("Timed out waiting for document ingestion to complete (10 minutes). Evaluation aborted.");
                }
                log.info("Ingestion completed successfully. Proceeding with evaluation.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for document ingestion to complete.", e);
            }
        } else {
            throw new IllegalStateException("Vector store ingestion has not run or completed. Cannot evaluate empty vector store.");
        }
    }

    private List<EvaluationQuestion> selectQuestions(EvaluationConfig config) {
        List<EvaluationQuestion> allQuestions = datasetLoader.loadDataset();

        if (config.getQuestionIndex() != null) {
            if (config.getQuestionIndex() < 0 || config.getQuestionIndex() >= allQuestions.size()) {
                throw new IllegalArgumentException(
                    String.format("Invalid questionIndex %d. Must be in range [0, %d]",
                        config.getQuestionIndex(), allQuestions.size() - 1));
            }
            EvaluationQuestion question = allQuestions.get(config.getQuestionIndex());
            log.info("Evaluating specific question [{}]: {}", config.getQuestionIndex(), question.getQuestionText());
            return List.of(question);
        } else {
            int limit = Math.min(config.getQuestionLimit(), allQuestions.size());
            return allQuestions.subList(0, limit);
        }
    }

    /**
     * Evaluates selected questions sequentially with rate-limit throttling to prevent 429 quota exhaustion.
     */
    private List<EvaluationResult> evaluateQuestionsSequentially(List<EvaluationQuestion> questions, EvaluationConfig config) {
        List<EvaluationResult> results = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            EvaluationQuestion question = questions.get(i);
            log.info("[Sequential Eval] [{}/{}] Starting: {}", i + 1, questions.size(), question.getQuestionText());

            try {
                EvaluationResult result = evaluationService.evaluateQuestion(
                        question,
                        config.getMetricsToRun(),
                        config.isSkipEvaluators()
                );
                results.add(result);
                log.info("[Sequential Eval] [{}/{}] Finished: {}", i + 1, questions.size(), question.getQuestionText());
            } catch (Exception e) {
                log.error("[Sequential Eval] [{}/{}] Failed for question '{}': {}",
                        i + 1, questions.size(), question.getQuestionText(), e.getMessage(), e);
                // Create a failure placeholder result to avoid halting evaluation
                EvaluationResult failureResult = new EvaluationResult();
                failureResult.setQuestionId(question.getId());
                failureResult.setQuestionText(question.getQuestionText());
                failureResult.setGroundTruth(question.getGroundTruth());
                failureResult.setUserAnswer("EVALUATION_ERROR: " + e.getMessage());
                results.add(failureResult);
            }

            // Apply throttling delay between questions (except after the final question)
            if (i < questions.size() - 1 && throttleMs > 0) {
                try {
                    log.debug("Throttling rate limit: sleeping {}ms before next evaluation...", throttleMs);
                    Thread.sleep(throttleMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Throttling sleep interrupted. Proceeding immediately.");
                    break;
                }
            }
        }

        return results;
    }
}