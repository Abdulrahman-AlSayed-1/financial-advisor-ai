package com.alsayed.financial_advisor_ai.model.region;

import com.alsayed.financial_advisor_ai.model.raw.*;
import java.util.List;

/**
 * Represents a segmented region of a page with an assigned RegionType.
 */
public record ContentRegion(
    int pageNumber,
    RegionType type,
    double[] bbox,
    List<RawPdfTextBlock> enclosedText,
    RawPdfTable rawTable,
    RawPdfImage rawImage,
    RawPdfDrawing rawDrawing,
    String croppedImagePath,
    String paddedImagePath
) {}
