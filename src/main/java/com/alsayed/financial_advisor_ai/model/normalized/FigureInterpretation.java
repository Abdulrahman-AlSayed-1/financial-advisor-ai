package com.alsayed.financial_advisor_ai.model.normalized;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Normalized domain model for figure/chart interpretations.
 */
public record FigureInterpretation(
    @JsonProperty("figure_title") String figureTitle,
    @JsonProperty("figure_type") String figureType,
    @JsonProperty("metric") String metric,
    @JsonProperty("unit") String unit,
    @JsonProperty("detected_legend_series") List<String> detectedLegendSeries,
    @JsonProperty("panels") List<PanelInterpretation> panels,
    @JsonProperty("benchmark") String benchmark,
    @JsonProperty("notes") List<String> notes,
    @JsonProperty("diagnostic_status") String diagnosticStatus
) {
    public FigureInterpretation(
            String figureTitle,
            String figureType,
            String metric,
            String unit,
            List<PanelInterpretation> panels,
            List<String> notes,
            String diagnosticStatus
    ) {
        this(figureTitle, figureType, metric, unit, List.of(), panels, null, notes, diagnosticStatus);
    }

    public static FigureInterpretation failed(String errorReason) {
        return new FigureInterpretation(
            "", "unknown", "", "", List.of(), List.of(), null, List.of(errorReason), "FAILED"
        );
    }
}
