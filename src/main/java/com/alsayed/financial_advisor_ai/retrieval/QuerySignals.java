package com.alsayed.financial_advisor_ai.retrieval;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Structured signals extracted from a query for comparative retrieval.
 * 
 * This class represents the parsed intent of a query, including entities,
 * metrics, periods, and comparison operations. It enables structured
 * retrieval for comparative/aggregation queries like "highest", "lowest", "top N".
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class QuerySignals {
    
    private String rawQuery;

    /**
     * Entity/domain being queried (e.g., "S&P 500 sectors", "asset classes")
     */
    private Set<String> entities;
    
    /**
     * Table or domain context (e.g., "sector", "asset_class", "correlation")
     */
    private Set<String> tableTypes;
    
    /**
     * Metric being queried (e.g., "Return (%)", "Yield (%)", "Spread")
     */
    private String metric;

    /**
     * Normalized canonical metric enum.
     */
    private CanonicalMetric canonicalMetric;

    /**
     * All normalized canonical metrics detected in query.
     */
    private Set<CanonicalMetric> canonicalMetrics = new java.util.LinkedHashSet<>();

    /**
     * Unit detected in query, e.g. "basis points", "%"
     */
    private String canonicalUnit;

    /**
     * If this is a derived numerical query, these are the underlying evidence metrics required.
     */
    private java.util.List<String> derivedEvidenceMetrics = new java.util.ArrayList<>();
    
    /**
     * Period constraint (e.g., "YTD", "1Y", "5 Years")
     */
    private String period;
    
    /**
     * Structured Tenors / Maturities constraint (e.g., "10y", "2y", "30y", "3m")
     */
    private Set<String> tenors = new java.util.LinkedHashSet<>();
    
    /**
     * Date/time constraint (e.g., "September 30, 2024")
     */
    private String dateConstraint;
    
    /**
     * Comparison operation type
     */
    private ComparisonOperation operation;
    
    /**
     * Number of results requested (for top/bottom N queries)
     */
    private Integer limit;
    
    /**
     * Grouping/entity type (e.g., "sector", "country", "security")
     */
    private String groupBy;
    
    /**
     * Generalized query intent category.
     */
    private QueryIntent queryIntent = QueryIntent.GENERAL_DOCUMENT_QUERY;
    
    /**
     * Whether this is a comparative/aggregation query
     * -- SETTER --
     *  Set the comparative flag.
     * -- GETTER --
     *  Check if this is a comparative/aggregation query.


     */
    private boolean isComparative;

    /**
     * Types of comparison operations supported.
     */
    public enum ComparisonOperation {
        MAXIMUM,      // highest, largest, maximum
        MINIMUM,      // lowest, smallest, minimum
        TOP_N,        // top N
        BOTTOM_N,     // bottom N
        NONE          // ordinary factual query
    }
    
    /**
     * Check if this query requires numeric comparison.
     */
    public boolean requiresNumericComparison() {
        return isComparative && operation != ComparisonOperation.NONE;
    }
    
    /**
     * Check if this query requires sorting in descending order.
     */
    public boolean requiresDescendingSort() {
        return operation == ComparisonOperation.MAXIMUM || 
               operation == ComparisonOperation.TOP_N;
    }
    
    /**
     * Check if this query requires sorting in ascending order.
     */
    public boolean requiresAscendingSort() {
        return operation == ComparisonOperation.MINIMUM || 
               operation == ComparisonOperation.BOTTOM_N;
    }
}
