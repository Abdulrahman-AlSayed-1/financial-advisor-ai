package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.ingestion.extractor.PdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.retrieval.EntityRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {"spring.config.name=application-test", "app.vision.enabled=true"})
public class EmbeddingDiagnosticRunnerTest {

    @Autowired
    private PdfExtractor pdfExtractor;

    @Autowired
    private PyMuPdfPipelineProcessor pipelineProcessor;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EntityRegistry entityRegistry;

    @Test
    @DisplayName("Comprehensive Batch Embedding Diagnostic")
    void runDiagnostic() {
        System.out.println("============================================================");
        System.out.println("1. EXTRACTING DOCUMENTS AND CHUNKING");
        System.out.println("============================================================");

        RawPdfDocument rawDoc = pdfExtractor.extract(new ClassPathResource("docs/pymupdf_extraction.json"));
        List<Document> rawChunks = pipelineProcessor.process(rawDoc, "article_thebeatoct2024");
        System.out.println("Raw chunks produced: " + rawChunks.size());

        // 1. Sanitize & inspect documents
        List<Document> validChunks = new ArrayList<>();
        List<Document> invalidChunks = new ArrayList<>();

        for (int idx = 0; idx < rawChunks.size(); idx++) {
            Document doc = rawChunks.get(idx);
            String text = doc.getText();
            if (text == null || text.trim().isEmpty() || text.isBlank()) {
                invalidChunks.add(doc);
                System.out.printf("INVALID CHUNK at index %d: id=%s, type=%s, page=%s%n",
                        idx, doc.getId(), doc.getMetadata().get("chunk_type"), doc.getMetadata().get("page_number"));
            } else {
                validChunks.add(doc);
            }
        }

        System.out.println("Valid chunks: " + validChunks.size());
        System.out.println("Invalid chunks: " + invalidChunks.size());

        // 2. Chunk-type Histogram
        System.out.println("============================================================");
        System.out.println("2. CHUNK-TYPE HISTOGRAM (ACCOUNTING FOR ALL " + validChunks.size() + " CHUNKS)");
        System.out.println("============================================================");

        Map<String, Long> chunkTypeCounts = validChunks.stream()
                .collect(Collectors.groupingBy(d -> String.valueOf(d.getMetadata().get("chunk_type")), Collectors.counting()));

        long sumCount = 0;
        for (Map.Entry<String, Long> entry : chunkTypeCounts.entrySet()) {
            System.out.printf("  chunk_type '%-20s' -> %d%n", entry.getKey(), entry.getValue());
            sumCount += entry.getValue();
        }
        System.out.println("  Total accounted chunks: " + sumCount);

        // 3. Entity Registry Check
        System.out.println("============================================================");
        System.out.println("3. ENTITY REGISTRY INTEGRITY CHECK");
        System.out.println("============================================================");
        int preRegSize = validChunks.size();
        entityRegistry.populateFromDocuments(validChunks);
        EntityRegistry.RegistryStats stats = entityRegistry.getStats();
        System.out.printf("  Registry stats: %d unique entities, %d total occurrences%n",
                stats.getUniqueEntities(), stats.getTotalOccurrences());
        System.out.printf("  validChunks list size before/after: %d / %d%n", preRegSize, validChunks.size());

        // 3.1 Detailed Uniqueness and Duplication Analysis
        System.out.println("============================================================");
        System.out.println("3.1 DETAILED DOCUMENT ID & EQUALITY DUPLICATION TRACE");
        System.out.println("============================================================");

        Map<String, List<Integer>> idToIndices = new LinkedHashMap<>();
        Map<Document, List<Integer>> docToIndices = new LinkedHashMap<>();

        for (int idx = 0; idx < validChunks.size(); idx++) {
            Document d = validChunks.get(idx);
            idToIndices.computeIfAbsent(d.getId(), k -> new ArrayList<>()).add(idx);
            docToIndices.computeIfAbsent(d, k -> new ArrayList<>()).add(idx);
        }

        List<Map.Entry<String, List<Integer>>> duplicateIdEntries = idToIndices.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .toList();

        List<Map.Entry<Document, List<Integer>>> duplicateDocEntries = docToIndices.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .toList();

        System.out.printf("Total Valid Chunks: %d%n", validChunks.size());
        System.out.printf("Unique Document IDs: %d (Duplicate ID groups: %d)%n", idToIndices.size(), duplicateIdEntries.size());
        System.out.printf("Unique Document Objects (equals): %d (Duplicate Object groups: %d)%n%n", docToIndices.size(), duplicateDocEntries.size());

        System.out.println("--- LIST OF ALL DUPLICATE DOCUMENT GROUPS ---");
        int dupGroupIndex = 0;
        for (Map.Entry<String, List<Integer>> entry : duplicateIdEntries) {
            dupGroupIndex++;
            String id = entry.getKey();
            List<Integer> indices = entry.getValue();
            Document firstDoc = validChunks.get(indices.get(0));

            int pageNum = (int) firstDoc.getMetadata().getOrDefault("page_number", -1);
            String chunkType = String.valueOf(firstDoc.getMetadata().get("chunk_type"));
            String section = String.valueOf(firstDoc.getMetadata().get("section"));
            String errReason = String.valueOf(firstDoc.getMetadata().get("error_reason"));
            int contentHash = firstDoc.getText() != null ? firstDoc.getText().hashCode() : 0;
            int textLen = firstDoc.getText() != null ? firstDoc.getText().length() : 0;

            List<Integer> batches = indices.stream().map(i -> (i / 50) + 1).distinct().toList();

            System.out.printf("Duplicate Group #%d: ID=%s (count=%d)%n", dupGroupIndex, id, indices.size());
            System.out.printf("  Chunk Indices: %s%n", indices);
            System.out.printf("  Batches Affected: %s (Batch Size 50)%n", batches);
            System.out.printf("  Page: %d, Type: %s, Section: '%s'%n", pageNum, chunkType, section);
            System.out.printf("  Error Reason: %s, Content Hash: %d, Text Length: %d%n", errReason, contentHash, textLen);
            System.out.printf("  Text Preview: %s%n%n", firstDoc.getText().replace("\n", " \\n "));
        }

        // 4. Batch Position & Content Analysis (Batches of 50)
        System.out.println("============================================================");
        System.out.println("4. TOKEN COUNT BATCHING STRATEGY ACROSS ALL 16 BATCHES");
        System.out.println("============================================================");
        org.springframework.ai.embedding.TokenCountBatchingStrategy tokenBatching = new org.springframework.ai.embedding.TokenCountBatchingStrategy();

        int batchSize = 50;
        int total = validChunks.size();
        int batchIndex = 0;
        List<Integer> failedBatchIndices = new ArrayList<>();

        for (int i = 0; i < total; i += batchSize) {
            batchIndex++;
            int end = Math.min(i + batchSize, total);
            List<Document> batch = validChunks.subList(i, end);

            List<List<Document>> strategyBatches = tokenBatching.batch(batch);
            int strategyDocCount = strategyBatches.stream().mapToInt(List::size).sum();

            boolean mismatch = (strategyDocCount != batch.size());
            if (mismatch) {
                failedBatchIndices.add(batchIndex);
            }

            System.out.printf("Batch #%2d [%3d - %3d]: Input=%2d | Strategy Out=%2d | SubBatches=%d | Status=%s%n",
                    batchIndex, i + 1, end, batch.size(), strategyDocCount, strategyBatches.size(),
                    mismatch ? "MISMATCH (FAILED)" : "OK (SUCCESS)");
        }

        System.out.println("============================================================");
        System.out.println("CONFIRMED FAILED BATCHES: " + failedBatchIndices);
        System.out.println("============================================================");
    }

    private void diagnoseFailedBatch(List<Document> batch, int globalOffset) {
        System.out.println("  >>> DEEP DIVE INTO FAILED BATCH <<<");
        // Test sub-batches: 25, 10, 1
        System.out.println("  Testing individual documents in this batch (1-by-1):");
        int singleSuccess = 0;
        int singleFail = 0;

        for (int j = 0; j < batch.size(); j++) {
            Document doc = batch.get(j);
            String text = doc.getText();
            int docIdx = globalOffset + j + 1;
            try {
                EmbeddingRequest singleReq = new EmbeddingRequest(List.of(text), null);
                EmbeddingResponse singleResp = embeddingModel.call(singleReq);
                int got = singleResp.getResults() != null ? singleResp.getResults().size() : 0;
                if (got != 1) {
                    System.out.printf("    Doc #%d (id=%s, type=%s, page=%s, len=%d): returned %d embeddings!%n",
                            docIdx, doc.getId(), doc.getMetadata().get("chunk_type"), doc.getMetadata().get("page_number"), text.length(), got);
                    singleFail++;
                } else {
                    singleSuccess++;
                }
            } catch (Exception e) {
                System.out.printf("    Doc #%d (id=%s, type=%s, page=%s, len=%d): EXCEPTION %s: %s%n",
                        docIdx, doc.getId(), doc.getMetadata().get("chunk_type"), doc.getMetadata().get("page_number"), text.length(), e.getClass().getSimpleName(), e.getMessage());
                singleFail++;
            }
        }
        System.out.printf("  Individual doc test summary: %d succeeded, %d failed%n", singleSuccess, singleFail);

        // Test sub-batch sizes: 5, 10, 20, 25
        for (int subSize : List.of(5, 10, 20, 25)) {
            System.out.printf("  Testing sub-batch size = %d on this batch:%n", subSize);
            for (int k = 0; k < batch.size(); k += subSize) {
                int kEnd = Math.min(k + subSize, batch.size());
                List<String> subTexts = batch.subList(k, kEnd).stream().map(Document::getText).toList();
                try {
                    EmbeddingResponse resp = embeddingModel.call(new EmbeddingRequest(subTexts, null));
                    int got = resp.getResults() != null ? resp.getResults().size() : 0;
                    System.out.printf("    sub-batch [%d - %d] (size %d): returned %d embeddings%n", k, kEnd, subTexts.size(), got);
                } catch (Exception e) {
                    System.out.printf("    sub-batch [%d - %d] (size %d): EXCEPTION %s: %s%n", k, kEnd, subTexts.size(), e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }
}
