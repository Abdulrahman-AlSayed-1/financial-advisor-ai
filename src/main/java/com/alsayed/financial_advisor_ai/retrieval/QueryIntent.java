package com.alsayed.financial_advisor_ai.retrieval;

/**
 * Generalized query intent classes for intent-aware retrieval and reranking.
 * Designed to be domain-agnostic and document-independent.
 */
public enum QueryIntent {
    NUMERICAL_LOOKUP,
    COMPARATIVE_NUMERICAL,
    NARRATIVE_VIEW,
    EXPLANATION_WHY,
    DECLARATIVE_QUOTATION,
    GENERAL_DOCUMENT_QUERY
}
