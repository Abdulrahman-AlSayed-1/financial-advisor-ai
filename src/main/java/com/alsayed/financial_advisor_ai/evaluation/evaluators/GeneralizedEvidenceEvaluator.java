package com.alsayed.financial_advisor_ai.evaluation.evaluators;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 4 H2: Generalized Evidence Evaluator.
 * Supports multiple valid evidence pages, explicit evidence chunk IDs, and evidence spans
 * while maintaining backward compatibility with single target-page benchmark formats.
 */
@Slf4j
@Component
public class GeneralizedEvidenceEvaluator {

    public record MatchResult(
            boolean isMatch,
            int rank,
            String matchType,
            String matchedLocation,
            Document matchedDocument
    ) {
        public static MatchResult noMatch() {
            return new MatchResult(false, -1, "NO_MATCH", "NONE", null);
        }
    }

    /**
     * Evaluates retrieved documents against the authorized evidence locations defined in the question.
     * Evaluation order:
     * 1. Specific Chunk ID (highest precision)
     * 2. Valid Pages (explicit multi-page or single source_page)
     * 3. Evidence Span (range of contiguous pages)
     * 4. Content / Keyword fallback (for semantic and legacy support)
     */
    public MatchResult evaluateRetrieval(EvaluationQuestion question, List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return MatchResult.noMatch();
        }

        // Build valid target pages set
        Set<Integer> validPages = new HashSet<>();
        if (question.getValidPages() != null && !question.getValidPages().isEmpty()) {
            validPages.addAll(question.getValidPages());
        }
        if (question.getSourcePage() != null) {
            validPages.add(question.getSourcePage());
        }

        // Build valid chunk IDs set
        Set<String> validChunkIds = new HashSet<>();
        if (question.getValidChunkIds() != null && !question.getValidChunkIds().isEmpty()) {
            validChunkIds.addAll(question.getValidChunkIds());
        }
        if (question.getRelevantChunkIds() != null && !question.getRelevantChunkIds().isEmpty()) {
            validChunkIds.addAll(question.getRelevantChunkIds());
        }

        String gold = question.getGroundTruth() != null ? question.getGroundTruth().toLowerCase().trim() : "";

        for (int r = 0; r < retrievedDocs.size(); r++) {
            Document doc = retrievedDocs.get(r);
            int rank = r + 1;
            String docId = doc.getId();

            // 1. Evidence Identity match (Priority 1)
            if (docId != null && !validChunkIds.isEmpty() && validChunkIds.contains(docId)) {
                return new MatchResult(true, rank, "CHUNK_ID_MATCH", "chunk_id=" + docId, doc);
            }

            // 2. Page Location match
            Object pObj = doc.getMetadata().get("page_number");
            int docPage = pObj instanceof Number ? ((Number) pObj).intValue() : -1;
            if (docPage > 0) {
                if (validPages.contains(docPage)) {
                    return new MatchResult(true, rank, "VALID_PAGE_MATCH", "page=" + docPage, doc);
                }
                if (question.getEvidenceSpan() != null && question.getEvidenceSpan().containsPage(docPage)) {
                    return new MatchResult(true, rank, "EVIDENCE_SPAN_MATCH", "span=" + question.getEvidenceSpan().getStartPage() + "-" + question.getEvidenceSpan().getEndPage(), doc);
                }
            }

            // 3. Keyword / Content match (Legacy & semantic compatibility)
            if (!gold.isEmpty()) {
                String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
                String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
                String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();
                if (text.contains(gold) || sec.contains(gold) || tbl.contains(gold)) {
                    return new MatchResult(true, rank, "KEYWORD_CONTENT_MATCH", "keyword=" + gold, doc);
                }
            }
        }

        return MatchResult.noMatch();
    }
}
