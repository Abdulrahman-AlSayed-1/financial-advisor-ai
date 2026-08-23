package com.alsayed.financial_advisor_ai.chunking.figure;

import com.alsayed.financial_advisor_ai.chunking.FigureDataPoint;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.normalized.PanelInterpretation;
import com.alsayed.financial_advisor_ai.vision.MultiSourceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests for FigureChunkBuilder.
 *
 * Tests the three-tier hierarchy:
 *   Tier 1 — figure_summary  (always)
 *   Tier 2 — figure_panel    (always when panels exist)
 *   Tier 3 — figure          (conditional: only when total datapoints <= policy threshold)
 *
 * Information preservation invariant:
 *   Every extracted datapoint value must appear in at least one chunk — in the
 *   panel summary if not in a datapoint chunk.
 */
class FigureChunkBuilderTest {

    private MultiSourceValidator validator;
    private FigureChunkPolicy policy;
    private FigureChunkBuilder builder;

    @BeforeEach
    void setUp() {
        validator = Mockito.mock(MultiSourceValidator.class);
        when(validator.validateDataPoint(any(), anyList(), anyList()))
                .thenReturn(new MultiSourceValidator.ValidationResult(
                        com.alsayed.financial_advisor_ai.model.provenance.ValidationStatus.VISUAL_ONLY,
                        com.alsayed.financial_advisor_ai.model.provenance.NumericalProvenance.VISION_EXPLICIT_LABEL,
                        null, "test"));
        when(validator.validateDataPoint(any(), anyList()))
                .thenReturn(new MultiSourceValidator.ValidationResult(
                        com.alsayed.financial_advisor_ai.model.provenance.ValidationStatus.VISUAL_ONLY,
                        com.alsayed.financial_advisor_ai.model.provenance.NumericalProvenance.VISION_EXPLICIT_LABEL,
                        null, "test"));
        policy = new FigureChunkPolicy();
        builder = new FigureChunkBuilder(validator, policy);
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private FigureDataPoint dp(String entity, String series, String value) {
        FigureDataPoint p = new FigureDataPoint();
        p.setEntity(entity);
        p.setSeries(series);
        p.setValue(value);
        p.setUnit("%");
        p.setMetric("Rate");
        return p;
    }

    private FigureInterpretation twoEntityTwoPanelFigure() {
        PanelInterpretation p1 = new PanelInterpretation(
                "Developed Markets",
                List.of(dp("Japanese Yen", "Current", "0.25"), dp("Japanese Yen", "1 Yr Prior", "0.10"))
        );
        PanelInterpretation p2 = new PanelInterpretation(
                "Emerging Markets",
                List.of(
                        dp("Nigerian Naira (NGN)", "Current", "20.80"),
                        dp("Nigerian Naira (NGN)", "1 Yr Prior", "11.37"),
                        dp("Turkish New Lira (TRY)", "Current", "37.50"),
                        dp("Turkish New Lira (TRY)", "1 Yr Prior", "21.50")
                )
        );
        return new FigureInterpretation(
                "Local Sovereign Currency Yields", "bar", "Local Interest Rate", "%",
                List.of(p1, p2), List.of(), "OK"
        );
    }

    private List<Document> build(FigureInterpretation interp) {
        return builder.buildChunks(interp, 45, "Some Section", "article.pdf", "article_thebeatoct2024", List.of());
    }

    // ── Tests: Tier 1 — Figure Summary ───────────────────────────────────────

    @Test
    void alwaysCreatesFigureSummaryChunk() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        long summaryCount = chunks.stream()
                .filter(d -> "figure_summary".equals(d.getMetadata().get("chunk_type")))
                .count();
        assertEquals(1, summaryCount, "Exactly one figure_summary chunk must be created");
    }

    @Test
    void figureSummaryContainsTitle() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        Document summary = chunks.stream()
                .filter(d -> "figure_summary".equals(d.getMetadata().get("chunk_type")))
                .findFirst().orElseThrow();
        assertThat(summary.getText()).contains("Local Sovereign Currency Yields");
    }

    @Test
    void figureSummaryContainsPanelNames() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        Document summary = chunks.stream()
                .filter(d -> "figure_summary".equals(d.getMetadata().get("chunk_type")))
                .findFirst().orElseThrow();
        assertThat(summary.getText()).contains("Developed Markets", "Emerging Markets");
    }

    @Test
    void figureSummaryContainsAllEntityNames() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        Document summary = chunks.stream()
                .filter(d -> "figure_summary".equals(d.getMetadata().get("chunk_type")))
                .findFirst().orElseThrow();
        assertThat(summary.getText()).contains("Nigerian Naira (NGN)", "Turkish New Lira (TRY)", "Japanese Yen");
    }

    @Test
    void figureSummaryContainsAllSeriesNames() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        Document summary = chunks.stream()
                .filter(d -> "figure_summary".equals(d.getMetadata().get("chunk_type")))
                .findFirst().orElseThrow();
        assertThat(summary.getText()).contains("Current", "1 Yr Prior");
    }

    // ── Tests: Tier 2 — Panel Summaries ──────────────────────────────────────

    @Test
    void createsPanelSummaryForEachPanel() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        long panelChunks = chunks.stream()
                .filter(d -> "figure_panel".equals(d.getMetadata().get("chunk_type")))
                .count();
        assertEquals(2, panelChunks, "One panel summary per panel");
    }

    @Test
    void panelSummaryContainsAllDatapointValues() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        Document emergingPanel = chunks.stream()
                .filter(d -> "figure_panel".equals(d.getMetadata().get("chunk_type")))
                .filter(d -> "Emerging Markets".equals(d.getMetadata().get("panel")))
                .findFirst().orElseThrow();

        // ALL values must be present in panel summary content
        assertThat(emergingPanel.getText())
                .contains("20.80")
                .contains("11.37")
                .contains("37.50")
                .contains("21.50");
    }

    @Test
    void panelSummaryDistinguishesCurrentVs1YrPrior_NigerianNaira() {
        // CRITICAL REQUIREMENT: Current (20.80) and 1 Yr Prior (11.37) must be unambiguous
        List<Document> chunks = build(twoEntityTwoPanelFigure());

        Document panel = chunks.stream()
                .filter(d -> "figure_panel".equals(d.getMetadata().get("chunk_type")))
                .filter(d -> "Emerging Markets".equals(d.getMetadata().get("panel")))
                .findFirst().orElseThrow();

        String text = panel.getText();
        // The table structure must contain both column headers
        assertThat(text).contains("Current");
        assertThat(text).contains("1 Yr Prior");
        // And the values in context of the entity
        assertThat(text).contains("Nigerian Naira (NGN)");
        assertThat(text).contains("20.80");
        assertThat(text).contains("11.37");
        // Values must appear in the correct row — the Nigerian Naira line must contain 20.80 and 11.37
        String nigerianNairaLine = text.lines()
                .filter(l -> l.contains("Nigerian Naira"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nigerian Naira line not found in:\n" + text));
        assertThat(nigerianNairaLine).contains("20.80").contains("11.37");
    }

    // ── Tests: Tier 3 — Datapoint Chunks (conditional) ───────────────────────

    @Test
    void smallFigure_createsDatapointChunks() {
        policy.setMaxDatapointChunks(30);
        // 6 datapoints < 30 → datapoint chunks ALSO created
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        long datapointChunks = chunks.stream()
                .filter(d -> "figure".equals(d.getMetadata().get("chunk_type")))
                .count();
        assertEquals(6, datapointChunks, "Small figure (6 dp < threshold 30) should create datapoint chunks");
    }

    @Test
    void largeFigure_doesNotCreateDatapointChunks() {
        policy.setMaxDatapointChunks(5);
        // 6 datapoints > 5 threshold → NO datapoint chunks
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        long datapointChunks = chunks.stream()
                .filter(d -> "figure".equals(d.getMetadata().get("chunk_type")))
                .count();
        assertEquals(0, datapointChunks,
                "Large figure (6 dp > threshold 5) must NOT create individual datapoint chunks");
    }

    @Test
    void largeFigure_allValuesStillPresentInPanelSummaries() {
        // INFORMATION PRESERVATION INVARIANT for large figures
        policy.setMaxDatapointChunks(5); // threshold = 5, figure has 6 dp
        List<Document> chunks = build(twoEntityTwoPanelFigure());

        String allChunkText = chunks.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);

        // Every value must appear somewhere
        assertThat(allChunkText)
                .contains("20.80", "11.37", "37.50", "21.50", "0.25", "0.10");
    }

    // ── Tests: Provenance / Metadata ──────────────────────────────────────────

    @Test
    void allChunksHaveSourceDocumentId() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        assertThat(chunks).allMatch(d ->
                "article_thebeatoct2024".equals(d.getMetadata().get("source_document_id")));
    }

    @Test
    void allChunksHavePageNumber() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        assertThat(chunks).allMatch(d -> d.getMetadata().containsKey("page_number"));
    }

    @Test
    void allChunksHaveSourceVision() {
        List<Document> chunks = build(twoEntityTwoPanelFigure());
        assertThat(chunks).allMatch(d -> "vision".equals(d.getMetadata().get("source")));
    }



    // ── Tests: Failed / null interpretation ──────────────────────────────────

    @Test
    void nullInterpretation_returnsDiagnosticChunk() {
        List<Document> chunks = builder.buildChunks(null, 7, "Section", "doc.pdf", "doc", List.of());
        assertEquals(1, chunks.size());
        assertEquals("figure", chunks.get(0).getMetadata().get("chunk_type"));
        assertEquals("FAILED", chunks.get(0).getMetadata().get("extraction_status"));
    }

    @Test
    void failedInterpretation_returnsDiagnosticChunk() {
        FigureInterpretation failed = FigureInterpretation.failed("VISION_TIMEOUT");
        List<Document> chunks = builder.buildChunks(failed, 7, "Section", "doc.pdf", "doc", List.of());
        assertEquals(1, chunks.size());
        assertEquals("FAILED", chunks.get(0).getMetadata().get("extraction_status"));
        assertThat(chunks.get(0).getText()).contains("VISION_TIMEOUT");
    }

    // ── Tests: Deterministic IDs ──────────────────────────────────────────────

    @Test
    void deterministicIds_sameInputSameIds() {
        FigureInterpretation interp = twoEntityTwoPanelFigure();
        List<String> firstIds  = build(interp).stream().map(Document::getId).sorted().toList();
        List<String> secondIds = build(interp).stream().map(Document::getId).sorted().toList();
        assertEquals(firstIds, secondIds, "Chunk IDs must be deterministic for the same input");
    }
}
