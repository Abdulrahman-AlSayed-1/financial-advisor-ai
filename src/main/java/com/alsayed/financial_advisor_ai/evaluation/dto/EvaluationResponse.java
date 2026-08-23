package com.alsayed.financial_advisor_ai.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResponse {
    private String answer;
    
    // Raw retrieved documents in original retrieval order (for Hit@K and retrieval metrics)
    private List<Document> retrievedDocuments;
    
    // Deduplicated contexts for LLM-based evaluation (relevancy, faithfulness, context recall)
    private List<String> contexts;
}