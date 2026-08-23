package com.alsayed.financial_advisor_ai.model.raw;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Embedded image metadata extracted from PyMuPDF get_images().
 */
public record RawPdfImage(
    @JsonProperty("image_index") int imageIndex,
    @JsonProperty("bbox") double[] bbox,
    @JsonProperty("width_px") int widthPx,
    @JsonProperty("height_px") int heightPx,
    @JsonProperty("image_path") String imagePath,
    @JsonProperty("xref") int xref
) {
    public double getX0() { return bbox != null && bbox.length > 0 ? bbox[0] : 0.0; }
    public double getY0() { return bbox != null && bbox.length > 1 ? bbox[1] : 0.0; }
    public double getX1() { return bbox != null && bbox.length > 2 ? bbox[2] : 0.0; }
    public double getY1() { return bbox != null && bbox.length > 3 ? bbox[3] : 0.0; }
}
