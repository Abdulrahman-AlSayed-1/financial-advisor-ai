package com.alsayed.financial_advisor_ai.evaluation.evaluators;

import com.alsayed.financial_advisor_ai.evaluation.dto.AtomicEvidence;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvidenceGroup;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Evidence-based retrieval evaluator that determines if retrieved context
 * contains sufficient evidence to answer a question, independent of chunk IDs.
 */
@Slf4j
@Component
public class EvidenceBasedRetrievalEvaluator {

    /**
     * Evaluates whether the retrieved chunks contain sufficient evidence for the question.
     * Prefers atomic evidence (entity+metric+value+period) over loose keyword matching.
     * 
     * @param question The evaluation question with evidence groups or atomic evidence
     * @param retrievedChunks List of retrieved documents
     * @param k The number of chunks to consider (for Hit@K)
     * @return EvidenceMatchResult with detailed matching information
     */
    public EvidenceMatchResult evaluateEvidence(
            EvaluationQuestion question,
            List<Document> retrievedChunks,
            int k) {

        // Prefer atomic evidence if available
        if (question.getRequiredEvidence() != null && !question.getRequiredEvidence().isEmpty()) {
            return evaluateAtomicEvidence(question, retrievedChunks, k);
        }

        // Fall back to legacy evidence groups
        if (question.getEvidenceGroups() == null || question.getEvidenceGroups().isEmpty()) {
            log.warn("Question has no evidence groups defined: {}", question.getQuestionText());
            return new EvidenceMatchResult(false, List.of(), List.of(), "No evidence groups defined");
        }

        // Take only top K chunks
        List<Document> topKChunks = retrievedChunks.stream()
                .limit(k)
                .toList();

        boolean isTableQuestion = "table".equalsIgnoreCase(question.getContentType());
        String combinedContext = topKChunks.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase();
        String normalizedCombinedContext = normalizeText(combinedContext);

        List<String> matchedEvidence = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();

        // Check each evidence group
        for (EvidenceGroup group : question.getEvidenceGroups()) {
            if (group.getRequiredTerms() == null || group.getRequiredTerms().isEmpty()) {
                continue;
            }

            boolean groupMatched;
            if (isTableQuestion) {
                // For structured/table questions, require terms to co-occur within the SAME chunk
                groupMatched = topKChunks.stream().anyMatch(chunk -> {
                    String chunkNorm = normalizeText(chunk.getText());
                    return group.getRequiredTerms().stream()
                            .allMatch(term -> chunkNorm.contains(normalizeText(term)));
                });
            } else {
                groupMatched = group.getRequiredTerms().stream()
                        .allMatch(term -> normalizedCombinedContext.contains(normalizeText(term)));
            }

            if (groupMatched) {
                matchedEvidence.addAll(group.getRequiredTerms());
            } else {
                missingEvidence.addAll(group.getRequiredTerms());
            }
        }

        // All evidence groups must be matched for a hit
        boolean allGroupsMatched = missingEvidence.isEmpty();

        String reason = allGroupsMatched
                ? "All evidence groups matched"
                : "Missing evidence: " + String.join(", ", missingEvidence);

        return new EvidenceMatchResult(allGroupsMatched, matchedEvidence, missingEvidence, reason);
    }

    /**
     * Evaluates using atomic evidence units (entity+metric+value+period).
     * Requires atomic evidence to match within at least one individual chunk in top-K.
     */
    private EvidenceMatchResult evaluateAtomicEvidence(
            EvaluationQuestion question,
            List<Document> retrievedChunks,
            int k) {

        // Take only top K chunks
        List<Document> topKChunks = retrievedChunks.stream()
                .limit(k)
                .toList();

        List<String> matchedEvidence = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();

        // Check each atomic evidence unit against individual chunks in top-K
        for (AtomicEvidence evidence : question.getRequiredEvidence()) {
            StringBuilder sb = new StringBuilder();
            if (evidence.getEntity() != null) sb.append(evidence.getEntity()).append(" ");
            if (evidence.getMetric() != null) sb.append(evidence.getMetric()).append(" ");
            if (evidence.getValue() != null) sb.append(evidence.getValue()).append(" ");
            if (evidence.getPeriod() != null) sb.append(evidence.getPeriod());
            String evidenceDescription = sb.toString().trim();

            boolean matchedInAnyChunk = topKChunks.stream().anyMatch(chunk -> evidence.matches(chunk.getText()));

            if (matchedInAnyChunk) {
                matchedEvidence.add(evidenceDescription);
            } else {
                if (evidence.getOptional() == null || !evidence.getOptional()) {
                    missingEvidence.add(evidenceDescription);
                }
            }
        }

        // All non-optional evidence must be matched for a hit
        boolean allMatched = missingEvidence.isEmpty();

        String reason = allMatched
                ? "All atomic evidence matched"
                : "Missing atomic evidence: " + String.join(", ", missingEvidence);

        return new EvidenceMatchResult(allMatched, matchedEvidence, missingEvidence, reason);
    }

    /**
     * Normalizes text for robust matching by handling:
     * - Case insensitivity
     * - Punctuation removal
     * - Whitespace normalization
     * - Common abbreviations
     * - Percentage symbols
     * - Hyphens
     */
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text.toLowerCase();

        // Remove percentage symbols
        normalized = normalized.replace("%", "");

        // Remove common punctuation but keep alphanumeric and spaces
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");

        // Normalize whitespace
        normalized = normalized.replaceAll("\\s+", " ").trim();

        // Handle common abbreviations
        normalized = normalized.replace("u.s.", "us");
        normalized = normalized.replace("u s", "us");
        
        // Handle period variations
        normalized = normalized.replace("1-year", "1y");
        normalized = normalized.replace("3-year", "3y");
        normalized = normalized.replace("5-year", "5y");
        normalized = normalized.replace("10-year", "10y");
        normalized = normalized.replace("1-year", "1y");
        normalized = normalized.replace("12-mo", "12mo");
        normalized = normalized.replace("1-mo", "1mo");
        normalized = normalized.replace("3-mo", "3mo");

        return normalized;
    }

    /**
     * Result of evidence matching evaluation.
     */
    public record EvidenceMatchResult(
            boolean matched,
            List<String> matchedEvidence,
            List<String> missingEvidence,
            String reason
    ) {}
}
