package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.ingestion.extractor.PdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfPage;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class FindHeadlineCpiPageTest {

    @Autowired
    private PdfExtractor pdfExtractor;

    @Test
    @DisplayName("Find which page contains Headline CPI and Fed funds")
    void findPages() throws Exception {
        RawPdfDocument rawDoc = pdfExtractor.extract(new FileSystemResource("src/main/resources/docs/article_thebeatoct2024.pdf"));

        for (RawPdfPage p : rawDoc.pages()) {
            for (RawPdfTextBlock b : p.textBlocks()) {
                String t = b.text().toLowerCase();
                if (t.contains("headline") || t.contains("cpi") || t.contains("inflation") || t.contains("fed funds") || t.contains("target range")) {
                    System.out.printf("Page %02d text: '%s'%n", p.pageNumber(), b.text().replace("\n", " ").trim());
                }
            }
        }
    }
}
