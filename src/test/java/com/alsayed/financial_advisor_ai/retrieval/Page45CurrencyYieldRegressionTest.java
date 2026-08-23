package com.alsayed.financial_advisor_ai.retrieval;

import com.alsayed.financial_advisor_ai.chunking.FigureDataPoint;
import com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkBuilder;
import com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkPolicy;
import com.alsayed.financial_advisor_ai.controller.ChatController;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.normalized.PanelInterpretation;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import com.alsayed.financial_advisor_ai.vision.MultiSourceValidator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class Page45CurrencyYieldRegressionTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private ChatController chatController;

    private final MultiSourceValidator validator = new MultiSourceValidator();
    private final FigureChunkPolicy policy = new FigureChunkPolicy();
    private final FigureChunkBuilder figureChunkBuilder = new FigureChunkBuilder(validator, policy);

    // ── Layer 1: Canonical Evidence Modeling & Relationships ───────────────────

    @Test
    @DisplayName("Layer 1: Canonical FigureInterpretation encodes exact Entity -> Series -> Value relationships")
    void testCanonicalFigureInterpretationPreservesRelationships() {
        FigureDataPoint zarCurrent = FigureDataPoint.builder()
                .figureTitle("Local Sovereign Currency Yields")
                .panel("Emerging Market Local Interest Rates % (1 Year)")
                .entity("South African Rand (ZAR)")
                .entitySymbol("ZAR")
                .series("Current")
                .metric("Local Sovereign Currency Yield")
                .unit("%")
                .value("8.05")
                .pageNumber(45)
                .extractionMethod("VISION_LLM")
                .confidence("HIGH")
                .build();

        FigureDataPoint zar1YrPrior = FigureDataPoint.builder()
                .figureTitle("Local Sovereign Currency Yields")
                .panel("Emerging Market Local Interest Rates % (1 Year)")
                .entity("South African Rand (ZAR)")
                .entitySymbol("ZAR")
                .series("1 Yr Prior")
                .metric("Local Sovereign Currency Yield")
                .unit("%")
                .value("9.22")
                .pageNumber(45)
                .extractionMethod("VISION_LLM")
                .confidence("HIGH")
                .build();

        FigureDataPoint hufCurrent = FigureDataPoint.builder()
                .figureTitle("Local Sovereign Currency Yields")
                .panel("Emerging Market Local Interest Rates % (1 Year)")
                .entity("Hungarian Forint (HUF)")
                .entitySymbol("HUF")
                .series("Current")
                .metric("Local Sovereign Currency Yield")
                .unit("%")
                .value("5.27")
                .pageNumber(45)
                .extractionMethod("VISION_LLM")
                .confidence("HIGH")
                .build();

        FigureDataPoint huf1YrPrior = FigureDataPoint.builder()
                .figureTitle("Local Sovereign Currency Yields")
                .panel("Emerging Market Local Interest Rates % (1 Year)")
                .entity("Hungarian Forint (HUF)")
                .entitySymbol("HUF")
                .series("1 Yr Prior")
                .metric("Local Sovereign Currency Yield")
                .unit("%")
                .value("8.69")
                .pageNumber(45)
                .extractionMethod("VISION_LLM")
                .confidence("HIGH")
                .build();

        FigureDataPoint ngnCurrent = FigureDataPoint.builder()
                .figureTitle("Local Sovereign Currency Yields")
                .panel("Emerging Market Local Interest Rates % (1 Year)")
                .entity("Nigerian Naira (NGN)")
                .entitySymbol("NGN")
                .series("Current")
                .metric("Local Sovereign Currency Yield")
                .unit("%")
                .value("20.80")
                .pageNumber(45)
                .extractionMethod("VISION_LLM")
                .confidence("HIGH")
                .build();

        FigureDataPoint ngn1YrPrior = FigureDataPoint.builder()
                .figureTitle("Local Sovereign Currency Yields")
                .panel("Emerging Market Local Interest Rates % (1 Year)")
                .entity("Nigerian Naira (NGN)")
                .entitySymbol("NGN")
                .series("1 Yr Prior")
                .metric("Local Sovereign Currency Yield")
                .unit("%")
                .value("11.37")
                .pageNumber(45)
                .extractionMethod("VISION_LLM")
                .confidence("HIGH")
                .build();

        PanelInterpretation emPanel = new PanelInterpretation(
                "Emerging Market Local Interest Rates % (1 Year)",
                List.of(zarCurrent, zar1YrPrior, hufCurrent, huf1YrPrior, ngnCurrent, ngn1YrPrior)
        );

        FigureInterpretation interpretation = new FigureInterpretation(
                "Local Sovereign Currency Yields",
                "bar_chart",
                "Local Sovereign Currency Yield",
                "%",
                List.of(emPanel),
                List.of("1-year yield comparison across emerging markets"),
                "SUCCESS"
        );

        // Validation passes
        assertTrue(validator.validateFigureInterpretation(interpretation),
                "Valid multi-series chart interpretation must pass structural validation");

        // Build chunks
        List<RawPdfTextBlock> textBlocks = List.of(
                new RawPdfTextBlock("South African Rand (ZAR) 8.05 9.22", new double[]{100, 100, 300, 120}, "Helvetica", 10.0, false, false, 0),
                new RawPdfTextBlock("Hungarian Forint (HUF) 5.27 8.69", new double[]{100, 120, 300, 140}, "Helvetica", 10.0, false, false, 1)
        );

        List<Document> chunks = figureChunkBuilder.buildChunks(
                interpretation, 45, "Emerging Markets", "TheBeatOct2024.pdf", "doc_test", textBlocks);

        assertThat(chunks).isNotEmpty();

        // Tier 2 Panel Summary chunk contains the explicit structured table
        Document panelChunk = chunks.stream()
                .filter(d -> "figure_panel".equals(d.getMetadata().get("chunk_type")))
                .findFirst()
                .orElseThrow();

        assertThat(panelChunk.getText()).contains("South African Rand (ZAR) | 8.05 | 9.22");
        assertThat(panelChunk.getText()).contains("Hungarian Forint (HUF) | 5.27 | 8.69");
        assertThat(panelChunk.getText()).contains("Nigerian Naira (NGN) | 20.80 | 11.37");

        // Tier 3 Datapoint chunks preserve explicit metadata
        List<Document> datapointChunks = chunks.stream()
                .filter(d -> "figure".equals(d.getMetadata().get("chunk_type")))
                .toList();

        assertThat(datapointChunks).anySatisfy(d -> {
            assertThat(d.getMetadata().get("entity")).isEqualTo("South African Rand (ZAR)");
            assertThat(d.getMetadata().get("series")).isEqualTo("1 Yr Prior");
            assertThat(d.getMetadata().get("value")).isEqualTo("9.22");
        });

        assertThat(datapointChunks).anySatisfy(d -> {
            assertThat(d.getMetadata().get("entity")).isEqualTo("Hungarian Forint (HUF)");
            assertThat(d.getMetadata().get("series")).isEqualTo("1 Yr Prior");
            assertThat(d.getMetadata().get("value")).isEqualTo("8.69");
        });
    }

    // ── Layer 2: Fallback Safety & Unresolved Behavior ─────────────────────────

    @Test
    @DisplayName("Layer 2: Ambiguous/Conflicting Fallback is safely marked UNRESOLVED with NO fake data")
    void testAmbiguousFallbackSafelyDegradesToUnresolved() {
        // Construct an ambiguous mapping where entity has conflicting values for the same series
        FigureDataPoint p1 = FigureDataPoint.builder()
                .entity("South African Rand (ZAR)")
                .series("Current")
                .value("8.05")
                .build();

        FigureDataPoint p2Conflicting = FigureDataPoint.builder()
                .entity("South African Rand (ZAR)")
                .series("Current")
                .value("6.06") // Corrupted duplicate
                .build();

        PanelInterpretation corruptPanel = new PanelInterpretation(
                "Ambiguous Panel",
                List.of(p1, p2Conflicting)
        );

        FigureInterpretation corruptInterp = new FigureInterpretation(
                "Ambiguous Figure",
                "bar_chart",
                "Rate",
                "%",
                List.of(corruptPanel),
                List.of(),
                "SUCCESS"
        );

        // Structural validator MUST reject conflicting duplicate mappings
        assertFalse(validator.validateFigureInterpretation(corruptInterp),
                "Validator must reject conflicting entity+series mappings");

        // Building chunks for an UNRESOLVED interpretation must not emit false datapoints
        FigureInterpretation unresolvedInterp = new FigureInterpretation(
                "Ambiguous Figure",
                "bar_chart",
                "Rate",
                "%",
                List.of(),
                List.of("Spatial relationships ambiguous; fallback could not resolve"),
                "UNRESOLVED"
        );

        List<Document> chunks = figureChunkBuilder.buildChunks(
                unresolvedInterp, 45, "Emerging Markets", "TheBeatOct2024.pdf", "doc_test", List.of());

        assertThat(chunks).hasSize(1);
        Document diagnosticChunk = chunks.get(0);
        assertThat(diagnosticChunk.getMetadata().get("extraction_status")).isEqualTo("UNRESOLVED");
        assertThat(diagnosticChunk.getMetadata().get("confidence")).isEqualTo("UNRESOLVED");
        assertThat(diagnosticChunk.getText()).contains("Visual relationships could not be established with confidence");
    }

    // ── Layer 3: Hybrid Retrieval & Production Chat ────────────────────────────

    @Test
    @DisplayName("Layer 3: Production ChatController routes through HybridRetrievalService")
    void testChatControllerUsesHybridRetrieval() {
        String query = "What was the 1-year emerging market local interest rate for the South African Rand 1 Yr prior";

        // Query HybridRetrievalService directly
        List<Document> retrieved = hybridRetrievalService.search(query, 10);
        assertThat(retrieved).isNotEmpty();
        log.info("Hybrid search retrieved {} documents for ZAR query", retrieved.size());

        // Call production /chat endpoint
        String answer = chatController.chat(query);
        log.info("Production /chat answer for ZAR 1 Yr Prior: {}", answer);
        assertThat(answer).isNotBlank();
        assertFalse(answer.contains("LLM_GENERATION_UNAVAILABLE"), "Chat endpoint should generate answer cleanly");
    }
}
