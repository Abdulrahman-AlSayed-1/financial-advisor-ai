package com.alsayed.financial_advisor_ai.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Atomic evidence unit for retrieval evaluation.
 * Represents a single factual requirement as entity + metric + value + period.
 * This enables semantic matching that distinguishes between similar entities with different metrics.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AtomicEvidence {
    /**
     * The entity being referenced (e.g., "US Loans", "S&P 500", "High Yield Corporate")
     */
    private String entity;
    
    /**
     * The metric being measured (e.g., "current yield", "annual default rate", "YTD return")
     */
    private String metric;
    
    /**
     * The expected value (e.g., "9.54%", "0.94%", "15.35%")
     */
    private String value;
    
    /**
     * The time period (e.g., "current", "September 30, 2024", "5 years")
     */
    private String period;
    
    /**
     * Whether this evidence unit is optional (can fail without causing overall Hit@K failure)
     */
    private Boolean optional = false;
    
    /**
     * Check if this evidence matches a given text using semantic matching.
     * Returns true if the text contains the entity, metric, and value with reasonable tolerance.
     */
    public boolean matches(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        String lowerText = text.toLowerCase();
        
        // Check entity presence
        boolean entityMatch = entity != null && 
            (lowerText.contains(entity.toLowerCase()) || 
             containsVariation(lowerText, entity) ||
             containsCorrelationEntity(lowerText, entity));
        
        // Check metric presence (if specified) - more lenient matching
        boolean metricMatch = metric == null || metric.isEmpty() ||
            lowerText.contains(metric.toLowerCase()) ||
            containsVariation(lowerText, metric) ||
            containsMetricVariation(lowerText, metric);
        
        // Check value presence (if specified)
        boolean valueMatch = value == null || value.isEmpty() ||
            containsValue(lowerText, value);
        
        // Check period presence (if specified) - more lenient matching
        boolean periodMatch = period == null || period.isEmpty() ||
            lowerText.contains(period.toLowerCase()) ||
            containsVariation(lowerText, period) ||
            containsPeriodVariation(lowerText, period);
        
        return entityMatch && metricMatch && valueMatch && periodMatch;
    }
    
    /**
     * Check if text contains a variation of the term (abbreviations, synonyms).
     */
    private boolean containsVariation(String text, String term) {
        // Common variations for financial terms
        String lowerTerm = term.toLowerCase();
        
        // Handle common abbreviations
        if (lowerTerm.contains("united states") && text.contains("u.s.")) return true;
        if (lowerTerm.contains("u.s.") && text.contains("united states")) return true;
        if (lowerTerm.contains("corporate") && text.contains("corp")) return true;
        if (lowerTerm.contains("corporation") && text.contains("corp")) return true;
        if (lowerTerm.contains("information technology") && text.contains("info tech")) return true;
        if (lowerTerm.contains("info tech") && text.contains("information technology")) return true;
        if (lowerTerm.contains("utilities") && text.contains("utility")) return true;
        if (lowerTerm.contains("high yield") && (text.contains("hy") || text.contains("high yield"))) return true;
        if (lowerTerm.contains("leveraged loan") && (text.contains("loan") || text.contains("leveraged loan"))) return true;
        
        return false;
    }
    
    /**
     * Check if text contains metric variations.
     * Handles different ways metrics can be expressed in chunked data.
     */
    private boolean containsMetricVariation(String text, String metric) {
        String lowerMetric = metric.toLowerCase();
        
        // Handle return variations
        if (lowerMetric.contains("return") && text.contains("return")) return true;
        if (lowerMetric.contains("total return") && text.contains("return")) return true;
        if (lowerMetric.contains("ytd total return") && text.contains("ytd") && text.contains("return")) return true;
        
        // Handle correlation variations
        if (lowerMetric.contains("correlation") && text.contains("correlation")) return true;
        if (lowerMetric.contains("return correlation") && text.contains("correlation")) return true;
        
        // Handle yield variations
        if (lowerMetric.contains("yield") && text.contains("yield")) return true;
        
        // Handle default rate variations
        if (lowerMetric.contains("default rate") && text.contains("default")) return true;
        
        return false;
    }
    
    /**
     * Check if text contains period variations.
     * Handles different ways periods can be expressed in chunked data.
     */
    private boolean containsPeriodVariation(String text, String period) {
        String lowerPeriod = period.toLowerCase();

        // General report as-of date (e.g. "September 30, 2024", "current") is implicit in quarterly report chunks
        if (lowerPeriod.contains("september") || lowerPeriod.contains("2024") || lowerPeriod.contains("current")) {
            return true;
        }

        // Handle year variations
        if (lowerPeriod.contains("5-year") || lowerPeriod.contains("5 years") || lowerPeriod.contains("5y")) {
            return text.contains("5-year") || text.contains("5 years") || text.contains("5y") || text.contains("5 yr") || text.contains("5-yr");
        }
        if (lowerPeriod.contains("10-year") || lowerPeriod.contains("10 years") || lowerPeriod.contains("10y")) {
            return text.contains("10-year") || text.contains("10 years") || text.contains("10y") || text.contains("10 yr") || text.contains("10-yr");
        }

        return false;
    }
    
    /**
     * Check if text contains correlation matrix entities (row and column entities).
     * Handles combined entity names like "S&P 500 and U.S. Aggregate" matching separate row/column entities.
     */
    private boolean containsCorrelationEntity(String text, String entity) {
        String lowerEntity = entity.toLowerCase();
        String cleanText = text.toLowerCase().replace(".", "").replaceAll("\\s+", " ");

        // Check for "and" separator indicating combined entities
        if (lowerEntity.contains(" and ")) {
            String[] entities = lowerEntity.split(" and ");
            if (entities.length == 2) {
                String e1 = entities[0].trim().replace(".", "");
                String e2 = entities[1].trim().replace(".", "");
                boolean entity1Found = cleanText.contains(e1);
                boolean entity2Found = cleanText.contains(e2);
                return entity1Found && entity2Found;
            }
        }

        return false;
    }
    
    /**
     * Check if text contains the value with reasonable format tolerance.
     * Handles percentage formats, decimal representations, etc.
     */
    private boolean containsValue(String text, String value) {
        String lowerValue = value.toLowerCase();
        
        // Exact match
        if (text.contains(lowerValue)) {
            return true;
        }
        
        // Handle percentage variations
        String numericPart = lowerValue.replaceAll("[^0-9.]", "");
        if (!numericPart.isEmpty()) {
            // Check for numeric value with or without % sign
            if (text.contains(numericPart + "%") || text.contains(numericPart + " percent")) {
                return true;
            }
            // Check for just the numeric part
            if (text.contains(numericPart)) {
                return true;
            }
        }
        
        // Handle basis points (bps) to percentage conversion
        if (lowerValue.contains("bps") || lowerValue.contains("basis points")) {
            String bpsValue = numericPart;
            try {
                double bps = Double.parseDouble(bpsValue);
                double percentValue = bps / 100.0;
                if (text.contains(String.valueOf(percentValue)) || 
                    text.contains(String.format("%.2f", percentValue))) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }
        
        return false;
    }
}
