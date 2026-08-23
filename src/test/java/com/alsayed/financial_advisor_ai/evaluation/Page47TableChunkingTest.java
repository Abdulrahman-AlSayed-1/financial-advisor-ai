package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.chunking.CanonicalTable;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableChunker;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableParser;
import com.alsayed.financial_advisor_ai.ingestion.ContentClassifier;
import com.alsayed.financial_advisor_ai.model.region.ContentRegion;
import com.alsayed.financial_advisor_ai.ingestion.extractor.PdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfPage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

import java.util.List;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class Page47TableChunkingTest {

    @Autowired
    private PdfExtractor pdfExtractor;

    @Autowired
    private ContentClassifier contentClassifier;

    @Autowired
    private CanonicalTableParser tableParser;

    @Autowired
    private CanonicalTableChunker tableChunker;

    @Value("${app.ingestion.pdf-path:classpath:docs/article_thebeatoct2024.pdf}")
    private Resource pdfResource;

    @Test
    void testPage47TableExtraction() throws Exception {
        RawPdfDocument rawDoc = pdfExtractor.extract(pdfResource);
        for (RawPdfPage page : rawDoc.pages()) {
            if (page.pageNumber() == 47) {
                List<ContentRegion> regions = contentClassifier.classifyPage(page);
                System.out.println("Regions on page 47: " + regions.size());
                for (ContentRegion r : regions) {
                    System.out.println("Region type: " + r.type());
                    if (r.rawTable() != null) {
                        System.out.println("RAW TABLE MARKDOWN:\n" + r.rawTable().markdown());
                        CanonicalTable canonicalTable = tableParser.parseMarkdownTable(
                                r.rawTable().markdown(), "page_47_tbl_0", "Asset Class Return Analysis (%)", "Alternatives", 47, r.enclosedText());
                        System.out.println("CANONICAL ROWS: " + canonicalTable.getRows().size());
                        for (var row : canonicalTable.getRows()) {
                            System.out.printf("Row: '%s' | cells: %d\n", row.getRowLabel(), row.getCells().size());
                        }

                        List<Document> chunks = tableChunker.chunk(canonicalTable, "article_thebeatoct2024", List.of("0"), List.of("page_47_tbl_0"), List.of(47), List.of(), List.of(), List.of());
                        System.out.println("CHUNKS EMITTED: " + chunks.size());
                        for (Document d : chunks) {
                            System.out.println("--- CHUNK ---\n" + d.getText() + "\n");
                        }
                    }
                }
            }
        }
    }
}
