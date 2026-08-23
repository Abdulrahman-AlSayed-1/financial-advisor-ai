package com.alsayed.financial_advisor_ai.model.raw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Model representing a single page extraction from PyMuPDF.
 * Uses canonical Top-Left coordinates (x0 <= x1, y0 <= y1, origin at Top-Left).
 */
public record RawPdfPage(
    @JsonProperty("page_number") int pageNumber,
    @JsonProperty("width") double width,
    @JsonProperty("height") double height,
    @JsonProperty("text_blocks") List<RawPdfTextBlock> textBlocks,
    @JsonProperty("tables") List<RawPdfTable> tables,
    @JsonProperty("images") List<RawPdfImage> images,
    @JsonProperty("drawings") List<RawPdfDrawing> drawings,
    @JsonProperty("page_image_path") String pageImagePath
) {}
