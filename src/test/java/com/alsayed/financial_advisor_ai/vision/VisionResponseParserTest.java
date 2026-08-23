package com.alsayed.financial_advisor_ai.vision;

import com.alsayed.financial_advisor_ai.chunking.CanonicalTable;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableParser;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VisionResponseParserTest {

    private final CanonicalTableParser tableParser = new CanonicalTableParser();
    private final VisionResponseParser parser = new VisionResponseParser(tableParser);

    @Test
    void testParseFigureValidJson() {
        String json = """
                ```json
                {
                  "figure_title": "Local Sovereign Currency Yields",
                  "figure_type": "bar_chart",
                  "metric": "Local Currency Yield",
                  "unit": "%",
                  "panels": [
                    {
                      "panel_title": "EM Local Currency Yields % (1 Year)",
                      "data_points": [
                        {
                          "entity": "Nigerian Naira (NGN)",
                          "series": "1 Yr Prior",
                          "value": "11.37",
                          "unit": "%",
                          "metric": "Local Currency Yield"
                        },
                        {
                          "entity": "Japanese Yen (JPY)",
                          "series": "Current",
                          "value": "0.85",
                          "unit": "%",
                          "metric": "Local Currency Yield"
                        }
                      ]
                    }
                  ],
                  "notes": ["Page 45 visual extraction"],
                  "diagnostic_status": "SUCCESS"
                }
                ```
                """;

        VisionResponse response = VisionResponse.success(json, "gemini", 120, false);
        FigureInterpretation interpretation = parser.parseFigure(response);

        assertNotNull(interpretation);
        assertEquals("Local Sovereign Currency Yields", interpretation.figureTitle());
        assertEquals("bar_chart", interpretation.figureType());
        assertEquals("Local Currency Yield", interpretation.metric());
        assertNotNull(interpretation.panels());
        assertEquals(1, interpretation.panels().size());

        var panel = interpretation.panels().get(0);
        assertEquals("EM Local Currency Yields % (1 Year)", panel.panelTitle());
        assertEquals(2, panel.dataPoints().size());

        var dp1 = panel.dataPoints().get(0);
        assertEquals("Nigerian Naira (NGN)", dp1.getEntity());
        assertEquals("1 Yr Prior", dp1.getSeries());
        assertEquals("11.37", dp1.getValue());
    }

    @Test
    void testParseFigureFailureResponse() {
        VisionResponse failure = VisionResponse.failure("Timeout", "gemini", 60000);
        FigureInterpretation interpretation = parser.parseFigure(failure);

        assertNotNull(interpretation);
        assertEquals("FAILED", interpretation.diagnosticStatus());
    }
}
