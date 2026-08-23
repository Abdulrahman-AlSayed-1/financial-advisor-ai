package com.alsayed.financial_advisor_ai.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Compact DTO for retrieval debugging and diagnostics.
 * Contains essential metadata and scores for retrieved chunks without full content.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalDebugResult {
    
    private int rank;
    private double score;
    private Integer page;
    private String section;
    private String tableId;
    private String rowEntity;
    private String columnEntity;
    private String metric;
    private String period;
    private String unit;
    private String value;
    private String chunkType;
    private String chunkId;
    private String content;
    
    /**
     * Create a RetrievalDebugResult from a Document with metadata extraction.
     * Handles missing metadata safely.
     */
    public static RetrievalDebugResult fromDocument(int rank, double score, org.springframework.ai.document.Document document) {
        java.util.Map<String, Object> metadata = document.getMetadata();
        
        return RetrievalDebugResult.builder()
                .rank(rank)
                .score(score)
                .page(getIntMetadata(metadata, "page_number"))
                .section(getStringMetadata(metadata, "section"))
                .tableId(getStringMetadata(metadata, "table_id"))
                .rowEntity(getStringMetadata(metadata, "row_entity"))
                .columnEntity(getStringMetadata(metadata, "column_entity"))
                .metric(getStringMetadata(metadata, "metric"))
                .period(getStringMetadata(metadata, "period"))
                .unit(getStringMetadata(metadata, "unit"))
                .value(getStringMetadata(metadata, "value"))
                .chunkType(getStringMetadata(metadata, "chunk_type"))
                .chunkId(document.getId())
                .content(document.getText())
                .build();
    }
    
    private static String getStringMetadata(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value != null ? value.toString() : null;
    }
    
    private static Integer getIntMetadata(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
