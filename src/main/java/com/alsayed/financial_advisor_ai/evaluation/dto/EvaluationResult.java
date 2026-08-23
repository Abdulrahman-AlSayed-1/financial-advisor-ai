package com.alsayed.financial_advisor_ai.evaluation.dto;

import lombok.Data;

import java.util.List;

@Data
public class EvaluationResult {
    private String questionId;
    private String questionText;
    private String groundTruth;
    private String userAnswer;
    private List<String> contexts;
    
    // Retrieval metrics (Layer 1)
    private Boolean hitAt5Pass;
    private Boolean hitAt10Pass;
    
    // Generation metrics (Layer 2)
    private Boolean relevancyPass;
    private float relevancyScore;
    private Boolean answerCorrectnessPass;
    private float answerCorrectnessScore;
    
    // Grounding metrics (Layer 3)
    private Boolean faithfulnessPass;
    private float faithfulnessScore;
    private Boolean contextRecallPass;
    private float contextRecallScore;
    
    private long latency;
}
