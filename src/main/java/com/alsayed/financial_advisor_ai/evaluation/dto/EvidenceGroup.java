package com.alsayed.financial_advisor_ai.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a group of required evidence terms that must be present
 * in the retrieved context for a retrieval evaluation to pass.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceGroup {
    /**
     * List of terms that must be present in the retrieved context.
     * All terms in a single group must be present for the group to be considered matched.
     */
    private List<String> requiredTerms;
}
