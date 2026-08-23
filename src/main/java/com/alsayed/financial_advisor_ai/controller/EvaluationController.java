package com.alsayed.financial_advisor_ai.controller;

import com.alsayed.financial_advisor_ai.evaluation.EvaluationDatasetLoader;
import com.alsayed.financial_advisor_ai.evaluation.EvaluationOrchestrator;
import com.alsayed.financial_advisor_ai.evaluation.EvaluationService;
import com.alsayed.financial_advisor_ai.evaluation.RetrievalDebugService;
import com.alsayed.financial_advisor_ai.evaluation.config.EvaluationConfig;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResponse;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResult;
import com.alsayed.financial_advisor_ai.evaluation.dto.RetrievalDebugResult;
import com.alsayed.financial_advisor_ai.evaluation.dto.RetrievalDatasetResult;
import com.alsayed.financial_advisor_ai.evaluation.rag.RagSystemClient;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import com.alsayed.financial_advisor_ai.retrieval.NumericComparator;
import com.alsayed.financial_advisor_ai.retrieval.QueryAnalyzer;
import com.alsayed.financial_advisor_ai.retrieval.QuerySignals;
import com.alsayed.financial_advisor_ai.retrieval.StructuredCandidateRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final EvaluationOrchestrator evaluationOrchestrator;
    private final RagSystemClient ragSystemClient;
    private final RetrievalDebugService retrievalDebugService;
    private final EvaluationDatasetLoader evaluationDatasetLoader;
    private final QueryAnalyzer queryAnalyzer;
    private final NumericComparator numericComparator;
    private final StructuredCandidateRetriever structuredCandidateRetriever;
    private final HybridRetrievalService hybridRetrievalService;

    @PostMapping("/chat")
    public EvaluationResponse evaluate(@RequestParam String prompt) {
        return ragSystemClient.queryRagSystem(prompt);
    }

    /**
     * Single question evaluation with metrics for instant feedback.
     * Much faster than running full evaluation on multiple questions.
     */
    @PostMapping("/single")
    public EvaluationResult evaluateSingle(
            @RequestParam String prompt,
            @RequestParam(required = false) List<String> metrics
    ) {
        Set<String> metricsToRun = (metrics == null || metrics.isEmpty())
                ? EvaluationConfig.QUICK_METRICS
                : Set.copyOf(metrics);
        
        // Create a temporary question object
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText(prompt);
        
        return evaluationService.evaluateQuestion(question, metricsToRun);
    }

    /**
     * Full evaluation: both Spring AI metrics on the entire dataset.
     * Each question costs 1 RAG call + up to 2 evaluator LLM calls.
     */
    @PostMapping("/run")
    public List<EvaluationResult> runEvaluation() {
        return evaluationOrchestrator.runEvaluations();
    }

    /**
     * Quick evaluation for iteration. Defaults to Hit@K metrics on 10 questions.
     * Example: POST /evaluation/quick-run?metrics=relevancy,factChecking&questionLimit=5&skipEvaluators=true
     */
    @PostMapping("/quick-run")
    public List<EvaluationResult> quickRunEvaluation(
            @RequestParam(required = false) List<String> metrics,
            @RequestParam(defaultValue = "10") int questionLimit,
            @RequestParam(defaultValue = "false") boolean skipEvaluators,
            @RequestParam(required = false) Integer questionIndex
    ) {
        Set<String> metricsToRun = (metrics == null || metrics.isEmpty())
                ? Set.of(EvaluationConfig.HIT_AT_5, EvaluationConfig.HIT_AT_10)  // Default to Hit@K metrics
                : new HashSet<>(metrics);

        return evaluationOrchestrator.runEvaluations(metricsToRun, questionLimit, skipEvaluators, questionIndex);
    }

    /**
     * Retrieval-only debugging endpoint.
     * Performs retrieval without LLM generation or evaluation.
     * Fast path for debugging retrieval quality.
     * 
     * Example: POST /evaluation/retrieval-debug?question=Which%20two%20S%26P%20500%20sectors...&topK=10
     */
    @PostMapping("/retrieval-debug")
    public List<RetrievalDebugResult> debugRetrieval(
            @RequestParam String question,
            @RequestParam(defaultValue = "10") int topK
    ) {
        return retrievalDebugService.debugRetrieval(question, topK);
    }

    /**
     * Retrieval-only dataset evaluation endpoint.
     * Evaluates retrieval for the entire dataset without LLM calls.
     * Returns Hit@K metrics and per-question diagnostics.
     * 
     * Example: POST /evaluation/retrieval?topK=10
     */
    @PostMapping("/retrieval")
    public RetrievalDatasetResult evaluateDatasetRetrieval(
            @RequestParam(defaultValue = "10") int topK
    ) {
        List<EvaluationQuestion> questions = evaluationDatasetLoader.loadDataset();
        return retrievalDebugService.evaluateDatasetRetrieval(questions, topK);
    }

    /**
     * Comparative retrieval debug endpoint.
     * Performs structured retrieval for comparative/aggregation queries without LLM calls.
     * Shows extracted signals, candidates, and ranked results.
     * 
     * Example: POST /evaluation/comparative-debug?question=Which%20two%20S%26P%20500%20sectors...&topK=10
     */
    @PostMapping("/comparative-debug")
    public Map<String, Object> debugComparativeRetrieval(
            @RequestParam String question,
            @RequestParam(defaultValue = "10") int topK
    ) {
        // Analyze query to extract signals
        QuerySignals signals = queryAnalyzer.analyze(question);
        
        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", question);
        Map<String, Object> detectedSignals = new HashMap<>();
        detectedSignals.put("entities", signals.getEntities());
        detectedSignals.put("metric", signals.getMetric());
        detectedSignals.put("period", signals.getPeriod());
        detectedSignals.put("date_constraint", signals.getDateConstraint());
        detectedSignals.put("operation", signals.getOperation());
        detectedSignals.put("limit", signals.getLimit());
        detectedSignals.put("group_by", signals.getGroupBy());
        detectedSignals.put("is_comparative", signals.isComparative());
        response.put("detected_signals", detectedSignals);
        
        // Only perform comparative retrieval if it's actually a comparative query
        if (!signals.isComparative()) {
            response.put("message", "Query is not comparative, would use normal retrieval path");
            response.put("candidate_rows", List.of());
            response.put("selected", List.of());
            return response;
        }
        
        try {
            // Retrieve candidates using hybrid retrieval (which now handles comparative queries)
            List<Document> candidates = hybridRetrievalService.search(question, topK * 10);
            
            // Sort candidates by numeric values
            List<NumericComparator.RankedDocument> ranked = 
                numericComparator.compareAndSort(candidates, signals);
            
            // Candidate rows
            List<Map<String, Object>> candidateRows = candidates.stream()
                .limit(20) // Show first 20 candidates
                .map(doc -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("row_entity", structuredCandidateRetriever.getRowEntity(doc));
                    row.put("metric", structuredCandidateRetriever.getMetric(doc));
                    row.put("period", structuredCandidateRetriever.getPeriod(doc));
                    row.put("value", structuredCandidateRetriever.extractNumericValue(doc));
                    row.put("table_id", structuredCandidateRetriever.getTableId(doc));
                    return row;
                })
                .collect(Collectors.toList());
            response.put("candidate_rows", candidateRows);
            
            // Selected results
            List<Map<String, Object>> selected = ranked.stream()
                .limit(topK)
                .map(rankedDoc -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("rank", rankedDoc.rank());
                    row.put("row_entity", structuredCandidateRetriever.getRowEntity(rankedDoc.document()));
                    row.put("metric", structuredCandidateRetriever.getMetric(rankedDoc.document()));
                    row.put("period", structuredCandidateRetriever.getPeriod(rankedDoc.document()));
                    row.put("value", rankedDoc.numericValue());
                    return row;
                })
                .collect(Collectors.toList());
            response.put("selected", selected);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            response.put("candidate_rows", List.of());
            response.put("selected", List.of());
        }
        
        return response;
    }
}
