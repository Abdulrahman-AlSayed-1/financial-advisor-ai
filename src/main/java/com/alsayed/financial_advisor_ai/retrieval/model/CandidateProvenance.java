package com.alsayed.financial_advisor_ai.retrieval.model;

import java.util.Set;

/**
 * Detailed audit trail (provenance) for a retrieval candidate across channels.
 */
public record CandidateProvenance(
        Integer denseRank,
        Double denseScore,
        Integer lexicalRank,
        Double lexicalScore,
        Integer structuredRank,
        Double structuredScore,
        Set<String> sources,
        double rrfScore,
        double metadataScore
) {}
