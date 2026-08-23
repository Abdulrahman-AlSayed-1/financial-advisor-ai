package com.alsayed.financial_advisor_ai.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class EvaluationQuestion {
    private String id;

    @JsonProperty("question")
    private String questionText;

    @JsonProperty("ground_truth")
    private String groundTruth;

    @JsonProperty("source_context")
    private String sourceContext;

    @JsonProperty("source_section")
    private String sourceSection;

    @JsonProperty("source_page")
    private Integer sourcePage;

    @JsonProperty("content_type")
    private String contentType;

    @JsonProperty("relevant_chunk_ids")
    @Deprecated
    private List<String> relevantChunkIds;

    @JsonProperty("valid_pages")
    private List<Integer> validPages;

    @JsonProperty("valid_chunk_ids")
    private List<String> validChunkIds;

    @JsonProperty("evidence_span")
    private EvidenceSpan evidenceSpan;

    @JsonProperty("evidence_groups")
    private List<EvidenceGroup> evidenceGroups;

    @JsonProperty("required_evidence")
    private List<AtomicEvidence> requiredEvidence;
}