package com.alsayed.financial_advisor_ai.model.raw;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Raw table representation directly from PyMuPDF find_tables().
 */
public record RawPdfTable(
    @JsonProperty("table_index") int tableIndex,
    @JsonProperty("bbox") double[] bbox,
    @JsonProperty("row_count") int rowCount,
    @JsonProperty("col_count") int colCount,
    @JsonProperty("raw_grid") List<List<String>> rawGrid,
    @JsonProperty("markdown") String markdown,
    @JsonProperty("cell_bboxes") List<double[]> cellBboxes
) {
    public double getX0() { return bbox != null && bbox.length > 0 ? bbox[0] : 0.0; }
    public double getY0() { return bbox != null && bbox.length > 1 ? bbox[1] : 0.0; }
    public double getX1() { return bbox != null && bbox.length > 2 ? bbox[2] : 0.0; }
    public double getY1() { return bbox != null && bbox.length > 3 ? bbox[3] : 0.0; }
}
