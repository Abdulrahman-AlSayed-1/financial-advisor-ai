package com.alsayed.financial_advisor_ai.chunking;

import com.alsayed.financial_advisor_ai.chunking.table.TableChunkPolicy;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LogicalTableRegionReconstructionTest {

    private CanonicalTableParser parser;
    private CanonicalTableChunker chunker;

    @BeforeEach
    void setUp() {
        parser = new CanonicalTableParser();
        TableChunkPolicy policy = new TableChunkPolicy();
        policy.setRowGroupSize(15);
        policy.setSmallTableThreshold(20);
        policy.setMaxCharsPerChunk(1200);
        policy.setAlwaysCreateSummary(true);
        chunker = new CanonicalTableChunker(policy);
    }

    @Test
    @DisplayName("Synthesizes complete logical table when PyMuPDF splits sector rows into adjacent text blocks")
    void testReconstructLogicalTableWithAssociatedTextBlocks() {
        // Base markdown table extracted by PyMuPDF (e.g. broad indices)
        String baseMarkdown = """
                | Entity | 1-Mo. | 3-Mo. | YTD | 1Y | 3Y | 5Y | 10Y | 2023 | 2022 | 2021 | 2020 | 2019 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | S&P 500 | 2.14 | 5.89 | 22.08 | 36.35 | 11.91 | 15.98 | 13.38 | 26.29 | -18.11 | 28.71 | 18.40 | 31.49 |
                | Russell 1000 Growth | 2.83 | 3.19 | 24.55 | 42.19 | 12.02 | 19.74 | 16.52 | 42.68 | -29.14 | 27.60 | 38.49 | 36.39 |
                """;

        // Spatially/structurally associated text block with sector rows (e.g. Info Tech, Utilities)
        String textBlockContent = """
                S&P 500 Comm. Services
                4.63
                1.68
                28.81
                42.91
                6.45
                14.59
                9.78
                55.80
                -39.89
                21.57
                23.61
                32.69
                S&P 500 Info Tech
                2.49
                1.61
                30.31
                52.68
                19.89
                26.74
                22.40
                57.84
                -28.19
                34.53
                43.89
                50.29
                S&P 500 Utilities
                6.60
                19.37
                30.63
                41.82
                11.66
                7.99
                10.41
                -7.08
                1.57
                17.67
                0.48
                26.35
                """;

        RawPdfTextBlock textBlock = new RawPdfTextBlock(
                textBlockContent,
                new double[]{98.0, 430.0, 900.0, 555.0},
                "ArialMT",
                8.5,
                false,
                false,
                10
        );

        CanonicalTable reconstructedTable = parser.parseMarkdownTable(
                baseMarkdown, "page_40_tbl_0", "Asset Class Return Analysis (%)", 40, List.of(textBlock));

        assertThat(reconstructedTable).isNotNull();
        // 2 base rows + 3 associated rows = 5 rows total
        assertThat(reconstructedTable.getRows()).hasSize(5);

        // Verify Info Tech was reconstructed with exact cell mapping
        CanonicalTable.CanonicalRow infoTechRow = reconstructedTable.getRows().stream()
                .filter(r -> "S&P 500 Info Tech".equals(r.getRowLabel()))
                .findFirst()
                .orElse(null);

        assertThat(infoTechRow).isNotNull();
        assertThat(infoTechRow.getCells()).hasSize(12);

        CanonicalTable.CanonicalCell ytdCell = infoTechRow.getCells().stream()
                .filter(c -> "YTD".equalsIgnoreCase(c.getPeriod()))
                .findFirst()
                .orElse(null);

        assertThat(ytdCell).isNotNull();
        assertThat(ytdCell.getValue()).isEqualTo("30.31");

        // Verify Utilities was reconstructed with exact cell mapping
        CanonicalTable.CanonicalRow utilitiesRow = reconstructedTable.getRows().stream()
                .filter(r -> "S&P 500 Utilities".equals(r.getRowLabel()))
                .findFirst()
                .orElse(null);

        assertThat(utilitiesRow).isNotNull();
        CanonicalTable.CanonicalCell utilYtdCell = utilitiesRow.getCells().stream()
                .filter(c -> "YTD".equalsIgnoreCase(c.getPeriod()))
                .findFirst()
                .orElse(null);

        assertThat(utilYtdCell).isNotNull();
        assertThat(utilYtdCell.getValue()).isEqualTo("30.63");

        // Chunking verification
        List<Document> chunks = chunker.chunk(
                reconstructedTable,
                "article_thebeatoct2024",
                List.of("0"),
                List.of("page_40_tbl_0"),
                List.of(40),
                List.of(), List.of(), List.of());

        assertThat(chunks).isNotEmpty();
        // Check that table summary and row group chunks contain Info Tech (30.31) and Utilities (30.63)
        boolean hasInfoTechInChunks = chunks.stream()
                .anyMatch(doc -> doc.getText().contains("S&P 500 Info Tech") && doc.getText().contains("30.31"));
        boolean hasUtilitiesInChunks = chunks.stream()
                .anyMatch(doc -> doc.getText().contains("S&P 500 Utilities") && doc.getText().contains("30.63"));

        assertThat(hasInfoTechInChunks).isTrue();
        assertThat(hasUtilitiesInChunks).isTrue();
    }
}
