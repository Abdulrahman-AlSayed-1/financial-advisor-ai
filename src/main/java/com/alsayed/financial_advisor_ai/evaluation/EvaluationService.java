package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.config.EvaluationConfig;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResponse;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResult;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.Evaluators;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.RetrievalEvaluator;
import com.alsayed.financial_advisor_ai.evaluation.rag.RagSystemClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core evaluation service that coordinates RAG evaluation.
 * Uses LLM-based evaluators for all metrics (relevancy, faithfulness, context recall, answer correctness).
 * Delegates to specialized components for RAG interaction.
 *
 * @see <a href="https://docs.spring.io/spring-ai/reference/api/testing.html">Spring AI Evaluation Testing</a>
 */
@Slf4j
@Service
public class EvaluationService {

    private final RagSystemClient ragSystemClient;
    private final Evaluators evaluators;
    private final RetrievalEvaluator retrievalEvaluator;

    @Value("${app.evaluation.skip-evaluators:false}")
    private boolean defaultSkipEvaluators;

    @Value("${app.evaluation.evaluator-chunk-limit:5}")
    private int evaluatorChunkLimit;

    @Value("${app.evaluation.evaluator-mode:all}")
    private String evaluatorMode;

    public EvaluationService(RagSystemClient ragSystemClient,
                             Evaluators evaluators,
                             RetrievalEvaluator retrievalEvaluator) {
        this.ragSystemClient = ragSystemClient;
        this.evaluators = evaluators;
        this.retrievalEvaluator = retrievalEvaluator;
    }


    @PostConstruct
    public void init() {
        if (defaultSkipEvaluators) {
            log.warn("Default evaluator setting is set to skip - only RAG responses will be returned unless overridden");
        }
    }

    /**
     * Evaluate a single question using the provided EvaluationConfig configuration object.
     * Preferred method for orchestrators and controllers.
     */
    public EvaluationResult evaluateQuestion(EvaluationQuestion question, EvaluationConfig config) {
        return evaluateQuestion(question, config.getMetricsToRun(), config.isSkipEvaluators());
    }

    /**
     * Evaluate a single question with all metrics using default skip-evaluator setting.
     */
    public EvaluationResult evaluateQuestion(EvaluationQuestion question) {
        return evaluateQuestion(question, EvaluationConfig.ALL_METRICS, defaultSkipEvaluators);
    }

    /**
     * Evaluate a single question with specified metrics using default skip-evaluator setting.
     */
    public EvaluationResult evaluateQuestion(EvaluationQuestion question, Set<String> metricsToRun) {
        return evaluateQuestion(question, metricsToRun, defaultSkipEvaluators);
    }

    /**
     * Core evaluation logic accepting metrics and explicit skipEvaluators flag.
     * Follows Spring AI evaluation pattern.
     */
    public EvaluationResult evaluateQuestion(EvaluationQuestion question, Set<String> metricsToRun, boolean shouldSkipEvaluators) {
        long startTime = Instant.now().toEpochMilli();
        log.info("Calling RAG system...");
        EvaluationResponse ragResponse = ragSystemClient.queryRagSystem(question.getQuestionText());
        long latency = Instant.now().toEpochMilli() - startTime;
        log.info("RAG call completed in {}ms", latency);

        // Raw retrieved documents for Hit@K metrics (original retrieval order, no post-processing)
        List<Document> rawRetrievedDocuments = ragResponse.getRetrievedDocuments();

        // Deduplicated contexts for LLM evaluators (as String list)
        List<String> deduplicatedContexts = ragResponse.getContexts();
        
        // Limit chunks for LLM evaluators to reduce token usage
        List<String> limitedContexts = limitChunksForEvaluators(deduplicatedContexts);

        log.info("Chunk counts - raw retrieved: {}, deduplicated: {}, limited for evaluators: {}",
                rawRetrievedDocuments.size(), deduplicatedContexts.size(), limitedContexts.size());

        EvaluationResult result = new EvaluationResult();
        result.setQuestionId(question.getId() != null ? question.getId() : UUID.randomUUID().toString());
        result.setQuestionText(question.getQuestionText());
        result.setGroundTruth(question.getGroundTruth());
        result.setUserAnswer(ragResponse.getAnswer());
        result.setContexts(ragResponse.getContexts());
        result.setLatency(latency);

        // Layer 1: Retrieval metrics (Hit@K runs even when evaluators are skipped since it's objective)
        // Hit@K uses raw retrieved documents (no post-processing)
        // Relevancy and Context Recall use full deduplicated contexts (no artificial truncation)
        runRetrievalMetrics(question, ragResponse, rawRetrievedDocuments, deduplicatedContexts, result, metricsToRun, shouldSkipEvaluators);
        
        if (!shouldSkipEvaluators) {
            // Layer 2: Generation metrics
            // Faithfulness uses limited contexts (generation metric, token optimization acceptable)
            runGenerationMetrics(question, ragResponse, limitedContexts, result, metricsToRun);
        } else {
            log.info("LLM evaluators skipped for speed (Hit@K still runs)");
        }

        return result;
    }

    /**
     * Limit chunks for evaluators to reduce token usage and cost.
     * Uses top-k most relevant chunks for LLM-based evaluation.
     */
    private List<String> limitChunksForEvaluators(List<String> contextStrings) {
        if (contextStrings.size() <= evaluatorChunkLimit) {
            return contextStrings;
        }
        
        List<String> limited = contextStrings.stream()
                .limit(evaluatorChunkLimit)
                .toList();
        
        log.info("Limited evaluator chunks from {} to {} for token optimization", 
                contextStrings.size(), limited.size());
        
        return limited;
    }

    /**
     * Run Layer 1 retrieval metrics (Hit@K, Relevancy, Context Recall).
     * Hit@K is objective (no LLM calls), Relevancy and Context Recall are LLM-based.
     */
    private void runRetrievalMetrics(EvaluationQuestion question, EvaluationResponse ragResponse,
                                      List<Document> rawRetrievedDocuments, List<String> deduplicatedContexts,
                                      EvaluationResult result, Set<String> metricsToRun, boolean shouldSkipEvaluators) {
        // Hit@5 - check if source context is in top 5 retrieved chunks (objective, no LLM)
        if (metricsToRun.contains(EvaluationConfig.HIT_AT_5)) {
            boolean hitAt5 = retrievalEvaluator.hitAtK(question, rawRetrievedDocuments, 5);
            result.setHitAt5Pass(hitAt5);
            log.info("Hit@5: {}", hitAt5 ? "PASS" : "FAIL");
        }

        // Hit@10 - check if source context is in top 10 retrieved chunks (objective, no LLM)
        if (metricsToRun.contains(EvaluationConfig.HIT_AT_10)) {
            boolean hitAt10 = retrievalEvaluator.hitAtK(question, rawRetrievedDocuments, 10);
            result.setHitAt10Pass(hitAt10);
            log.info("Hit@10: {}", hitAt10 ? "PASS" : "FAIL");
        }

        // Skip LLM-based retrieval metrics if evaluators are skipped
        if (shouldSkipEvaluators) {
            log.info("Skipping LLM-based retrieval metrics (evaluators disabled)");
            return;
        }

        // Skip LLM-based retrieval metrics in minimal mode
        if ("minimal".equals(evaluatorMode)) {
            log.info("Skipping LLM-based retrieval metrics in minimal mode");
            return;
        }

        // Relevancy - LLM-based retrieval quality metric (uses full deduplicated contexts)
        if (metricsToRun.contains(EvaluationConfig.RELEVANCY)) {
            var relevancyScore = evaluators.evaluateRelevancy(
                question.getQuestionText(),
                deduplicatedContexts
            );
            result.setRelevancyPass(relevancyScore.score() >= 0.7);
            result.setRelevancyScore((float) relevancyScore.score());
            log.info("Relevancy: pass={}, score={}, reasoning={}",
                result.getRelevancyPass(), relevancyScore.score(), relevancyScore.reasoning());
        }

        // Context Recall - LLM-based retrieval completeness metric (uses full deduplicated contexts)
        if (metricsToRun.contains(EvaluationConfig.CONTEXT_RECALL)) {
            var contextRecallScore = evaluators.evaluateContextRecall(
                question.getQuestionText(),
                question.getGroundTruth(),
                deduplicatedContexts
            );
            result.setContextRecallPass(contextRecallScore.score() >= 0.7);
            result.setContextRecallScore((float) contextRecallScore.score());
            log.info("Context recall: pass={}, score={}, reasoning={}",
                result.getContextRecallPass(), contextRecallScore.score(), contextRecallScore.reasoning());
        }
    }

    /**
     * Run Layer 2 generation metrics (Answer Correctness, Faithfulness).
     * Both are LLM-based and evaluate the generated answer quality.
     */
    private void runGenerationMetrics(EvaluationQuestion question, EvaluationResponse ragResponse,
                                      List<String> limitedContexts, EvaluationResult result, Set<String> metricsToRun) {
        // Skip generation metrics in retrieval-only or minimal mode
        if ("retrieval-only".equals(evaluatorMode) || "minimal".equals(evaluatorMode)) {
            log.info("Skipping generation metrics in {} mode", evaluatorMode);
            return;
        }

        // Answer Correctness - LLM-based generation quality metric (no contexts needed)
        if (metricsToRun.contains(EvaluationConfig.ANSWER_CORRECTNESS)) {
            log.info("Running answer correctness evaluation...");
            var answerCorrectnessScore = evaluators.evaluateAnswerCorrectness(
                question.getQuestionText(),
                question.getGroundTruth(),
                ragResponse.getAnswer()
            );
            result.setAnswerCorrectnessPass(answerCorrectnessScore.score() >= 0.7);
            result.setAnswerCorrectnessScore((float) answerCorrectnessScore.score());
            log.info("Answer correctness: pass={}, score={}, reasoning={}",
                result.getAnswerCorrectnessPass(), answerCorrectnessScore.score(), answerCorrectnessScore.reasoning());
        }

        // Faithfulness - LLM-based grounding metric (uses limited contexts)
        if (metricsToRun.contains(EvaluationConfig.FAITHFULNESS)) {
            log.info("Running faithfulness evaluation...");
            var faithfulnessScore = evaluators.evaluateFaithfulness(ragResponse.getAnswer(), limitedContexts);
            result.setFaithfulnessPass(faithfulnessScore.score() >= 0.7);
            result.setFaithfulnessScore((float) faithfulnessScore.score());
            log.info("Faithfulness: pass={}, score={}, reasoning={}",
                result.getFaithfulnessPass(), faithfulnessScore.score(), faithfulnessScore.reasoning());
        }
    }
}