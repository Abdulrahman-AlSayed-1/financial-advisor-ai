package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.chunking.FigureDataPoint;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.normalized.PanelInterpretation;
import com.alsayed.financial_advisor_ai.vision.MultiSourceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * General regression test for visual chart understanding, legend mapping,
 * range invariants, negative values, and multi-series grounding.
 */
public class GeneralChartExtractionValidationTest {

    private MultiSourceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MultiSourceValidator();
    }

    @Test
    @DisplayName("Valid range chart with High, Low, Current, Average and negative values passes validation")
    void testValidRangeChartValidation() {
        FigureInterpretation validInterpretation = new FigureInterpretation(
                "Commodity Price Spreads: 5-Year High, Low, Average",
                "range_bar_chart",
                "Spread",
                "bps",
                List.of("Current", "High", "Low", "Average"),
                List.of(
                        new PanelInterpretation(
                                "Energy & Metals",
                                List.of(
                                        FigureDataPoint.builder().entity("Crude Oil").series("Current").value("45.2").unit("bps").metric("Spread").build(),
                                        FigureDataPoint.builder().entity("Crude Oil").series("High").value("120.0").unit("bps").metric("Spread").build(),
                                        FigureDataPoint.builder().entity("Crude Oil").series("Low").value("-35.5").unit("bps").metric("Spread").build(),
                                        FigureDataPoint.builder().entity("Crude Oil").series("Average").value("30.0").unit("bps").metric("Spread").build(),

                                        FigureDataPoint.builder().entity("Copper").series("Current").value("18.4").unit("bps").metric("Spread").build(),
                                        FigureDataPoint.builder().entity("Copper").series("High").value("65.0").unit("bps").metric("Spread").build(),
                                        FigureDataPoint.builder().entity("Copper").series("Low").value("10.2").unit("bps").metric("Spread").build(),
                                        FigureDataPoint.builder().entity("Copper").series("Average").value("25.0").unit("bps").metric("Spread").build()
                                )
                        )
                ),
                "Benchmark Spread: 25.0 bps",
                List.of("Source: Bloomberg as of 2024"),
                "SUCCESS"
        );

        assertTrue(validator.validateFigureInterpretation(validInterpretation),
                "Valid multi-series range chart with negative values should pass validation");
    }

    @Test
    @DisplayName("Range chart with inverted boundary (High < Low) must be rejected")
    void testInvertedRangeInvariantRejection() {
        FigureInterpretation invalidInterpretation = new FigureInterpretation(
                "Yield Analysis: High vs Low",
                "range_bar_chart",
                "Yield",
                "%",
                List.of("High", "Low"),
                List.of(
                        new PanelInterpretation(
                                "Sovereign Debt",
                                List.of(
                                        FigureDataPoint.builder().entity("Country A").series("High").value("4.2").unit("%").metric("Yield").build(),
                                        // Low is greater than High -> invalid geometry
                                        FigureDataPoint.builder().entity("Country A").series("Low").value("6.8").unit("%").metric("Yield").build()
                                )
                        )
                ),
                null,
                List.of(),
                "SUCCESS"
        );

        assertFalse(validator.validateFigureInterpretation(invalidInterpretation),
                "Figure interpretation with High < Low must be rejected by range invariant validation");
    }

    @Test
    @DisplayName("Unjustified hallucinated generic series names (Label, Series 1) must be rejected")
    void testHallucinatedSeriesRejection() {
        FigureInterpretation hallucinatedSeriesInterp = new FigureInterpretation(
                "Equity Valuation Multiples",
                "bar_chart",
                "P/E",
                "x",
                List.of("Current", "10-Yr Average"),
                List.of(
                        new PanelInterpretation(
                                null,
                                List.of(
                                        FigureDataPoint.builder().entity("Tech").series("Current").value("25.0").unit("x").metric("P/E").build(),
                                        // "Label" was not in detected_legend_series
                                        FigureDataPoint.builder().entity("Tech").series("Label").value("35.0").unit("x").metric("P/E").build()
                                )
                        )
                ),
                null,
                List.of(),
                "SUCCESS"
        );

        assertFalse(validator.validateFigureInterpretation(hallucinatedSeriesInterp),
                "Figure interpretation with hallucinated 'Label' series not in legend must be rejected");
    }

    @Test
    @DisplayName("Standard multi-series grouped bar chart without range semantics validates correctly")
    void testStandardMultiSeriesChartValidation() {
        FigureInterpretation groupedBarInterp = new FigureInterpretation(
                "GDP Growth by Region",
                "grouped_bar_chart",
                "GDP Growth",
                "%",
                List.of("2023", "2024E", "2025E"),
                List.of(
                        new PanelInterpretation(
                                null,
                                List.of(
                                        FigureDataPoint.builder().entity("US").series("2023").value("2.5").unit("%").metric("GDP Growth").build(),
                                        FigureDataPoint.builder().entity("US").series("2024E").value("2.8").unit("%").metric("GDP Growth").build(),
                                        FigureDataPoint.builder().entity("Eurozone").series("2023").value("0.5").unit("%").metric("GDP Growth").build(),
                                        FigureDataPoint.builder().entity("Eurozone").series("2024E").value("0.8").unit("%").metric("GDP Growth").build()
                                )
                        )
                ),
                null,
                List.of(),
                "SUCCESS"
        );

        assertTrue(validator.validateFigureInterpretation(groupedBarInterp),
                "Standard non-range multi-series bar chart should pass without enforcing range invariants");
    }
}
