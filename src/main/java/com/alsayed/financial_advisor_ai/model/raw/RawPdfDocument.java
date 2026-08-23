package com.alsayed.financial_advisor_ai.model.raw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Top-level data model representing a raw PDF extraction output from PyMuPDF.
 */
public record RawPdfDocument(
    @JsonProperty("source_file") String sourceFile,
    @JsonProperty("extraction_timestamp") String extractionTimestamp,
    @JsonProperty("total_pages") int totalPages,
    @JsonProperty("pages") List<RawPdfPage> pages
) {}
