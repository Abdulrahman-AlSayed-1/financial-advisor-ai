package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.chunking.CanonicalTable;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableChunker;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableParser;
import com.alsayed.financial_advisor_ai.ingestion.extractor.PdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfPage;
import com.alsayed.financial_advisor_ai.model.region.ContentRegion;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import com.alsayed.financial_advisor_ai.retrieval.QueryAnalyzer;
import com.alsayed.financial_advisor_ai.retrieval.QuerySignals;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {"spring.config.name=application-test", "app.vision.enabled=true"})
public class Page20EvidenceTraceTest {

    @Autowired
    private PdfExtractor pdfExtractor;

    @Autowired
    private ContentClassifier contentClassifier;

    @Autowired
    private CanonicalTableParser tableParser;

    @Autowired
    private CanonicalTableChunker tableChunker;

    @Autowired
    private PyMuPdfPipelineProcessor pipelineProcessor;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Autowired
    private VectorStore vectorStore;

    @Test
    @DisplayName("End-to-End Evidence Audit for Page 20 (Q3: US Loans 9.54%)")
    void tracePage20Evidence() {
        System.out.println("============================================================");
        System.out.println("1. RAW PYMUPDF EXTRACTION (PAGE 20)");
        System.out.println("============================================================");
        RawPdfDocument rawDoc = pdfExtractor.extract(new ClassPathResource("docs/pymupdf_extraction.json"));
        RawPdfPage page20 = rawDoc.pages().stream()
                .filter(p -> p.pageNumber() == 20)
                .findFirst()
                .orElseThrow();

        System.out.printf("Page 20: %d text blocks, %d tables, %d images, %d drawings%n",
                page20.textBlocks() != null ? page20.textBlocks().size() : 0,
                page20.tables() != null ? page20.tables().size() : 0,
                page20.images() != null ? page20.images().size() : 0,
                page20.drawings() != null ? page20.drawings().size() : 0);

        System.out.println("\n--- TEXT BLOCKS ON PAGE 20 ---");
        for (int i = 0; i < page20.textBlocks().size(); i++) {
            var b = page20.textBlocks().get(i);
            System.out.printf("Block #%d (fontSize=%.1f, bbox=[%.1f, %.1f, %.1f, %.1f]):%n%s%n",
                    i, b.fontSize(), b.getX0(), b.getY0(), b.getX1(), b.getY1(), b.text());
        }

        System.out.println("\n--- TABLES DETECTED ON PAGE 20 ---");
        for (int i = 0; i < page20.tables().size(); i++) {
            var t = page20.tables().get(i);
            System.out.printf("Table #%d (bbox=%s):%n%s%n", i, java.util.Arrays.toString(t.bbox()), t.markdown());
        }

        System.out.println("============================================================");
        System.out.println("2. CONTENT CLASSIFIER ON PAGE 20");
        System.out.println("============================================================");
        List<ContentRegion> regions = contentClassifier.classifyPage(page20);
        System.out.printf("Page 20 classified into %d ContentRegions:%n", regions.size());
        for (int i = 0; i < regions.size(); i++) {
            ContentRegion r = regions.get(i);
            System.out.printf("Region #%d: Type=%-15s BBox=%s EnclosedTextBlocks=%d TablePresent=%b%n",
                    i, r.type(), java.util.Arrays.toString(r.bbox()),
                    r.enclosedText() != null ? r.enclosedText().size() : 0,
                    r.rawTable() != null);
            if (r.enclosedText() != null && !r.enclosedText().isEmpty()) {
                System.out.println("  Enclosed text preview: " + r.enclosedText().get(0).text().replace("\n", " | "));
            }
            if (r.rawTable() != null) {
                System.out.println("  Raw table markdown:\n" + r.rawTable().markdown());
            }
        }

        System.out.println("============================================================");
        System.out.println("3. CANONICAL TABLE PARSER & CHUNKER ON PAGE 20");
        System.out.println("============================================================");
        for (int i = 0; i < regions.size(); i++) {
            ContentRegion r = regions.get(i);
            if (r.rawTable() != null && r.rawTable().markdown() != null) {
                String tableId = "page_20_tbl_" + i;
                CanonicalTable ct = tableParser.parseMarkdownTable(r.rawTable().markdown(), tableId, "Key Rates", 20, r.enclosedText());
                System.out.printf("CanonicalTable for Region #%d: title='%s', section='%s', rows=%d, headerLevels=%d%n",
                        i, ct.getTitle(), ct.getSection(), ct.getRows() != null ? ct.getRows().size() : 0,
                        ct.getHeaderLevels() != null ? ct.getHeaderLevels().size() : 0);

                if (ct.getHeaderLevels() != null && !ct.getHeaderLevels().isEmpty()) {
                    System.out.println("  Header row: " + java.util.Arrays.toString(ct.getHeaderLevels().get(0)));
                }
                if (ct.getRows() != null) {
                    for (var row : ct.getRows()) {
                        StringBuilder cellsStr = new StringBuilder();
                        if (row.getCells() != null) {
                            for (var cell : row.getCells()) {
                                cellsStr.append("[").append(cell.getColumnPath()).append("=").append(cell.getValue()).append("] ");
                            }
                        }
                        System.out.printf("  Row label='%s', category='%s', cells: %s%n",
                                row.getRowLabel(), row.getCategory(), cellsStr);
                    }
                }
            }
        }

        System.out.println("============================================================");
        System.out.println("4. ALL GENERATED CHUNKS FOR PAGE 20 IN PIPELINE");
        System.out.println("============================================================");
        List<Document> allChunks = pipelineProcessor.process(rawDoc, "article_thebeatoct2024");
        List<Document> page20Chunks = allChunks.stream()
                .filter(d -> Integer.valueOf(20).equals(d.getMetadata().get("page_number")))
                .toList();

        System.out.printf("Total chunks generated for Page 20: %d%n", page20Chunks.size());
        for (int i = 0; i < page20Chunks.size(); i++) {
            Document doc = page20Chunks.get(i);
            System.out.printf("%n--- Page 20 Chunk #%d (id=%s, type=%s) ---%n", i, doc.getId(), doc.getMetadata().get("chunk_type"));
            System.out.printf("Metadata: %s%n", doc.getMetadata());
            System.out.println("Content:\n" + doc.getText());
        }

        System.out.println("============================================================");
        System.out.println("5. RETRIEVAL TRACE FOR Q3 QUERY");
        System.out.println("============================================================");
        String query = "What was the current yield for U.S. Loans (Leveraged Loans) as of September 30, 2024?";
        List<Document> retrieved = hybridRetrievalService.search(query, 25);

        System.out.printf("Retrieved %d chunks for Q3 query: '%s'%n", retrieved.size(), query);
        for (int i = 0; i < retrieved.size(); i++) {
            Document doc = retrieved.get(i);
            System.out.printf("%n[Rank #%d] Score=%s, Page=%s, Type=%s, ID=%s%n",
                    i + 1, doc.getScore(), doc.getMetadata().get("page_number"), doc.getMetadata().get("chunk_type"), doc.getId());
            System.out.printf("Metadata: %s%n", doc.getMetadata());
            System.out.println("Content snippet:\n" + (doc.getText().length() > 300 ? doc.getText().substring(0, 300) + "..." : doc.getText()));
        }

        System.out.println("\n--- DETAILED VECTOR STORE TOP-K FOR Q3 QUERY ---");
        List<Document> rawVectorDocs = vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(query)
                        .topK(200)
                        .similarityThreshold(0.0)
                        .build());
        System.out.printf("Vector store returned %d raw docs%n", rawVectorDocs.size());
        for (int i = 0; i < rawVectorDocs.size(); i++) {
            Document d = rawVectorDocs.get(i);
            if (Integer.valueOf(20).equals(d.getMetadata().get("page_number"))) {
                System.out.printf("  [Vector Rank #%3d] Page 20 | Score=%.4f | Type=%-15s | ID=%s | Preview: %s%n",
                        i + 1, d.getScore(), d.getMetadata().get("chunk_type"), d.getId(),
                        d.getText().replace("\n", " ").substring(0, Math.min(80, d.getText().length())));
            }
        }

        System.out.println("\n--- QUERY ANALYZER SIGNALS FOR Q3 QUERY ---");
        QuerySignals signals = queryAnalyzer.analyze(query);
        System.out.printf("Signals: isComparative=%b, entities=%s, metric=%s, period=%s, tableTypes=%s%n",
                signals.isComparative(), signals.getEntities(), signals.getMetric(), signals.getPeriod(), signals.getTableTypes());

        System.out.println("\n--- CONTEXT FORMATTED FOR LLM ---");
        String contextForLlm = retrieved.stream()
                .limit(10)
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        System.out.printf("Total context length: %d chars%n", contextForLlm.length());
        System.out.println("Contains '9.54'? " + contextForLlm.contains("9.54"));
        System.out.println("Contains 'US Loans'? " + contextForLlm.contains("US Loans"));
        System.out.println("Contains 'Leveraged Loans'? " + contextForLlm.contains("Leveraged Loans"));
    }
}
