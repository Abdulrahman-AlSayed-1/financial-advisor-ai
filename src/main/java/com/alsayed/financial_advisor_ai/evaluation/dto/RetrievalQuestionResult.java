package com.alsayed.financial_advisor_ai.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result for a single question in retrieval-only dataset evaluation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalQuestionResult {
    
    private String questionId;
    private String questionText;
    private boolean hitAt5;
    private boolean hitAt10;
    private long retrievalTime;
    private int retrievedCount;
    private List<Integer> retrievedPages;
    private List<RetrievalDebugResult> topChunks;
}
