package com.alsayed.financial_advisor_ai.model.normalized;

import com.alsayed.financial_advisor_ai.chunking.FigureDataPoint;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Sub-panel or single chart series group within a FigureInterpretation.
 */
public record PanelInterpretation(
    @JsonProperty("panel_title") String panelTitle,
    @JsonProperty("data_points") List<FigureDataPoint> dataPoints
) {}
