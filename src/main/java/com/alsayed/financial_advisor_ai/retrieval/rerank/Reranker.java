package com.alsayed.financial_advisor_ai.retrieval.rerank;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Strategy interface for reranking candidate documents based on query relevance.
 * Adheres to Dependency Inversion and Open/Closed principles.
 */
public interface Reranker {

    /**
     * Reranks candidate documents for the given query.
     *
     * @param query      The user prompt or search query.
     * @param candidates The list of retrieved candidate documents.
     * @return List of documents paired with their reranking score, ordered by descending score.
     */
    List<RerankedDocument> rerank(String query, List<Document> candidates);
}
