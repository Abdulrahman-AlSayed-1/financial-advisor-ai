package com.alsayed.financial_advisor_ai.chunking.text;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Normalizes and groups PyMuPDF text blocks into heading-aware paragraph chunks.
 */
@Slf4j
@Component
public class TextNormalizer {

    @Value("${app.chunking.target-chars:3000}")
    private int targetChars;

    @Value("${app.chunking.min-chars:80}")
    private int minChars;

    /**
     * Assemble text blocks from a page into Spring AI Document chunks.
     * Tracks the current section heading across pages.
     */
    public List<Document> assembleChunks(int pageNumber, List<RawPdfTextBlock> textBlocks, String currentSectionHeading, String sourceFile) {
        return assembleChunks(pageNumber, textBlocks, currentSectionHeading, sourceFile, "");
    }

    /**
     * Assemble text blocks from a page into Spring AI Document chunks with document scoping.
     * Tracks the current section heading across pages.
     */
    public List<Document> assembleChunks(int pageNumber, List<RawPdfTextBlock> textBlocks, String currentSectionHeading, String sourceFile, String sourceDocumentId) {
        List<Document> chunks = new ArrayList<>();
        if (textBlocks == null || textBlocks.isEmpty()) {
            return chunks;
        }

        // Sort reading order: Top-to-Bottom (y0), then Left-to-Right (x0)
        List<RawPdfTextBlock> sortedBlocks = new ArrayList<>(textBlocks);
        sortedBlocks.sort(Comparator
                .comparingDouble(RawPdfTextBlock::getY0)
                .thenComparingDouble(RawPdfTextBlock::getX0));

        String currentSection = currentSectionHeading != null && !currentSectionHeading.isBlank()
                ? currentSectionHeading
                : "Overview";

        StringBuilder buffer = new StringBuilder();
        List<Integer> blockIndices = new ArrayList<>();

        for (RawPdfTextBlock block : sortedBlocks) {
            String text = block.text().trim();
            if (text.isEmpty()) continue;

            if (block.isLikelyHeading() && buffer.length() >= targetChars) {
                String textContent = buffer.toString().trim();
                if (!isDisconnectedNumberDump(textContent)) {
                    chunks.add(createParagraphChunk(textContent, currentSection, pageNumber, sourceFile, sourceDocumentId, blockIndices));
                }
                buffer.setLength(0);
                blockIndices.clear();
                currentSection = text;
            } else if (block.isLikelyHeading() && buffer.length() == 0) {
                currentSection = text;
            }

            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(text);
            blockIndices.add(block.blockIndex());

            if (buffer.length() >= targetChars) {
                String textContent = buffer.toString().trim();
                if (!isDisconnectedNumberDump(textContent)) {
                    chunks.add(createParagraphChunk(textContent, currentSection, pageNumber, sourceFile, sourceDocumentId, blockIndices));
                }
                buffer.setLength(0);
                blockIndices.clear();
            }
        }

        if (buffer.length() > 0) {
            String textContent = buffer.toString().trim();
            if (!isDisconnectedNumberDump(textContent)) {
                chunks.add(createParagraphChunk(textContent, currentSection, pageNumber, sourceFile, sourceDocumentId, blockIndices));
            }
        }

        return chunks;
    }

    private boolean isDisconnectedNumberDump(String content) {
        if (content == null || content.isBlank()) return true;
        String[] lines = content.split("\n+");
        if (lines.length < 4) return false;
        int shortOrNumericLines = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.matches("^[-+]?\\d+(\\.\\d+)?%?$") || trimmed.length() <= 12) {
                shortOrNumericLines++;
            }
        }
        return (double) shortOrNumericLines / lines.length > 0.65;
    }

    private Document createParagraphChunk(String content, String section, int pageNumber, String sourceFile, String sourceDocumentId, List<Integer> blockIndices) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunk_type", "paragraph");
        metadata.put("content_type", "paragraph");
        metadata.put("section", section);
        metadata.put("page_number", pageNumber);
        metadata.put("source", "pymupdf");
        metadata.put("source_file", sourceFile != null ? sourceFile : "document.pdf");
        metadata.put("source_document_id", sourceDocumentId != null ? sourceDocumentId : "");
        metadata.put("extraction_status", "COMPLETE");
        metadata.put("visual_interpretation", "NOT_REQUIRED");
        metadata.put("validation_status", "VERIFIED_AGREEMENT");
        metadata.put("block_indices", new ArrayList<>(blockIndices));

        // Deterministic UUID for pgvector
        String docPrefix = (sourceDocumentId != null && !sourceDocumentId.isBlank()) ? sourceDocumentId : (sourceFile != null ? sourceFile : "doc");
        String deterministicSeed = docPrefix + "_p_" + pageNumber + "_sec_" + section + "_" + content.hashCode();
        String chunkId = UUID.nameUUIDFromBytes(deterministicSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

        String chunkContent = section != null && !section.isEmpty() && !content.startsWith(section)
                ? "Section: " + section + "\n\n" + content
                : content;

        return new Document(chunkId, chunkContent, metadata);
    }
}
