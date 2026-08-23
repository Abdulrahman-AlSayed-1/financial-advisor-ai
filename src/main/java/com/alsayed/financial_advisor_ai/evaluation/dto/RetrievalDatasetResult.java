package com.alsayed.financial_advisor_ai.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result for retrieval-only dataset evaluation.
 * Contains aggregate Hit@K metrics and per-question results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalDatasetResult {
    
    private int totalQuestions;
    private int hitAt5Count;
    private int hitAt10Count;
    private double hitAt5Rate;
    private double hitAt10Rate;
    private List<RetrievalQuestionResult> questionResults;
}
