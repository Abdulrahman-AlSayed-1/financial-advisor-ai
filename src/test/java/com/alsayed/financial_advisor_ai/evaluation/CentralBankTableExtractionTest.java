package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.ingestion.PyMuPdfPipelineProcessor;
import com.alsayed.financial_advisor_ai.ingestion.extractor.JsonFilePdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class CentralBankTableExtractionTest {

    @Autowired
    private PyMuPdfPipelineProcessor pipelineProcessor;

    @Autowired
    private JsonFilePdfExtractor jsonExtractor;

    @Test
    @DisplayName("Verify Page 21 Central Bank Table semantic column alignment")
    void testCentralBankTableSemanticColumnAlignment() throws Exception {
        RawPdfDocument rawDoc = jsonExtractor.extract(new ClassPathResource("docs/pymupdf_extraction.json"));
        List<Document> chunks = pipelineProcessor.process(rawDoc, "article_thebeatoct2024");

        List<Document> page21TableChunks = chunks.stream()
                .filter(d -> {
                    Object p = d.getMetadata().get("page_number");
                    return p != null && ((Number) p).intValue() == 21 && "table".equals(d.getMetadata().get("chunk_type"));
                })
                .toList();

        assertFalse(page21TableChunks.isEmpty(), "Page 21 must have table chunks!");

        // 1. Verify U.S. Federal Reserve chunk
        Document fedDoc = page21TableChunks.stream()
                .filter(d -> "U.S. Federal Reserve".equalsIgnoreCase((String) d.getMetadata().get("row_entity")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("U.S. Federal Reserve chunk not found on Page 21"));

        String fedText = fedDoc.getText();
        System.out.println("=== FED CHUNK ===");
        System.out.println(fedText);

        assertTrue(fedText.contains("Current: 5.00%"), "Fed Current must be 5.00%, was:\n" + fedText);
        assertTrue(fedText.contains("1-Mo. Ago: 5.50%"), "Fed 1-Mo. Ago must be 5.50%, was:\n" + fedText);
        assertTrue(fedText.contains("12-Mo. Ago: 5.50%"), "Fed 12-Mo. Ago must be 5.50%, was:\n" + fedText);
        assertFalse(fedText.contains("Market Expectations for Future Central Bank Rates: 5.00%"),
                "Super-header must not be treated as a fake column header!");

        // 2. Verify BOE chunk
        Document boeDoc = page21TableChunks.stream()
                .filter(d -> "BOE".equalsIgnoreCase((String) d.getMetadata().get("row_entity")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BOE chunk not found on Page 21"));

        String boeText = boeDoc.getText();
        assertTrue(boeText.contains("Current: 5.00%"), "BOE Current must be 5.00%, was:\n" + boeText);
        assertTrue(boeText.contains("1-Mo. Ago: 5.00%"), "BOE 1-Mo. Ago must be 5.00%, was:\n" + boeText);
        assertTrue(boeText.contains("12-Mo. Ago: 5.25%"), "BOE 12-Mo. Ago must be 5.25%, was:\n" + boeText);

        // 3. Verify BOJ chunk
        Document bojDoc = page21TableChunks.stream()
                .filter(d -> "BOJ".equalsIgnoreCase((String) d.getMetadata().get("row_entity")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BOJ chunk not found on Page 21"));

        String bojText = bojDoc.getText();
        assertTrue(bojText.contains("Current: 0.25%"), "BOJ Current must be 0.25%, was:\n" + bojText);
        assertTrue(bojText.contains("1-Mo. Ago: 0.25%"), "BOJ 1-Mo. Ago must be 0.25%, was:\n" + bojText);
        assertTrue(bojText.contains("12-Mo. Ago: -0.10%"), "BOJ 12-Mo. Ago must be -0.10%, was:\n" + bojText);

        // 4. Verify ECB chunk
        Document ecbDoc = page21TableChunks.stream()
                .filter(d -> "ECB".equalsIgnoreCase((String) d.getMetadata().get("row_entity")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ECB chunk not found on Page 21"));

        String ecbText = ecbDoc.getText();
        assertTrue(ecbText.contains("Current: 3.65%"), "ECB Current must be 3.65%, was:\n" + ecbText);
        assertTrue(ecbText.contains("1-Mo. Ago: 4.25%"), "ECB 1-Mo. Ago must be 4.25%, was:\n" + ecbText);
        assertTrue(ecbText.contains("12-Mo. Ago: 4.50%"), "ECB 12-Mo. Ago must be 4.50%, was:\n" + ecbText);
    }
}
