package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.RetrievalDebugResult;
import com.alsayed.financial_advisor_ai.evaluation.dto.RetrievalDatasetResult;
import com.alsayed.financial_advisor_ai.evaluation.dto.RetrievalQuestionResult;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.RetrievalEvaluator;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Retrieval-only debugging service.
 * Performs retrieval without LLM generation or evaluation.
 * Fast path for debugging retrieval quality.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalDebugService {

    private final HybridRetrievalService hybridRetrievalService;
    private final RetrievalEvaluator retrievalEvaluator;

    /**
     * Perform retrieval-only search for a single question.
     * Returns compact diagnostic information about retrieved chunks.
     * NO LLM calls - only retrieval.
     */
    public List<RetrievalDebugResult> debugRetrieval(String question, int topK) {
        log.info("Retrieval-debug: Searching for '{}' with topK={}", question, topK);
        
        long startTime = System.currentTimeMillis();
        List<Document> retrievedDocs = hybridRetrievalService.search(question, topK);
        long retrievalTime = System.currentTimeMillis() - startTime;
        
        log.info("Retrieval-debug: Retrieved {} documents in {}ms", retrievedDocs.size(), retrievalTime);
        
        // Convert to compact debug results
        List<RetrievalDebugResult> results = new ArrayList<>();
        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document doc = retrievedDocs.get(i);
            // Use position-based score since HybridRetrievalService doesn't expose individual scores
            double score = 1.0 - (i * 0.01);
            results.add(RetrievalDebugResult.fromDocument(i + 1, score, doc));
        }
        
        return results;
    }

    /**
     * Perform retrieval-only evaluation for the entire dataset.
     * Returns Hit@K metrics without LLM calls.
     */
    public RetrievalDatasetResult evaluateDatasetRetrieval(List<EvaluationQuestion> questions, int topK) {
        log.info("Retrieval-dataset evaluation: {} questions, topK={}", questions.size(), topK);
        
        int hitAt5Count = 0;
        int hitAt10Count = 0;
        List<RetrievalQuestionResult> questionResults = new ArrayList<>();
        
        for (EvaluationQuestion question : questions) {
            long startTime = System.currentTimeMillis();
            List<Document> retrievedDocs = hybridRetrievalService.search(question.getQuestionText(), topK);
            long retrievalTime = System.currentTimeMillis() - startTime;
            
            boolean hitAt5 = retrievalEvaluator.hitAtK(question, retrievedDocs, 5);
            boolean hitAt10 = retrievalEvaluator.hitAtK(question, retrievedDocs, 10);
            
            if (hitAt5) hitAt5Count++;
            if (hitAt10) hitAt10Count++;
            
            // Build question result
            RetrievalQuestionResult questionResult = new RetrievalQuestionResult();
            questionResult.setQuestionId(question.getId());
            questionResult.setQuestionText(question.getQuestionText());
            questionResult.setHitAt5(hitAt5);
            questionResult.setHitAt10(hitAt10);
            questionResult.setRetrievalTime(retrievalTime);
            questionResult.setRetrievedCount(retrievedDocs.size());
            
            // Extract page numbers for diagnostics
            List<Integer> pages = retrievedDocs.stream()
                    .map(doc -> getIntMetadata(doc.getMetadata(), "page_number"))
                    .toList();
            questionResult.setRetrievedPages(pages);
            
            // Convert top chunks to debug results
            List<RetrievalDebugResult> topChunks = IntStream.range(0, Math.min(5, retrievedDocs.size()))
                    .mapToObj(i -> {
                        Document doc = retrievedDocs.get(i);
                        double score = 1.0 - (i * 0.01);
                        return RetrievalDebugResult.fromDocument(i + 1, score, doc);
                    })
                    .toList();
            questionResult.setTopChunks(topChunks);
            
            questionResults.add(questionResult);
            
            log.debug("Question {}: Hit@5={}, Hit@10={}, time={}ms", 
                    question.getId(), hitAt5, hitAt10, retrievalTime);
        }
        
        RetrievalDatasetResult datasetResult = new RetrievalDatasetResult();
        datasetResult.setTotalQuestions(questions.size());
        datasetResult.setHitAt5Count(hitAt5Count);
        datasetResult.setHitAt10Count(hitAt10Count);
        datasetResult.setHitAt5Rate(questions.isEmpty() ? 0.0 : (double) hitAt5Count / questions.size());
        datasetResult.setHitAt10Rate(questions.isEmpty() ? 0.0 : (double) hitAt10Count / questions.size());
        datasetResult.setQuestionResults(questionResults);
        
        log.info("Retrieval-dataset evaluation complete: Hit@5={}/{} ({:.1%}), Hit@10={}/{} ({:.1%})",
                hitAt5Count, questions.size(), datasetResult.getHitAt5Rate(),
                hitAt10Count, questions.size(), datasetResult.getHitAt10Rate());
        
        return datasetResult;
    }
    
    private static Integer getIntMetadata(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
