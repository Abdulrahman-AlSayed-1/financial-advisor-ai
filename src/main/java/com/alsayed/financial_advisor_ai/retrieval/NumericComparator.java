package com.alsayed.financial_advisor_ai.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performs numeric comparison and sorting for comparative queries.
 * 
 * This component sorts documents by their numeric values and selects
 * the top/bottom N results based on the comparison operation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NumericComparator {

    private final StructuredCandidateRetriever candidateRetriever;

    /**
     * Compare and sort documents by their numeric values.
     * 
     * Returns the top N or bottom N documents based on the comparison operation.
     */
    public List<RankedDocument> compareAndSort(List<Document> candidates, QuerySignals signals) {
        if (!signals.requiresNumericComparison()) {
            log.debug("Query does not require numeric comparison");
            return candidates.stream()
                .map(doc -> new RankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }

        log.debug("Performing numeric comparison for {} candidates with operation: {}", 
            candidates.size(), signals.getOperation());

        // Extract numeric values from candidates
        List<RankedDocument> ranked = new ArrayList<>();
        for (Document doc : candidates) {
            Double value = candidateRetriever.extractNumericValue(doc);
            if (value != null) {
                ranked.add(new RankedDocument(doc, value, 0));
            } else {
                log.debug("Skipping document with no numeric value: {}", doc.getId());
            }
        }

        if (ranked.isEmpty()) {
            log.warn("No candidates with metadata numeric values found - returning candidates for LLM evaluation");
            return candidates.stream()
                .map(doc -> new RankedDocument(doc, 0.0, 0))
                .collect(Collectors.toList());
        }

        // Sort based on comparison operation
        if (signals.requiresDescendingSort()) {
            ranked.sort(Comparator.comparingDouble(RankedDocument::numericValue).reversed());
        } else if (signals.requiresAscendingSort()) {
            ranked.sort(Comparator.comparingDouble(RankedDocument::numericValue));
        }

        // Assign ranks
        for (int i = 0; i < ranked.size(); i++) {
            ranked.set(i, new RankedDocument(
                ranked.get(i).document(),
                ranked.get(i).numericValue(),
                i + 1
            ));
        }

        // Apply limit if specified
        Integer limit = signals.getLimit();
        if (limit != null && limit > 0) {
            ranked = ranked.stream()
                .limit(limit)
                .collect(Collectors.toList());
        }

        log.debug("Numeric comparison complete: {} results", ranked.size());

        return ranked;
    }

    /**
     * Group candidates by table ID for aggregation.
     */
    public Map<String, List<RankedDocument>> groupByTable(List<RankedDocument> ranked) {
        return ranked.stream()
            .collect(Collectors.groupingBy(doc -> 
                candidateRetriever.getTableId(doc.document())));
    }

    /**
     * Find the maximum value among candidates.
     */
    public Optional<RankedDocument> findMaximum(List<Document> candidates) {
        return candidates.stream()
            .map(doc -> {
                Double value = candidateRetriever.extractNumericValue(doc);
                if (value != null) {
                    return new RankedDocument(doc, value, 0);
                }
                return null;
            })
            .filter(Objects::nonNull)
            .max(Comparator.comparingDouble(RankedDocument::numericValue));
    }

    /**
     * Find the minimum value among candidates.
     */
    public Optional<RankedDocument> findMinimum(List<Document> candidates) {
        return candidates.stream()
            .map(doc -> {
                Double value = candidateRetriever.extractNumericValue(doc);
                if (value != null) {
                    return new RankedDocument(doc, value, 0);
                }
                return null;
            })
            .filter(Objects::nonNull)
            .min(Comparator.comparingDouble(RankedDocument::numericValue));
    }

    /**
     * Wrapper for document with its numeric value and rank.
     */
    public record RankedDocument(Document document, double numericValue, int rank) {}
}
