package com.alsayed.financial_advisor_ai.model.normalized;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import java.util.List;

/**
 * Normalized section containing a section title, page number, and associated text blocks.
 */
public record NormalizedTextSection(
    String sectionHeading,
    int pageNumber,
    String accumulatedText,
    List<RawPdfTextBlock> sourceBlocks
) {}
