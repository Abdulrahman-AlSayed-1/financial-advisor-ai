package com.alsayed.financial_advisor_ai.retrieval.model;

import org.springframework.ai.document.Document;

/**
 * Encapsulates a Document alongside its multi-channel provenance and rank data.
 */
public record MergedDocument(
        Document document,
        CandidateProvenance provenance
) {}
