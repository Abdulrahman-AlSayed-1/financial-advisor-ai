package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.chunking.CanonicalTable;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableChunker;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableParser;
import com.alsayed.financial_advisor_ai.chunking.table.DeterministicTableQualityAssessor;
import com.alsayed.financial_advisor_ai.chunking.table.TableChunkPolicy;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDrawing;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfPage;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import com.alsayed.financial_advisor_ai.model.region.ContentRegion;
import com.alsayed.financial_advisor_ai.model.region.RegionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class EvidenceIngestionPipelineTest {

    private ContentClassifier classifier;
    private CanonicalTableParser tableParser;
    private CanonicalTableChunker tableChunker;

    @BeforeEach
    void setUp() {
        classifier = new ContentClassifier(new DeterministicTableQualityAssessor());
        tableParser = new CanonicalTableParser();
        TableChunkPolicy policy = new TableChunkPolicy();
        policy.setRowGroupSize(15);
        policy.setSmallTableThreshold(20);
        policy.setMaxCharsPerChunk(1200);
        policy.setAlwaysCreateSummary(true);
        tableChunker = new CanonicalTableChunker(policy);
    }

    @Test
    @DisplayName("Test A: Full-page background drawing does not swallow narrative text blocks on Page 4 and Page 7")
    void testBackgroundDrawingDoesNotSwallowNarrativeText() {
        // Page 4 mock: background canvas + narrative paragraph containing '75 bps'
        RawPdfDrawing backgroundDrawing = new RawPdfDrawing(
                0,
                new double[]{0.0, 0.0, 945.36, 612.0},
                1,
                0,
                true
        );

        RawPdfTextBlock narrativeBlock = new RawPdfTextBlock(
                "Holding Underweight in Duration\n" +
                "Rate cuts are well priced. The longer-term average spread between Fed funds and the U.S. Treasury (UST) 10-year yield is 75 bps. " +
                "So, even if the Fed cuts to 3%, as priced, then the UST 10-year may end up around 3.75%.",
                new double[]{36.0, 137.0, 437.0, 290.0},
                "Arial-BoldMT",
                14.0,
                true,
                false,
                4
        );

        RawPdfPage page4 = new RawPdfPage(
                4,
                945.36,
                612.0,
                List.of(narrativeBlock),
                List.of(), // no PyMuPDF tables
                List.of(), // no images
                List.of(backgroundDrawing),
                "pages/page_4.png"
        );

        List<ContentRegion> regions = classifier.classifyPage(page4);

        // Verify narrative text region is created and NOT swallowed
        ContentRegion textRegion = regions.stream()
                .filter(r -> r.type() == RegionType.TEXT)
                .findFirst()
                .orElse(null);

        assertThat(textRegion).isNotNull();
        assertThat(textRegion.enclosedText()).hasSize(1);
        assertThat(textRegion.enclosedText().get(0).text()).contains("75 bps");

        // Verify background drawing is NOT registered as a figure candidate
        boolean hasBackgroundFigure = regions.stream()
                .anyMatch(r -> r.type() == RegionType.FIGURE_CANDIDATE && r.bbox()[2] >= 900.0);
        assertThat(hasBackgroundFigure).isFalse();
    }

    @Test
    @DisplayName("Test B: Page 20 pure text-based table is recognized and parsed into CanonicalTable with US Loans 9.54%")
    void testPage20TextBasedTableExtraction() {
        // Page 20 mock: Header block + 22 structured row blocks
        RawPdfTextBlock headerBlock = new RawPdfTextBlock(
                "Security\nCurrent\n12-Mo. Ago\nAverage\nMinimum\nMaximum",
                new double[]{43.0, 146.0, 876.0, 158.0},
                "Arial-BoldMT",
                11.0,
                true,
                false,
                5
        );

        RawPdfTextBlock rowSifma = new RawPdfTextBlock(
                "1-Week SIFMA\n3.15\n3.98\n3.46\n1.90\n4.55",
                new double[]{43.0, 165.0, 858.0, 176.0},
                "Arial-BoldMT",
                10.0,
                true,
                false,
                6
        );

        RawPdfTextBlock rowSofr = new RawPdfTextBlock(
                "Secured Overnight Financing Rate\n4.96\n5.31\n5.31\n4.82\n5.40",
                new double[]{43.0, 181.0, 858.0, 192.0},
                "Arial-BoldMT",
                10.0,
                true,
                false,
                7
        );

        RawPdfTextBlock rowHy = new RawPdfTextBlock(
                "US High Yield\n6.98\n8.90\n8.01\n6.98\n9.53",
                new double[]{43.0, 491.0, 858.0, 502.0},
                "Arial-BoldMT",
                10.0,
                true,
                false,
                26
        );

        RawPdfTextBlock rowLoans = new RawPdfTextBlock(
                "US Loans\n9.54\n10.30\n9.97\n9.54\n10.60",
                new double[]{43.0, 508.0, 860.0, 519.0},
                "Arial-BoldMT",
                10.0,
                true,
                false,
                27
        );

        RawPdfPage page20 = new RawPdfPage(
                20,
                945.36,
                612.0,
                List.of(headerBlock, rowSifma, rowSofr, rowHy, rowLoans),
                List.of(),
                List.of(),
                List.of(),
                "pages/page_20.png"
        );

        List<ContentRegion> regions = classifier.classifyPage(page20);

        // Verify a SIMPLE_TABLE region was synthesized from text blocks
        ContentRegion tableRegion = regions.stream()
                .filter(r -> r.type() == RegionType.SIMPLE_TABLE)
                .findFirst()
                .orElse(null);

        assertThat(tableRegion).isNotNull();
        assertThat(tableRegion.rawTable()).isNotNull();
        assertThat(tableRegion.rawTable().markdown()).contains("US Loans");

        // Parse into CanonicalTable
        CanonicalTable canonicalTable = tableParser.parseMarkdownTable(
                tableRegion.rawTable().markdown(),
                "page_20_tbl_0",
                "Key Rates (%)",
                20,
                tableRegion.enclosedText()
        );

        assertThat(canonicalTable.getRows()).hasSize(4);

        // Verify US Loans row
        CanonicalTable.CanonicalRow usLoansRow = canonicalTable.getRows().stream()
                .filter(r -> "US Loans".equals(r.getRowLabel()))
                .findFirst()
                .orElse(null);

        assertThat(usLoansRow).isNotNull();
        CanonicalTable.CanonicalCell currentCell = usLoansRow.getCells().stream()
                .filter(c -> "Current".equalsIgnoreCase(c.getColumnPath()) || "Current".equalsIgnoreCase(c.getPeriod()))
                .findFirst()
                .orElse(null);

        assertThat(currentCell).isNotNull();
        assertThat(currentCell.getValue()).isEqualTo("9.54");

        // Verify chunking produces key-value formatted chunks
        List<Document> chunks = tableChunker.chunk(
                canonicalTable,
                "article_thebeatoct2024",
                List.of("0"),
                List.of("page_20_tbl_0"),
                List.of(20),
                List.of(), List.of(), List.of());

        boolean hasUsLoansCurrentInChunk = chunks.stream()
                .anyMatch(d -> d.getText().contains("US Loans") && d.getText().contains("9.54"));

        assertThat(hasUsLoansCurrentInChunk).isTrue();
    }

    @Test
    @DisplayName("Test C: Full document pipeline produces Page 20 table chunk with US Loans 9.54%")
    void testFullDocumentPage20Extraction() throws Exception {
        var extractor = new com.alsayed.financial_advisor_ai.ingestion.extractor.JsonFilePdfExtractor();
        org.springframework.core.io.Resource resource = new org.springframework.core.io.ClassPathResource("docs/pymupdf_extraction.json");
        org.springframework.test.util.ReflectionTestUtils.setField(extractor, "jsonResource", resource);

        var rawDoc = extractor.extract(resource);
        assertThat(rawDoc).isNotNull();

        var textNormalizer = new com.alsayed.financial_advisor_ai.chunking.text.TextNormalizer();
        org.springframework.test.util.ReflectionTestUtils.setField(textNormalizer, "targetChars", 3000);
        org.springframework.test.util.ReflectionTestUtils.setField(textNormalizer, "minChars", 80);

        var figurePolicy = new com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkPolicy();
        var figureBuilder = new com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkBuilder(new com.alsayed.financial_advisor_ai.vision.MultiSourceValidator(), figurePolicy);
        var visionParser = new com.alsayed.financial_advisor_ai.vision.VisionResponseParser(tableParser);

        var processor = new PyMuPdfPipelineProcessor(
                classifier,
                textNormalizer,
                tableParser,
                tableChunker,
                visionParser,
                figureBuilder
        );

        List<Document> docChunks = processor.process(rawDoc, "article_thebeatoct2024");

        List<Document> page20Chunks = docChunks.stream()
                .filter(d -> Integer.valueOf(20).equals(d.getMetadata().get("page_number")))
                .toList();

        System.out.println("=== Page 20 Chunks Count: " + page20Chunks.size() + " ===");
        for (Document d : page20Chunks) {
            System.out.println("Chunk ID: " + d.getId() + " | type: " + d.getMetadata().get("chunk_type") + " | table_id: " + d.getMetadata().get("table_id"));
            System.out.println("Entities in group: " + d.getMetadata().get("entities_in_group"));
            System.out.println("Content:\n" + d.getText() + "\n---");
        }

        Document usLoansChunk = page20Chunks.stream()
                .filter(d -> d.getText().contains("US Loans") && d.getText().contains("9.54"))
                .findFirst()
                .orElse(null);

        assertThat(usLoansChunk).isNotNull();
    }
}
