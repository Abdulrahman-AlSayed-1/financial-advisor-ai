package com.alsayed.financial_advisor_ai.service;

import com.alsayed.financial_advisor_ai.ingestion.ExtractionQualityValidator;
import com.alsayed.financial_advisor_ai.ingestion.PyMuPdfPipelineProcessor;
import com.alsayed.financial_advisor_ai.ingestion.extractor.PdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.retrieval.EntityRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production Ingestion Service for PyMuPDF + Vision Multimodal RAG Pipeline.
 * Includes lifecycle synchronization, chunk sanitization, and batched vector store ingestion.
 */
@Slf4j
@Service
public class IngestionService implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ExtractionQualityValidator qualityValidator;
    private final EntityRegistry entityRegistry;
    private final PdfExtractor pdfExtractor;
    private final PyMuPdfPipelineProcessor pymupdfPipelineProcessor;

    private final AtomicBoolean ingestionComplete = new AtomicBoolean(false);
    private final AtomicBoolean ingesting = new AtomicBoolean(false);
    private final CountDownLatch ingestionLatch = new CountDownLatch(1);

    @Value("classpath:/docs/article_thebeatoct2024.pdf")
    private Resource pdfResource;

    @Value("${app.ingestion.force:false}")
    private boolean forceReingest;

    @Value("${app.ingestion.batch-size:50}")
    private int batchSize;

    public IngestionService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            ExtractionQualityValidator qualityValidator,
            EntityRegistry entityRegistry,
            PdfExtractor pdfExtractor,
            PyMuPdfPipelineProcessor pymupdfPipelineProcessor) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.qualityValidator = qualityValidator;
        this.entityRegistry = entityRegistry;
        this.pdfExtractor = pdfExtractor;
        this.pymupdfPipelineProcessor = pymupdfPipelineProcessor;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Starting PyMuPDF + Vision Multimodal RAG Ingestion Pipeline ===");
        ingesting.set(true);

        try {
            // Derive a stable source document ID from the PDF filename.
            // This is the key used for document-scoped idempotency — re-ingesting
            // the same document replaces only chunks belonging to that document.
            String sourceDocumentId = deriveSourceDocumentId(pdfResource);
            log.info("Source document ID: '{}'", sourceDocumentId);

            long existingCount = countVectorsBySource(sourceDocumentId);

            if (existingCount > 0 && !forceReingest) {
                log.info("Vector store already contains {} rows for source='{}' — skipping ingestion. " +
                        "Set app.ingestion.force=true to re-ingest.", existingCount, sourceDocumentId);
                ingestionComplete.set(true);
                return;
            }

            if (existingCount > 0) {
                log.info("app.ingestion.force=true — clearing {} existing rows for source='{}' before re-ingesting.",
                        existingCount, sourceDocumentId);
                clearVectorsBySource(sourceDocumentId);
                entityRegistry.clear();
            }

            RawPdfDocument rawDoc = pdfExtractor.extract(pdfResource);
            List<Document> rawChunks = pymupdfPipelineProcessor.process(rawDoc, sourceDocumentId);

            // 1. Sanitize Chunks: filter out empty, null, or blank text content
            List<Document> validChunks = new ArrayList<>();
            int emptyCount = 0;
            for (Document doc : rawChunks) {
                String text = doc.getText();
                if (text == null || text.trim().isEmpty() || text.isBlank()) {
                    emptyCount++;
                    log.warn("Skipping empty/whitespace document chunk (id: {}, metadata: {})",
                            doc.getId(), doc.getMetadata());
                } else {
                    validChunks.add(doc);
                }
            }

            if (emptyCount > 0) {
                log.warn("Sanitization filtered out {} empty chunks. Valid chunks remaining: {}",
                        emptyCount, validChunks.size());
            }

            // Log Chunk distribution
            long tableChunks = validChunks.stream()
                    .filter(d -> "table".equals(d.getMetadata().get("chunk_type")))
                    .count();
            long paragraphChunks = validChunks.stream()
                    .filter(d -> "paragraph".equals(d.getMetadata().get("chunk_type")))
                    .count();
            long figureChunks = validChunks.stream()
                    .filter(d -> "figure".equals(d.getMetadata().get("chunk_type")))
                    .count();

            log.info("PyMuPDF sanitized ingestion produced {} valid chunks: {} paragraphs, {} tables, {} figures",
                    validChunks.size(), paragraphChunks, tableChunks, figureChunks);

            // 2. Populate entity registry from valid chunks
            entityRegistry.populateFromDocuments(validChunks);
            EntityRegistry.RegistryStats stats = entityRegistry.getStats();
            log.info("Entity registry stats: {} unique entities, {} total occurrences",
                    stats.uniqueEntities(), stats.totalOccurrences());

            // 3. Batched Vector Store Loading
            int total = validChunks.size();
            log.info("Loading {} documents into PgVectorStore in batches of {}...", total, batchSize);

            int successfullyIndexed = 0;
            for (int i = 0; i < total; i += batchSize) {
                int end = Math.min(i + batchSize, total);
                List<Document> batch = validChunks.subList(i, end);
                try {
                    vectorStore.accept(batch);
                    successfullyIndexed += batch.size();
                } catch (Exception batchEx) {
                    log.warn("Batch [{} - {}] failed during vector embedding: {}. Falling back to 1-by-1 document insertion...",
                            i + 1, end, batchEx.getMessage());
                    for (Document doc : batch) {
                        try {
                            vectorStore.accept(List.of(doc));
                            successfullyIndexed++;
                        } catch (Exception singleEx) {
                            log.error("Skipping un-embeddable document chunk (id: {}, length: {} chars, preview: '{}...'): {}",
                                    doc.getId(),
                                    doc.getText() != null ? doc.getText().length() : 0,
                                    doc.getText() != null ? doc.getText().substring(0, Math.min(60, doc.getText().length())) : "null",
                                    singleEx.getMessage());
                        }
                    }
                }
                log.info("Vector ingestion progress: [{}/{} documents indexed ({}%)]",
                        end, total, (int) Math.round((double) end / total * 100));
            }

            log.info("Vector Store successfully loaded with {}/{} sanitized documents via PyMuPDF + Vision pipeline.", successfullyIndexed, total);
            ingestionComplete.set(true);
        } finally {
            ingesting.set(false);
            ingestionLatch.countDown();
        }
    }

    /**
     * Checks if ingestion is 100% complete and documents are in the vector store.
     */
    public boolean isIngestionComplete() {
        return ingestionComplete.get();
    }

    /**
     * Checks if ingestion is currently running.
     */
    public boolean isIngesting() {
        return ingesting.get();
    }

    /**
     * Blocks caller until ingestion finishes, or timeout occurs.
     *
     * @param timeout Timeout duration
     * @param unit TimeUnit
     * @return true if ingestion finished, false if timed out
     */
    public boolean awaitIngestionComplete(long timeout, TimeUnit unit) throws InterruptedException {
        if (ingestionComplete.get()) {
            return true;
        }
        return ingestionLatch.await(timeout, unit);
    }

    /** Count how many vector rows belong to the given source document. */
    private long countVectorsBySource(String sourceDocumentId) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM vector_store WHERE metadata->>'source_document_id' = ?",
                    Long.class, sourceDocumentId);
            return count != null ? count : 0;
        } catch (Exception e) {
            // On first run the table may not exist yet — treat as 0
            log.debug("Could not count vectors for source='{}': {}", sourceDocumentId, e.getMessage());
            return 0;
        }
    }

    /** Delete all vector rows belonging to the given source document. */
    private void clearVectorsBySource(String sourceDocumentId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'source_document_id' = ?",
                sourceDocumentId);
        log.info("Deleted {} existing vector rows for source='{}'", deleted, sourceDocumentId);
    }

    /**
     * Derive a stable, human-readable source document ID from the resource name.
     * Uses only the filename (not path) so that moving the PDF doesn't change its ID.
     *
     * <p>For URL resources (classpath:, file:) we extract the last path segment.
     */
    private String deriveSourceDocumentId(org.springframework.core.io.Resource resource) {
        try {
            String filename = resource.getFilename();
            if (filename != null && !filename.isBlank()) {
                // Strip extension for a clean identifier
                int dot = filename.lastIndexOf('.');
                return dot > 0 ? filename.substring(0, dot) : filename;
            }
            // Fallback: hash the URI
            return "doc_" + Integer.toHexString(resource.getURI().toString().hashCode());
        } catch (Exception e) {
            log.warn("Could not derive source document ID: {}", e.getMessage());
            return "unknown_document";
        }
    }
}