package com.alsayed.financial_advisor_ai.chunking;

import com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkBuilder;
import com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkPolicy;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.normalized.PanelInterpretation;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import com.alsayed.financial_advisor_ai.vision.MultiSourceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core acceptance test for the PyMuPDF + Vision Multimodal RAG Pipeline.
 *
 * Verifies that Page 45 "Local Sovereign Currency Yields" figure correctly yields
 * Nigerian Naira (NGN) 1 Yr Prior = 11.37% with complete semantic metadata.
 *
 * <h3>Critical Requirements Tested</h3>
 * <ul>
 *   <li>11.37% (1 Yr Prior) must be present and distinguishable from 14.50% (Current)</li>
 *   <li>The panel summary must include both values in a structured table</li>
 *   <li>Individual datapoint chunks are created (small figure with 2 datapoints &lt; threshold)</li>
 *   <li>A figure-level summary chunk is always produced</li>
 *   <li>source_document_id provenance is on every chunk</li>
 * </ul>
 */
class Page45NigerianNairaAcceptanceTest {

    private FigureChunkBuilder figureChunkBuilder;
    private FigureChunkPolicy policy;

    @BeforeEach
    void setUp() {
        MultiSourceValidator validator = new MultiSourceValidator();
        policy = new FigureChunkPolicy();
        figureChunkBuilder = new FigureChunkBuilder(validator, policy);
    }

    /** Build the Page 45 figure interpretation used across tests. */
    private FigureInterpretation buildPage45Figure() {
        FigureDataPoint nairaCurrent = new FigureDataPoint();
        nairaCurrent.setEntity("Nigerian Naira (NGN)");
        nairaCurrent.setSeries("Current");
        nairaCurrent.setValue("14.50");
        nairaCurrent.setUnit("%");
        nairaCurrent.setMetric("Local Currency Yield");

        FigureDataPoint naira1YrPrior = new FigureDataPoint();
        naira1YrPrior.setEntity("Nigerian Naira (NGN)");
        naira1YrPrior.setSeries("1 Yr Prior");
        naira1YrPrior.setValue("11.37");
        naira1YrPrior.setUnit("%");
        naira1YrPrior.setMetric("Local Currency Yield");

        PanelInterpretation emPanel = new PanelInterpretation(
                "EM Local Currency Yields % (1 Year)",
                List.of(nairaCurrent, naira1YrPrior)
        );

        return new FigureInterpretation(
                "Local Sovereign Currency Yields",
                "bar_chart",
                "Local Currency Yield",
                "%",
                List.of(emPanel),
                List.of("1-year yield change comparison across major emerging markets"),
                "SUCCESS"
        );
    }

    private List<RawPdfTextBlock> buildPage45TextBlocks() {
        return List.of(
                new RawPdfTextBlock("Local Sovereign Currency Yields",
                        new double[]{72, 50, 400, 70}, "Helvetica-Bold", 16.0, true, false, 0),
                new RawPdfTextBlock("EM Local Currency Yields % (1 Year)",
                        new double[]{72, 80, 350, 95}, "Helvetica-Bold", 12.0, true, false, 1),
                new RawPdfTextBlock("Nigerian Naira (NGN) 11.37 14.50",
                        new double[]{100, 150, 300, 165}, "Helvetica", 10.0, false, false, 2)
        );
    }

    private List<Document> build() {
        return figureChunkBuilder.buildChunks(
                buildPage45Figure(), 45, "Emerging Markets",
                "article_thebeatoct2024.pdf", "article_thebeatoct2024",
                buildPage45TextBlocks());
    }

    // ── Test: Chunk hierarchy ─────────────────────────────────────────────────

    @Test
    @DisplayName("Page 45: figure_summary chunk always produced")
    void alwaysProducesFigureSummary() {
        List<Document> chunks = build();
        long summaryCount = chunks.stream()
                .filter(c -> "figure_summary".equals(c.getMetadata().get("chunk_type")))
                .count();
        assertEquals(1, summaryCount, "One figure_summary chunk must be created");
    }

    @Test
    @DisplayName("Page 45: panel summary chunk produced for EM panel")
    void producesPanelSummary() {
        List<Document> chunks = build();
        long panelCount = chunks.stream()
                .filter(c -> "figure_panel".equals(c.getMetadata().get("chunk_type")))
                .count();
        assertEquals(1, panelCount, "One panel summary per panel");
    }

    @Test
    @DisplayName("Page 45: individual datapoint chunks produced (small figure)")
    void producesDatapointChunks_smallFigure() {
        // 2 datapoints < default threshold of 30 → datapoint chunks ALSO created
        List<Document> chunks = build();
        long dpChunks = chunks.stream()
                .filter(c -> "figure".equals(c.getMetadata().get("chunk_type")))
                .count();
        assertEquals(2, dpChunks, "2 datapoints below threshold = 2 individual datapoint chunks");
    }

    // ── Test: Nigerian Naira 1 Yr Prior exact value ───────────────────────────

    @Test
    @DisplayName("Page 45: Nigerian Naira 1 Yr Prior = 11.37% in datapoint chunk")
    void nairaOnePriorInDatapointChunk() {
        List<Document> chunks = build();

        Document naira1YrPriorChunk = chunks.stream()
                .filter(c -> "figure".equals(c.getMetadata().get("chunk_type")))
                .filter(c -> "1 Yr Prior".equals(c.getMetadata().get("series")))
                .findFirst()
                .orElse(null);

        assertNotNull(naira1YrPriorChunk, "Chunk for Nigerian Naira 1 Yr Prior must exist");

        // Verify value metadata
        assertEquals("Nigerian Naira (NGN)", naira1YrPriorChunk.getMetadata().get("entity"));
        assertEquals("1 Yr Prior",           naira1YrPriorChunk.getMetadata().get("series"));
        assertEquals("11.37",                naira1YrPriorChunk.getMetadata().get("value"));
        assertEquals("%",                    naira1YrPriorChunk.getMetadata().get("unit"));
        assertEquals("COMPLETE",             naira1YrPriorChunk.getMetadata().get("extraction_status"));
        assertEquals("SUCCESS",              naira1YrPriorChunk.getMetadata().get("visual_interpretation"));

        // Verify text content for LLM reasoning
        String text = naira1YrPriorChunk.getText();
        assertTrue(text.contains("Local Sovereign Currency Yields"), "Figure title must be in chunk text");
        assertTrue(text.contains("Nigerian Naira (NGN)"),             "Entity must be in chunk text");
        assertTrue(text.contains("1 Yr Prior"),                        "Series must be in chunk text");
        assertTrue(text.contains("11.37"),                             "Value 11.37 must be in chunk text");
    }

    @Test
    @DisplayName("Page 45: panel summary contains BOTH Current and 1 Yr Prior values for Nigerian Naira")
    void panelSummaryContainsBothNairaValues() {
        List<Document> chunks = build();

        Document panel = chunks.stream()
                .filter(c -> "figure_panel".equals(c.getMetadata().get("chunk_type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Panel summary chunk not found"));

        String text = panel.getText();
        // Both series values must be present in the panel summary
        assertTrue(text.contains("14.50"), "Current value 14.50 must be in panel summary");
        assertTrue(text.contains("11.37"), "1 Yr Prior value 11.37 must be in panel summary");
        // The entity must be present
        assertTrue(text.contains("Nigerian Naira"), "Entity must be in panel summary");
        // Series headers must be distinguishable
        assertTrue(text.contains("Current"),   "Series 'Current' must appear in panel");
        assertTrue(text.contains("1 Yr Prior"), "Series '1 Yr Prior' must appear in panel");
    }

    @Test
    @DisplayName("Page 45: datapoint chunk for Current value is 14.50, NOT 11.37")
    void nairaCurrentNotConfusedWith1YrPrior() {
        List<Document> chunks = build();

        Document currentChunk = chunks.stream()
                .filter(c -> "figure".equals(c.getMetadata().get("chunk_type")))
                .filter(c -> "Current".equals(c.getMetadata().get("series")))
                .findFirst()
                .orElse(null);

        assertNotNull(currentChunk, "Current series chunk must exist");
        assertEquals("14.50", currentChunk.getMetadata().get("value"),
                "Current value must be 14.50, NOT the 1 Yr Prior value of 11.37");
    }

    // ── Test: Provenance ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Page 45: all chunks have source_document_id")
    void allChunksHaveSourceDocumentId() {
        List<Document> chunks = build();
        chunks.forEach(c -> assertEquals("article_thebeatoct2024",
                c.getMetadata().get("source_document_id"),
                "source_document_id must be set on chunk: " + c.getId()));
    }

    @Test
    @DisplayName("Page 45: all chunks have source=vision")
    void allChunksHaveSourceVision() {
        List<Document> chunks = build();
        chunks.forEach(c -> assertEquals("vision", c.getMetadata().get("source"),
                "source must be 'vision' for figure chunks"));
    }

    @Test
    @DisplayName("Page 45: all chunks carry page_number=45")
    void allChunksHavePageNumber45() {
        List<Document> chunks = build();
        chunks.forEach(c -> assertEquals(45, c.getMetadata().get("page_number"),
                "page_number must be 45 on every chunk"));
    }

    @Test
    @DisplayName("Page 45: Spurious entity 'ALTERNATIVES' and axis ticks '-' are filtered out")
    void spuriousEntitiesAndTicksAreFiltered() {
        FigureDataPoint nairaValid = new FigureDataPoint();
        nairaValid.setEntity("Nigerian Naira (NGN)");
        nairaValid.setSeries("Current");
        nairaValid.setValue("14.50");

        FigureDataPoint alternativesSpurious = new FigureDataPoint();
        alternativesSpurious.setEntity("ALTERNATIVES");
        alternativesSpurious.setSeries("Current");
        alternativesSpurious.setValue("3.68");

        FigureDataPoint axisTickSpurious = new FigureDataPoint();
        axisTickSpurious.setEntity("British Pound (GBP)");
        axisTickSpurious.setSeries("Current");
        axisTickSpurious.setValue("-");

        PanelInterpretation panel = new PanelInterpretation(
                "EM Local Currency Yields % (1 Year)",
                List.of(nairaValid, alternativesSpurious, axisTickSpurious)
        );

        FigureInterpretation figure = new FigureInterpretation(
                "Local Sovereign Currency Yields",
                "bar_chart",
                "Local Currency Yield",
                "%",
                List.of(panel),
                List.of(),
                "SUCCESS"
        );

        List<Document> chunks = figureChunkBuilder.buildChunks(
                figure, 45, "Emerging Markets", "article_thebeatoct2024.pdf", "article_thebeatoct2024",
                buildPage45TextBlocks()
        );

        // Verify that ALTERNATIVES is never created as a chunk entity
        boolean hasAlternatives = chunks.stream().anyMatch(c -> "ALTERNATIVES".equalsIgnoreCase((String) c.getMetadata().get("entity"))
                || (c.getText() != null && c.getText().contains("Entity: ALTERNATIVES")));
        assertFalse(hasAlternatives, "ALTERNATIVES must NOT be generated as an entity chunk");

        // Verify that axis tick '-' is never created as a chunk value
        boolean hasHyphenValue = chunks.stream().anyMatch(c -> "-".equals(c.getMetadata().get("value")));
        assertFalse(hasHyphenValue, "Hyphen axis tick '-' must NOT be generated as a datapoint chunk");

        // Valid Naira chunk must still be created
        boolean hasNaira = chunks.stream().anyMatch(c -> "Nigerian Naira (NGN)".equals(c.getMetadata().get("entity")));
        assertTrue(hasNaira, "Valid Nigerian Naira chunk must be preserved");
    }
}
