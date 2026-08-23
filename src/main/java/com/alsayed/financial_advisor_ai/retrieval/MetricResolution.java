package com.alsayed.financial_advisor_ai.retrieval;

/**
 * Structured resolution result for a detected metric in a query.
 */
public record MetricResolution(
    CanonicalMetric canonicalMetric,
    String matchedPhrase,
    MatchType matchType,
    double score,
    int startIndex,
    int endIndex
) {
    public enum MatchType { 
        EXACT,   // Exact phrase match from the ontology
        PHRASE,  // Sub-phrase match
        ALIAS,   // Alias match
        NONE 
    }
}
