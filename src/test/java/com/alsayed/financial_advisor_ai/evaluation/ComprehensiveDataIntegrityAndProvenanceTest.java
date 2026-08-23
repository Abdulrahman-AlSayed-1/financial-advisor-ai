package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.chunking.FigureDataPoint;
import com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkBuilder;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.normalized.PanelInterpretation;
import com.alsayed.financial_advisor_ai.model.provenance.NumericalProvenance;
import com.alsayed.financial_advisor_ai.model.provenance.ValidationStatus;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTable;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import com.alsayed.financial_advisor_ai.vision.MultiSourceValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class ComprehensiveDataIntegrityAndProvenanceTest {

    @Autowired
    private MultiSourceValidator validator;

    @Autowired
    private FigureChunkBuilder figureChunkBuilder;

    @Test
    @DisplayName("1. Incident Regression: British Pound 9.90% vs -2.30% MUST be CONFLICT, NEVER VERIFIED_AGREEMENT")
    void testBritishPoundConflictIncident() {
        // Deterministic table row contains: British Pound (GBP) | 2.06 | 5.22 | 9.90 | -0.17 | 1.71
        RawPdfTable table = new RawPdfTable(
                0, new double[]{37.8, 177.8, 414.4, 357.3}, 1, 6, List.of(),
                "| British Pound (GBP) | 2.06 | 5.22 | 9.90 | -0.17 | 1.71 |",
                List.of()
        );

        // Vision erroneously emitted: -2.3%
        FigureDataPoint visionDp = FigureDataPoint.builder()
                .entity("British Pound (GBP)")
                .series("1Y")
                .metric("Spot Returns vs. USD")
                .value("-2.3")
                .unit("%")
                .pageNumber(42)
                .build();

        MultiSourceValidator.ValidationResult result = validator.validateDataPoint(visionDp, List.of(), List.of(table));

        assertEquals(ValidationStatus.CONFLICT, result.status(), "Discrepant value MUST be classified as CONFLICT");
        assertEquals(NumericalProvenance.CONFLICT, result.provenance());
        assertNotEquals(ValidationStatus.VERIFIED_AGREEMENT, result.status(), "Discrepant value MUST NEVER be VERIFIED_AGREEMENT");
    }

    @Test
    @DisplayName("2. Verified Agreement: Entity + Metric + Period + Value exact match in deterministic text")
    void testExactVerifiedAgreement() {
        RawPdfTable table = new RawPdfTable(
                0, new double[]{37.8, 177.8, 414.4, 357.3}, 1, 6, List.of(),
                "| British Pound (GBP) | 2.06 | 5.22 | 9.90 | -0.17 | 1.71 |",
                List.of()
        );

        FigureDataPoint visionDp = FigureDataPoint.builder()
                .entity("British Pound (GBP)")
                .series("1Y")
                .metric("Spot Returns vs. USD")
                .value("9.90")
                .unit("%")
                .pageNumber(42)
                .build();

        MultiSourceValidator.ValidationResult result = validator.validateDataPoint(visionDp, List.of(), List.of(table));

        assertEquals(ValidationStatus.VERIFIED_AGREEMENT, result.status());
        assertEquals(NumericalProvenance.RECONCILED, result.provenance());
    }

    @Test
    @DisplayName("3. Value matches but entity differs on same page -> NOT VERIFIED")
    void testValueMatchesDifferentEntity() {
        // Table has Canadian Dollar with -2.39%, but NO British Pound with -2.39%
        RawPdfTable table = new RawPdfTable(
                0, new double[]{37.8, 177.8, 414.4, 357.3}, 1, 6, List.of(),
                "| Canadian Dollar (CAD) | -0.24 | -2.39 | 0.08 | -2.12 | -0.40 |",
                List.of()
        );

        FigureDataPoint visionDp = FigureDataPoint.builder()
                .entity("British Pound (GBP)")
                .series("YTD")
                .value("-2.39")
                .unit("%")
                .pageNumber(42)
                .build();

        MultiSourceValidator.ValidationResult result = validator.validateDataPoint(visionDp, List.of(), List.of(table));

        assertNotEquals(ValidationStatus.VERIFIED_AGREEMENT, result.status(),
                "Entity mismatch must never be verified even if value exists elsewhere on page");
    }

    @Test
    @DisplayName("4. Visual Only: Explicit printed label not in text layer is VISUAL_ONLY, not hallucination")
    void testVisualOnlyExplicitLabel() {
        FigureDataPoint visionDp = FigureDataPoint.builder()
                .figureTitle("Global Real Estate Cap Rates")
                .entity("Industrial Cap Rate")
                .series("Current")
                .value("5.85")
                .unit("%")
                .pageNumber(15)
                .build();

        MultiSourceValidator.ValidationResult result = validator.validateDataPoint(visionDp, List.of(), List.of());

        assertEquals(ValidationStatus.VISUAL_ONLY, result.status());
        assertEquals(NumericalProvenance.VISION_EXPLICIT_LABEL, result.provenance());
    }

    @Test
    @DisplayName("5. Continuous Time-Series: Estimated point without label is VISION_VISUAL_ESTIMATE")
    void testContinuousTimeSeriesEstimate() {
        FigureDataPoint visionDp = FigureDataPoint.builder()
                .figureTitle("U.S. Dollar Index Time Series Trend 1970-2024")
                .entity("USD Index")
                .series("LHS")
                .period("1985")
                .value("142.5")
                .unit("index")
                .pageNumber(42)
                .build();

        MultiSourceValidator.ValidationResult result = validator.validateDataPoint(visionDp, List.of(), List.of());

        assertEquals(ValidationStatus.VISION_VISUAL_ESTIMATE, result.status());
        assertEquals(NumericalProvenance.VISION_VISUAL_ESTIMATE, result.provenance());
    }

    @Test
    @DisplayName("6. Provenance Conservation: Provenance & validation_status survive into Chunks and Metadata")
    void testProvenanceConservationIntoChunks() {
        FigureDataPoint dp1 = FigureDataPoint.builder()
                .entity("British Pound (GBP)")
                .series("1Y")
                .value("9.90")
                .unit("%")
                .pageNumber(42)
                .build();

        PanelInterpretation panel = new PanelInterpretation("Spot Returns vs. USD (%)", List.of(dp1));
        FigureInterpretation figure = new FigureInterpretation(
                "Developed Market Currency Performance and Yields",
                "table_multi_panel",
                "Spot Returns",
                "%",
                List.of("1Y"),
                List.of(panel),
                null,
                List.of(),
                "SUCCESS"
        );

        RawPdfTable table = new RawPdfTable(
                0, new double[]{37.8, 177.8, 414.4, 357.3}, 1, 6, List.of(),
                "| British Pound (GBP) | 2.06 | 5.22 | 9.90 | -0.17 | 1.71 |",
                List.of()
        );

        List<Document> chunks = figureChunkBuilder.buildChunks(
                figure, 42, "ALTERNATIVES", "article.pdf", "article_thebeatoct2024",
                List.of(), List.of(table), 0, new double[]{0, 0, 100, 100}
        );

        assertFalse(chunks.isEmpty());

        Document panelChunk = chunks.stream()
                .filter(c -> "figure_panel".equals(c.getMetadata().get("chunk_type")))
                .findFirst()
                .orElseThrow();

        assertTrue(panelChunk.getMetadata().containsKey("provenances"), "Panel chunk MUST contain provenances metadata");
        assertTrue(panelChunk.getMetadata().containsKey("validation_statuses"), "Panel chunk MUST contain validation_statuses metadata");
        assertTrue(panelChunk.getMetadata().get("provenances").toString().contains("RECONCILED"));
        assertTrue(panelChunk.getMetadata().get("validation_statuses").toString().contains("VERIFIED_AGREEMENT"));
    }
}
