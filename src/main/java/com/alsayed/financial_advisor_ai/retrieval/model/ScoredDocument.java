package com.alsayed.financial_advisor_ai.retrieval.model;

import org.springframework.ai.document.Document;

/**
 * Encapsulates a Document with its final blended retrieval score.
 */
public record ScoredDocument(
        Document document,
        double finalScore
) {}
