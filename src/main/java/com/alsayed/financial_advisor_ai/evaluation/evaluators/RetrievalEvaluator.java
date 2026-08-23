package com.alsayed.financial_advisor_ai.evaluation.evaluators;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Objective retrieval metrics that don't require LLM calls.
 * Provides Hit@K and other retrieval-focused metrics for Layer 1 evaluation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalEvaluator {

    private final EvidenceBasedRetrievalEvaluator evidenceBasedRetrievalEvaluator;

    /**
     * Hit@K metric - checks if retrieved context contains sufficient evidence to answer the question.
     * Prefers evidence-based evaluation when evidence_groups are available.
     * Falls back to chunk ID matching for backward compatibility.
     * Falls back to source context matching as diagnostic only when neither is available.
     *
     * @param question The evaluation question with evidence_groups, relevant chunk IDs, and/or source context
     * @param retrievedChunks The chunks retrieved by the RAG system (in original order)
     * @param k The K value for Hit@K (e.g., 5 for top-5)
     * @return true if sufficient evidence is found in top-K, false otherwise
     */
    public boolean hitAtK(EvaluationQuestion question, List<Document> retrievedChunks, int k) {
        // Defensive validation
        if (question == null) {
            log.warn("Question is null, cannot compute Hit@K");
            return false;
        }

        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            log.warn("Retrieved chunks is null or empty for question '{}', cannot compute Hit@K", 
                question.getQuestionText());
            return false;
        }

        if (k <= 0) {
            log.warn("Invalid k value {} for question '{}', must be positive", k, question.getQuestionText());
            return false;
        }

        // PREFER evidence-based evaluation (new, robust method)
        if (question.getEvidenceGroups() != null && !question.getEvidenceGroups().isEmpty()) {
            return hitAtKByEvidence(question, retrievedChunks, k);
        }

        // FALLBACK: chunk ID matching (deprecated, for backward compatibility)
        List<String> relevantChunkIds = question.getRelevantChunkIds();
        if (relevantChunkIds != null && !relevantChunkIds.isEmpty()) {
            log.warn("Using deprecated chunk ID-based Hit@K. Consider migrating to evidence_groups.");
            return hitAtKByIds(relevantChunkIds, retrievedChunks, k, question);
        }

        // FALLBACK: source context matching (diagnostic only, least reliable)
        if (question.getSourceContext() != null && !question.getSourceContext().isBlank()) {
            return hitAtKByContext(question, retrievedChunks, k);
        }

        log.warn("No evidence_groups, relevant chunk IDs, or source context provided for question '{}', cannot compute Hit@K", 
            question.getQuestionText());
        return false;
    }

    /**
     * Hit@K using evidence-based evaluation (preferred, robust method).
     * Checks if the retrieved context contains all required evidence groups.
     */
    private boolean hitAtKByEvidence(EvaluationQuestion question, List<Document> retrievedChunks, int k) {
        EvidenceBasedRetrievalEvaluator.EvidenceMatchResult result = 
            evidenceBasedRetrievalEvaluator.evaluateEvidence(question, retrievedChunks, k);

        if (result.matched()) {
            log.info("Hit@{} SUCCESS (by evidence) - Question: '{}', Matched evidence: {}, Missing evidence: {}", 
                k, question.getQuestionText(), 
                result.matchedEvidence(), result.missingEvidence());
        } else {
            log.warn("Hit@{} FAILURE (by evidence) - Question: '{}', Matched evidence: {}, Missing evidence: {}, Reason: {}", 
                k, question.getQuestionText(), 
                result.matchedEvidence(), result.missingEvidence(), result.reason());
        }

        return result.matched();
    }

    /**
     * Hit@K using explicit relevant chunk IDs (preferred, reliable method).
     * Checks if any relevant chunk ID appears within the first K retrieved documents.
     */
    private boolean hitAtKByIds(List<String> relevantChunkIds, List<Document> retrievedChunks, int k, 
                                 EvaluationQuestion question) {
        List<Document> topKChunks = retrievedChunks.stream()
                .limit(k)
                .toList();

        log.info("Hit@{} by ID - Question: '{}', Relevant IDs: {}, Checking top-{} of {} chunks", 
            k, question.getQuestionText(), relevantChunkIds, k, retrievedChunks.size());
        
        log.info("Top-K chunk IDs: {}", topKChunks.stream().map(Document::getId).toList());

        for (int i = 0; i < topKChunks.size(); i++) {
            Document chunk = topKChunks.get(i);
            String chunkId = chunk.getId();
            
            if (relevantChunkIds.contains(chunkId)) {
                log.info("Hit@{} SUCCESS (by ID) - Question: '{}', Relevant ID '{}' found at position {}/{}, Page: {}, Chunk type: {}", 
                    k, question.getQuestionText(), chunkId, i + 1, k,
                    safeGetMetadata(chunk.getMetadata(), "page_number"),
                    safeGetMetadata(chunk.getMetadata(), "chunk_type"));
                return true;
            }
        }

        // Log failure with diagnostic information
        log.warn("Hit@{} FAILURE (by ID) - Question: '{}', Relevant IDs: {} NOT found in top-{} chunks. " +
                 "Top-K chunk IDs: {}. This indicates retrieval failure.", 
            k, question.getQuestionText(), relevantChunkIds, k, 
            topKChunks.stream().map(Document::getId).toList());
        return false;
    }

    /**
     * Hit@K using source context matching (fallback diagnostic method).
     * Less reliable than ID-based matching due to formatting variations.
     * Used only when relevant chunk IDs are unavailable.
     */
    private boolean hitAtKByContext(EvaluationQuestion question, List<Document> retrievedChunks, int k) {
        String sourceContext = question.getSourceContext();
        List<Document> topKChunks = retrievedChunks.stream()
                .limit(k)
                .toList();

        log.debug("Hit@{} by context - Question: '{}', Source: '{}', Checking top-{} of {} chunks", 
            k, question.getQuestionText(), sourceContext, k, retrievedChunks.size());

        for (int i = 0; i < topKChunks.size(); i++) {
            Document chunk = topKChunks.get(i);
            String chunkText = chunk.getText();
            
            if (chunkText == null || chunkText.isBlank()) {
                log.debug("Chunk {} has blank text, skipping", i + 1);
                continue;
            }

            MatchResult matchResult = containsSourceContext(chunkText, sourceContext);
            
            if (matchResult.matched) {
                log.info("Hit@{} SUCCESS (by context) - Question: '{}', Source: '{}', Found at position {}/{}, Page: {}, Chunk type: {}, Match reason: {}", 
                    k, question.getQuestionText(), sourceContext, i + 1, k,
                    safeGetMetadata(chunk.getMetadata(), "page_number"),
                    safeGetMetadata(chunk.getMetadata(), "chunk_type"),
                    matchResult.reason);
                return true;
            } else {
                log.debug("Hit@{} - Chunk {}/{} (page: {}, type: {}) did not match. Reason: {}", 
                    k, i + 1, k,
                    safeGetMetadata(chunk.getMetadata(), "page_number"),
                    safeGetMetadata(chunk.getMetadata(), "chunk_type"),
                    matchResult.reason);
            }
        }

        log.warn("Hit@{} FAILURE (by context) - Question: '{}', Source context: '{}' NOT found in top-{} chunks. " +
                 "This may indicate: (A) retrieval failure (relevant info absent), " +
                 "(B) evaluator matching failure (info exists but format differs), or " +
                 "(C) dataset issue (sourceContext doesn't represent actual relevant chunk)", 
            k, question.getQuestionText(), sourceContext, k);
        return false;
    }

    /**
     * Check if chunk content contains the source context.
     * Conservative matching to avoid false positives.
     * Returns MatchResult with match status and diagnostic reason.
     */
    private MatchResult containsSourceContext(String chunkContent, String sourceContext) {
        if (chunkContent == null || sourceContext == null) {
            return new MatchResult(false, "null content");
        }

        // Normalize both strings for comparison
        String normalizedChunk = normalizeText(chunkContent);
        String normalizedSource = normalizeText(sourceContext);

        // Check for exact substring match (most reliable)
        if (normalizedChunk.contains(normalizedSource)) {
            return new MatchResult(true, "exact normalized match");
        }

        // Check if source context is longer than chunk (impossible match)
        if (normalizedSource.length() > normalizedChunk.length()) {
            return new MatchResult(false, "source longer than chunk");
        }

        // Check for substantial word overlap (at least 70% of source words present)
        double wordOverlapRatio = calculateWordOverlap(normalizedChunk, normalizedSource);
        if (wordOverlapRatio >= 0.7) {
            return new MatchResult(true, String.format("high word overlap (%.0f%%)", wordOverlapRatio * 100));
        }

        return new MatchResult(false, String.format("insufficient word overlap (%.0f%%)", wordOverlapRatio * 100));
    }

    /**
     * Calculate word overlap ratio between chunk and source context.
     * Returns the fraction of source words present in chunk.
     * Short words (<=2 chars) are excluded from BOTH numerator and denominator.
     */
    private double calculateWordOverlap(String chunk, String source) {
        String[] sourceWords = source.split("\\s+");
        String[] chunkWords = chunk.split("\\s+");
        
        if (sourceWords.length == 0) {
            return 0.0;
        }

        // Filter out short words from source for denominator
        java.util.List<String> significantSourceWords = new java.util.ArrayList<>();
        for (String sourceWord : sourceWords) {
            if (sourceWord.length() > 2) {
                significantSourceWords.add(sourceWord);
            }
        }

        if (significantSourceWords.isEmpty()) {
            return 0.0;
        }

        int matchingWords = 0;
        for (String sourceWord : significantSourceWords) {
            for (String chunkWord : chunkWords) {
                if (sourceWord.equals(chunkWord)) {
                    matchingWords++;
                    break;
                }
            }
        }
        
        return (double) matchingWords / significantSourceWords.size();
    }

    /**
     * Normalize text by removing extra whitespace.
     * Preserves Unicode characters for non-English content support.
     */
    private String normalizeText(String text) {
        return text.toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Safely get metadata value, returning "N/A" if metadata is null or key is missing.
     */
    private String safeGetMetadata(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return "N/A";
        }
        Object value = metadata.get(key);
        return value != null ? value.toString() : "N/A";
    }

    /**
     * Result of matching source context against chunk content.
     */
    private static record MatchResult(boolean matched, String reason) {}
}
