package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.ingestion.extractor.PdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.retrieval.EntityRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {"spring.config.name=application-test", "app.vision.enabled=true"})
public class BatchIngestionCardinalityVerificationTest {

    @Autowired
    private PdfExtractor pdfExtractor;

    @Autowired
    private PyMuPdfPipelineProcessor pipelineProcessor;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityRegistry entityRegistry;

    @Test
    @DisplayName("Verify 100% Unique Chunk IDs and Zero Batch Embedding Failures at Batch Size 50")
    void testBatchEmbeddingSucceedsWithoutFallback() {
        String sourceDocumentId = "test_batch_verification_doc";

        // Clean up test rows in vector store if any exist
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'source_document_id' = ?", sourceDocumentId);

        RawPdfDocument rawDoc = pdfExtractor.extract(new ClassPathResource("docs/pymupdf_extraction.json"));
        List<Document> rawChunks = pipelineProcessor.process(rawDoc, sourceDocumentId);

        // Filter valid chunks
        List<Document> validChunks = rawChunks.stream()
                .filter(d -> d.getText() != null && !d.getText().isBlank())
                .toList();

        int totalChunks = validChunks.size();
        System.out.printf("Total valid chunks to ingest: %d%n", totalChunks);

        // 1. Assert all Document IDs are distinct
        Set<String> uniqueIds = new HashSet<>();
        List<String> duplicateIds = new ArrayList<>();
        for (Document d : validChunks) {
            if (!uniqueIds.add(d.getId())) {
                duplicateIds.add(d.getId());
            }
        }
        assertThat(duplicateIds)
                .as("All Document IDs must be strictly unique within the document")
                .isEmpty();
        assertThat(uniqueIds).hasSize(totalChunks);

        // 2. Assert all Document objects are distinct according to Document.equals()
        Set<Document> uniqueDocs = new HashSet<>(validChunks);
        assertThat(uniqueDocs)
                .as("All Document instances must be distinct according to Document.equals()")
                .hasSize(totalChunks);

        // 3. Batch Vector Store Ingestion at Batch Size 50
        int batchSize = 50;
        int batchIndex = 0;
        int failedBatches = 0;
        int successfullyIndexed = 0;

        for (int i = 0; i < totalChunks; i += batchSize) {
            batchIndex++;
            int end = Math.min(i + batchSize, totalChunks);
            List<Document> batch = validChunks.subList(i, end);

            try {
                vectorStore.accept(batch);
                successfullyIndexed += batch.size();
                System.out.printf("Batch #%2d [%3d - %3d] (%2d docs) -> ACCEPTED BY VECTOR STORE%n",
                        batchIndex, i + 1, end, batch.size());
            } catch (Exception e) {
                failedBatches++;
                System.err.printf("Batch #%2d [%3d - %3d] FAILED: %s%n", batchIndex, i + 1, end, e.getMessage());
            }
        }

        // Verify that 0 batches failed and all chunks were indexed via batch accept
        assertThat(failedBatches)
                .as("Zero batches must fail or fall back to 1-by-1 ingestion")
                .isZero();
        assertThat(successfullyIndexed)
                .as("Number of successfully batch-indexed documents must match total chunk count")
                .isEqualTo(totalChunks);

        // 4. Verify count in PostgreSQL PgVectorStore
        Long countInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'source_document_id' = ?",
                Long.class, sourceDocumentId);
        assertThat(countInDb).isEqualTo((long) totalChunks);

        // Clean up test rows
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'source_document_id' = ?", sourceDocumentId);
    }
}
