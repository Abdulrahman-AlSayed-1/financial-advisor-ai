package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.ingestion.extractor.PdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class SearchAllPagesForRussellValuationTest {

    @Autowired
    private PdfExtractor pdfExtractor;

    @Value("${app.ingestion.pdf-path:classpath:docs/article_thebeatoct2024.pdf}")
    private Resource pdfResource;

    @Test
    void searchPages() throws Exception {
        RawPdfDocument rawDoc = pdfExtractor.extract(pdfResource);
        for (RawPdfPage p : rawDoc.pages()) {
            StringBuilder sb = new StringBuilder();
            if (p.textBlocks() != null) {
                for (var tb : p.textBlocks()) {
                    if (tb.text() != null) sb.append(tb.text()).append("\n");
                }
            }
            String text = sb.toString();
            if (text.toLowerCase().contains("russell 2000")) {
                System.out.printf("\n=== PAGE %d contains 'Russell 2000' ===\n", p.pageNumber());
                for (String line : text.split("\n")) {
                    if (line.toLowerCase().contains("russell 2000") || line.toLowerCase().contains("p/e") || line.toLowerCase().contains("ntm") || line.toLowerCase().contains("valuation")) {
                        System.out.println("  " + line);
                    }
                }
            }
        }
    }
}
