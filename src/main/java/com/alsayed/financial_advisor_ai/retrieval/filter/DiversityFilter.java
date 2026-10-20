package com.alsayed.financial_advisor_ai.retrieval.filter;

import com.alsayed.financial_advisor_ai.retrieval.model.ScoredDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Applies container diversity filtering to prevent candidate flooding from a single table or figure.
 * Ensures balanced evidence across document pages. Follows Single Responsibility Principle (SRP).
 */
@Slf4j
@Component
public class DiversityFilter {

    private static final int DEFAULT_GRANULAR_LIMIT = 5;
    private static final int DEFAULT_GENERIC_LIMIT = 1;

    /**
     * Filters ranked documents to ensure container diversity while respecting topK.
     *
     * @param sortedScores Candidate documents sorted by descending final score.
     * @param topK         Maximum number of documents to return.
     * @return Diversity-filtered list of documents up to topK.
     */
    public List<Document> filter(List<ScoredDocument> sortedScores, int topK) {
        if (sortedScores == null || sortedScores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> selected = new ArrayList<>();
        List<Document> deferred = new ArrayList<>();
        Map<String, Integer> sourceUnitCounts = new HashMap<>();

        for (ScoredDocument scoredDoc : sortedScores) {
            Document doc = scoredDoc.document();
            Map<String, Object> meta = doc.getMetadata();

            String sourceKey = deriveSourceKey(meta);
            String chunkType = String.valueOf(meta.getOrDefault("chunk_type", "doc"));

            int count = sourceUnitCounts.getOrDefault(sourceKey, 0);

            // Allow more granular chunks (rows, panels) from the same table/figure
            int allowedLimit = isGranularChunk(chunkType) ? DEFAULT_GRANULAR_LIMIT : DEFAULT_GENERIC_LIMIT;

            if (count < allowedLimit) {
                selected.add(doc);
                sourceUnitCounts.put(sourceKey, count + 1);
            } else {
                deferred.add(doc);
            }

            if (selected.size() >= topK) {
                break;
            }
        }

        // Backfill from deferred candidates if topK is not reached
        if (selected.size() < topK) {
            for (Document d : deferred) {
                if (selected.size() >= topK) break;
                selected.add(d);
            }
        }

        return selected;
    }

    private String deriveSourceKey(Map<String, Object> meta) {
        String chunkType = String.valueOf(meta.getOrDefault("chunk_type", "doc"));

        if (meta.get("figure_id") != null && !meta.get("figure_id").toString().isBlank()) {
            Object panelName = meta.getOrDefault("panel_title", meta.get("panel"));
            if ("figure_panel".equals(chunkType) && panelName != null) {
                return "fig_" + meta.get("figure_id") + "_p_" + panelName;
            } else {
                return "fig_" + meta.get("figure_id") + "_" + chunkType;
            }
        } else if (meta.get("table_id") != null && !meta.get("table_id").toString().isBlank()) {
            return "tbl_" + meta.get("table_id") + "_" + chunkType;
        } else {
            Object page = meta.getOrDefault("page_number", meta.get("page"));
            Object section = meta.getOrDefault("section", "doc");
            return "p_" + page + "_s_" + section;
        }
    }

    private boolean isGranularChunk(String chunkType) {
        return "table".equals(chunkType)
                || "table_row".equals(chunkType)
                || "figure_panel".equals(chunkType)
                || "figure_datapoints".equals(chunkType);
    }
}
