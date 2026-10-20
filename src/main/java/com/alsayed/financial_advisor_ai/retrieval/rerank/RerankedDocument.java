package com.alsayed.financial_advisor_ai.retrieval.rerank;

import org.springframework.ai.document.Document;

/**
 * Standard contract for a document paired with its reranked relevance score.
 */
public record RerankedDocument(Document document, double score) {

    /**
     * Alias for backward compatibility with existing test suites.
     */
    public double rerankedScore() {
        return score;
    }
}
