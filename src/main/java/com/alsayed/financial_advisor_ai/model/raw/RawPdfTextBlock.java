package com.alsayed.financial_advisor_ai.model.raw;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Text block extracted by PyMuPDF get_text("dict").
 * Coordinates [x0, y0, x1, y1] are Top-Left origin.
 */
public record RawPdfTextBlock(
    @JsonProperty("text") String text,
    @JsonProperty("bbox") double[] bbox,
    @JsonProperty("font_name") String fontName,
    @JsonProperty("font_size") double fontSize,
    @JsonProperty("is_bold") boolean isBold,
    @JsonProperty("is_italic") boolean isItalic,
    @JsonProperty("block_index") int blockIndex
) {
    /**
     * Check if this block looks like a section/page heading based on font style and size.
     */
    public boolean isLikelyHeading() {
        return isBold && fontSize >= 11.0;
    }

    public double getX0() { return bbox != null && bbox.length > 0 ? bbox[0] : 0.0; }
    public double getY0() { return bbox != null && bbox.length > 1 ? bbox[1] : 0.0; }
    public double getX1() { return bbox != null && bbox.length > 2 ? bbox[2] : 0.0; }
    public double getY1() { return bbox != null && bbox.length > 3 ? bbox[3] : 0.0; }
}
