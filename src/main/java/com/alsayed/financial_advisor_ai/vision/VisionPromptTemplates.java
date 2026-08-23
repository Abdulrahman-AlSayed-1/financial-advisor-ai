package com.alsayed.financial_advisor_ai.vision;

/**
 * Centralized prompt templates for Vision LLM extraction tasks.
 */
public final class VisionPromptTemplates {

    private VisionPromptTemplates() {}

    public static final String FIGURE_INTERPRETATION_PROMPT = """
            You are a high-precision financial chart and figure data extractor.
            Analyze this image of a financial chart/figure thoroughly and extract all structured data exhaustively.

            Contextual Information:
            - Page Number: %s
            - Section / Topic: %s
            - Surrounding Text Context: %s

            Extraction Instructions:
            1. Identify the overall figure title and sub-panel headings.
            2. Identify the chart type: e.g. "range_bar_chart", "grouped_bar_chart", "stacked_bar_chart", "line_chart", "scatter_plot", "donut_chart", "true_table", "multi_panel", "other".
            3. Detect the series/legend names. If there is no explicit legend, infer the series name from the title, axis, or labels. List them in "detected_legend_series".
            4. Extract EVERY distinct data point, text label, and annotated value you can see. Do not skip data because it doesn't fit a perfect grid.
            5. For EVERY entity / category / asset class, extract all corresponding data points:
               - "entity": the name of the category, asset class, sector, or row label (e.g. "Bonds", "Equities", "Information Technology", "Transition").
               - "series": the series or metric name (e.g. "Current", "Count", "Yield").
               - "value": the exact numerical value or text shown (preserve negatives and decimals).
               - "unit": unit of measurement (e.g. "x", "%%", "bps", "$", "years", "count").
               - "metric": underlying metric being measured.
               - "confidence": "HIGH", "MEDIUM", or "LOW".

            CRITICAL ACCURACY RULES:
            - Extract exhaustively: Capture every data point, floating label, and asset class grouping shown in the image.
            - Do NOT treat background grid axis ticks (e.g. 0, 10, 20 on the y-axis) as data points. Focus on the actual plotted data values and explicit labels.
            - Preserve exact entity names as printed on the chart.
            - If the visual is a scatter plot or lacks a strict grid, do your best to associate floating labels with their nearest data points.
            - Return output STRICTLY in valid JSON format matching the schema below without markdown preambles (do NOT wrap in ```json).

            Response JSON Schema:
            {
              "figure_title": "...",
              "figure_type": "range_bar_chart|grouped_bar_chart|stacked_bar_chart|line_chart|scatter_plot|donut_chart|true_table|multi_panel|other",
              "metric": "...",
              "unit": "...",
              "detected_legend_series": ["..."],
              "panels": [
                {
                  "panel_title": "...",
                  "data_points": [
                    {
                      "entity": "...",
                      "series": "...",
                      "value": "...",
                      "unit": "...",
                      "metric": "...",
                      "confidence": "HIGH|MEDIUM|LOW"
                    }
                  ]
                }
              ],
              "benchmark": "...",
              "notes": ["..."],
              "diagnostic_status": "SUCCESS"
            }
            """;

    public static final String COMPLEX_TABLE_PROMPT = """
            You are a financial table extraction specialist.
            Extract the tabular data from this image into clean, structured Markdown table and JSON.

            Contextual Information:
            - Page Number: %s
            - Section: %s

            Rules:
            1. Capture all multi-level headers, spanning columns, and row hierarchies.
            2. Preserve exact cell numbers, symbols (+/-), and percentages.
            3. Output ONLY a valid JSON object matching the schema below.

            Response JSON Schema:
            {
              "table_title": "...",
              "markdown": "| Header 1 | Header 2 |...\\n|---|---|...\\n| Data 1 | Data 2 |...",
              "notes": ["..."]
            }
            """;
}
