package com.alsayed.financial_advisor_ai.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an evidence span across multiple contiguous pages.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceSpan {

    @JsonProperty("start_page")
    private Integer startPage;

    @JsonProperty("end_page")
    private Integer endPage;

    public boolean containsPage(int page) {
        if (startPage != null && page < startPage) return false;
        if (endPage != null && page > endPage) return false;
        return true;
    }
}
