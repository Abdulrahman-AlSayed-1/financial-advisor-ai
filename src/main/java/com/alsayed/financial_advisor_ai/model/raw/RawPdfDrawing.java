package com.alsayed.financial_advisor_ai.model.raw;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Vector drawing metadata extracted from PyMuPDF get_drawings().
 */
public record RawPdfDrawing(
    @JsonProperty("drawing_index") int drawingIndex,
    @JsonProperty("bbox") double[] bbox,
    @JsonProperty("rect_count") int rectCount,
    @JsonProperty("line_count") int lineCount,
    @JsonProperty("has_fills") boolean hasFills
) {
    public double getX0() { return bbox != null && bbox.length > 0 ? bbox[0] : 0.0; }
    public double getY0() { return bbox != null && bbox.length > 1 ? bbox[1] : 0.0; }
    public double getX1() { return bbox != null && bbox.length > 2 ? bbox[2] : 0.0; }
    public double getY1() { return bbox != null && bbox.length > 3 ? bbox[3] : 0.0; }
    public double getWidth() { return getX1() - getX0(); }
    public double getHeight() { return getY1() - getY0(); }
}
