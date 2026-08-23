package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.ingestion.PyMuPdfPipelineProcessor;
import com.alsayed.financial_advisor_ai.ingestion.extractor.JsonFilePdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=true"
})
public class EtfFlowTableAvailabilityTest {

    @Autowired
    private PyMuPdfPipelineProcessor pipelineProcessor;

    @Autowired
    private JsonFilePdfExtractor jsonExtractor;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Verify Page 49 ETF Flow Table extraction and availability to RAG")
    void testEtfFlowTableExtractionAndAvailability() throws Exception {
        // 1. Verify Pipeline Extraction
        RawPdfDocument rawDoc = jsonExtractor.extract(new ClassPathResource("docs/pymupdf_extraction.json"));
        List<Document> chunks = pipelineProcessor.process(rawDoc, "article_thebeatoct2024");

        List<Document> page49TableChunks = chunks.stream()
                .filter(d -> {
                    Object p = d.getMetadata().get("page_number");
                    return p != null && ((Number) p).intValue() == 49 && "table".equals(d.getMetadata().get("chunk_type"));
                })
                .toList();

        assertFalse(page49TableChunks.isEmpty(), "Page 49 must produce table chunks!");

        // Find the ETF table chunk
        Document etfChunk = page49TableChunks.stream()
                .filter(d -> {
                    String title = (String) d.getMetadata().get("table_name");
                    return title != null && title.toLowerCase().contains("exchange-traded fund");
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("Top 10 Exchange-Traded Fund Categories table chunk not found on Page 49"));

        String etfText = etfChunk.getText();
        System.out.println("=== PAGE 49 ETF TABLE CHUNK ===");
        System.out.println(etfText);

        assertTrue(etfText.contains("Large Blend"), "ETF table chunk must contain 'Large Blend' entity!");
        assertTrue(etfText.contains("18,151"), "ETF table chunk must contain '18,151' monthly flow for Large Blend!");
        assertTrue(etfText.toLowerCase().contains("exchange-traded fund"), "ETF table chunk must carry the ETF table title!");

        // 2. Verify Hybrid Retrieval from vector store
        List<Document> retrieved = hybridRetrievalService.search("Which Exchange-Traded Fund category recorded the highest monthly inflows as of August 31, 2024?", 10);
        System.out.println("=== RETRIEVED CHUNKS FOR ETF QUERY ===");
        boolean foundEtfTable = false;
        for (int i = 0; i < retrieved.size(); i++) {
            Document doc = retrieved.get(i);
            System.out.printf("Rank #%d: [Page %s, Type: %s, Table: %s, Entity: %s]%n",
                    i + 1, doc.getMetadata().get("page_number"), doc.getMetadata().get("chunk_type"),
                    doc.getMetadata().get("table_name"), doc.getMetadata().get("row_entity"));
            if (doc.getText().contains("18,151") || (doc.getText().contains("Large Blend") && doc.getText().toLowerCase().contains("exchange-traded fund"))) {
                foundEtfTable = true;
            }
        }
        assertTrue(foundEtfTable, "Hybrid retrieval must retrieve Page 49 ETF flow table chunk containing Large Blend / 18,151!");
    }
}
