package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.chunking.CanonicalTable;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableChunker;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableParser;
import com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkBuilder;
import com.alsayed.financial_advisor_ai.chunking.text.TextNormalizer;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfPage;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import com.alsayed.financial_advisor_ai.model.region.ContentRegion;
import com.alsayed.financial_advisor_ai.model.region.RegionType;
import com.alsayed.financial_advisor_ai.vision.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 4-Stage Multimodal Ingestion Pipeline:
 * RawPdfDocument -> ContentRegions -> Normalized Elements / Vision -> Spring AI Documents.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PyMuPdfPipelineProcessor {

    private final ContentClassifier contentClassifier;
    private final TextNormalizer textNormalizer;
    private final CanonicalTableParser tableParser;
    private final CanonicalTableChunker tableChunker;
    private final VisionResponseParser visionParser;
    private final FigureChunkBuilder figureChunkBuilder;

    @Autowired(required = false)
    private VisionService visionService;

    @Value("${app.ingestion.extraction.images-dir:src/main/resources/docs/extraction_output}")
    private String extractionOutputDir;

    @Value("${app.vision.enabled:true}")
    private boolean visionEnabled;

    /**
     * Process an entire RawPdfDocument into Spring AI Document chunks.
     *
     * @param rawDoc The extracted PDF document
     * @return ordered list of Spring AI Documents ready for embedding
     */
    public List<Document> process(RawPdfDocument rawDoc) {
        return process(rawDoc, "");
    }

    /**
     * Process an entire RawPdfDocument into Spring AI Document chunks,
     * tagging every chunk with the given sourceDocumentId for idempotent ingestion.
     *
     * @param rawDoc           The extracted PDF document
     * @param sourceDocumentId Stable identifier for the source document
     * @return ordered list of Spring AI Documents ready for embedding
     */
    public List<Document> process(RawPdfDocument rawDoc, String sourceDocumentId) {
        log.info("Starting PyMuPdfPipelineProcessor on '{}' ({} pages) sourceDocumentId='{}'",
                rawDoc.sourceFile(), rawDoc.totalPages(), sourceDocumentId);

        List<Document> allChunks = new ArrayList<>();

        int textChunkCount = 0;
        int tableChunkCount = 0;
        int figureChunkCount = 0;

        for (RawPdfPage page : rawDoc.pages()) {
            int pageNum = page.pageNumber();
            String pageSection = resolvePageSection(page);
            List<ContentRegion> regions = contentClassifier.classifyPage(page);

            for (int regionIdx = 0; regionIdx < regions.size(); regionIdx++) {
                ContentRegion region = regions.get(regionIdx);
                switch (region.type()) {
                    case TEXT -> {
                        List<Document> textChunks = textNormalizer.assembleChunks(
                                pageNum, region.enclosedText(), pageSection, rawDoc.sourceFile(), sourceDocumentId);
                        allChunks.addAll(textChunks);
                        textChunkCount += textChunks.size();
                    }
                    case SIMPLE_TABLE -> {
                        if (region.rawTable() != null && region.rawTable().markdown() != null) {
                            String tableId = "page_" + pageNum + "_tbl_" + region.rawTable().tableIndex();
                            String tableTitle = resolveTableTitle(page, region.bbox(), pageSection);
                            CanonicalTable canonicalTable = tableParser.parseMarkdownTable(
                                    region.rawTable().markdown(), tableId, tableTitle, pageSection, pageNum, region.enclosedText());

                            List<Document> tblChunks = tableChunker.chunk(
                                    canonicalTable,
                                    sourceDocumentId,
                                    List.of(String.valueOf(region.rawTable().tableIndex())),
                                    List.of(tableId),
                                    List.of(pageNum),
                                    List.of(), List.of(), List.of());
                            allChunks.addAll(tblChunks);
                            tableChunkCount += tblChunks.size();
                        }
                    }
                    case COMPLEX_TABLE -> {
                        String tableId = "page_" + pageNum + "_complex_tbl_" + regionIdx;
                        String tableTitle = resolveTableTitle(page, region.bbox(), pageSection);
                        CanonicalTable canonicalTable = null;

                        if (visionEnabled && visionService != null) {
                            byte[] imgData = loadCroppedOrPageImage(pageNum, region.bbox(), page.pageImagePath());
                            if (imgData != null && imgData.length > 0) {
                                String prompt = String.format(VisionPromptTemplates.COMPLEX_TABLE_PROMPT, pageNum, pageSection);
                                VisionRequest req = VisionRequest.builder()
                                        .primaryImageData(imgData)
                                        .mimeType("image/png")
                                        .taskType(VisionTaskType.COMPLEX_TABLE_EXTRACTION)
                                        .prompt(prompt)
                                        .contextMetadata(Map.of("page", pageNum, "section", pageSection))
                                        .build();

                                VisionResponse resp = visionService.interpret(req);
                                canonicalTable = visionParser.parseComplexTable(resp, tableId, pageNum, pageSection);
                            }
                        }

                        if ((canonicalTable == null || canonicalTable.getRows() == null || canonicalTable.getRows().isEmpty())
                                && region.rawTable() != null && region.rawTable().markdown() != null && !region.rawTable().markdown().isBlank()) {
                            canonicalTable = tableParser.parseMarkdownTable(
                                    region.rawTable().markdown(), tableId, tableTitle, pageSection, pageNum, region.enclosedText());
                        }

                        if (canonicalTable != null && canonicalTable.getRows() != null && !canonicalTable.getRows().isEmpty()) {
                            List<Document> tblChunks = tableChunker.chunk(
                                    canonicalTable,
                                    sourceDocumentId,
                                    List.of("complex_table"),
                                    List.of(tableId),
                                    List.of(pageNum),
                                    List.of(), List.of(), List.of());
                            allChunks.addAll(tblChunks);
                            tableChunkCount += tblChunks.size();
                        }
                    }
                    case FIGURE_CANDIDATE -> {
                        if (visionEnabled && visionService != null) {
                            byte[] imgData = loadCroppedOrPageImage(pageNum, region.bbox(), page.pageImagePath());
                            if (imgData != null && imgData.length > 0) {
                                String surroundingContext = buildSurroundingContext(page.textBlocks());
                                String prompt = String.format(VisionPromptTemplates.FIGURE_INTERPRETATION_PROMPT, pageNum, pageSection, surroundingContext);
                                VisionRequest req = VisionRequest.builder()
                                        .primaryImageData(imgData)
                                        .mimeType("image/png")
                                        .taskType(VisionTaskType.FIGURE_INTERPRETATION)
                                        .prompt(prompt)
                                        .contextMetadata(Map.of("page", pageNum, "section", pageSection))
                                        .build();

                                VisionResponse resp = visionService.interpret(req);
                                FigureInterpretation interpretation = visionParser.parseFigure(resp);

                                if (interpretation != null) {
                                    List<Document> figChunks = figureChunkBuilder.buildChunks(
                                            interpretation, pageNum, pageSection,
                                            rawDoc.sourceFile(), sourceDocumentId, page.textBlocks(),
                                            page.tables(), regionIdx, region.bbox());
                                    allChunks.addAll(figChunks);
                                    figureChunkCount += figChunks.size();
                                }
                            }
                        }
                    }
                    case DECORATIVE -> {
                        // intentionally skipped
                    }
                }
            }
        }

        log.info("PyMuPdfPipelineProcessor finished. Created {} total chunks ({} paragraphs, {} tables, {} figures)",
                allChunks.size(), textChunkCount, tableChunkCount, figureChunkCount);

        return allChunks;
    }

    public String resolvePageSection(RawPdfPage page) {
        if (page == null || page.textBlocks() == null || page.textBlocks().isEmpty()) {
            return "Market Overview";
        }

        RawPdfTextBlock bestHeading = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (RawPdfTextBlock b : page.textBlocks()) {
            String text = b.text() != null ? b.text().trim() : "";
            if (!ContentClassifier.isValidSectionOrTableTitle(text, b.fontSize())) continue;

            // Must be in the top portion of the page (Y0 <= 300)
            if (b.getY0() > 300) continue;

            double score = 0.0;
            if (b.fontSize() >= 18.0) score += 130.0;
            else if (b.fontSize() >= 14.0) score += 100.0;
            else if (b.fontSize() >= 12.0) score += 60.0;
            else if (b.fontSize() >= 11.0) score += 40.0;
            else score += b.fontSize() * 2.0;

            if (b.isLikelyHeading() || b.fontSize() >= 18.0) score += 40.0;

            // Top-of-page reward
            if (b.getY0() <= 150) score += (150 - b.getY0()) * 0.4;

            // Concise header reward
            if (text.length() >= 5 && text.length() <= 80) score += 20.0;

            if (score > bestScore) {
                bestScore = score;
                bestHeading = b;
            }
        }

        return (bestHeading != null && !bestHeading.text().isBlank()) ? bestHeading.text().trim() : "Market Overview";
    }

    public String resolveTableTitle(RawPdfPage page, double[] tableBbox, String pageSection) {
        if (page == null || page.textBlocks() == null || page.textBlocks().isEmpty()) {
            return pageSection;
        }

        double tableY0 = tableBbox != null && tableBbox.length > 1 ? tableBbox[1] : Double.MAX_VALUE;
        double tableX0 = tableBbox != null && tableBbox.length > 0 ? tableBbox[0] : 0;
        double tableX1 = tableBbox != null && tableBbox.length > 2 ? tableBbox[2] : Double.MAX_VALUE;

        RawPdfTextBlock bestHeading = null;
        String bestTitleCandidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (RawPdfTextBlock b : page.textBlocks()) {
            String text = b.text() != null ? b.text().trim() : "";
            if (!ContentClassifier.isValidSectionOrTableTitle(text, b.fontSize())) continue;

            double bY1 = b.getY1();
            if (bY1 > tableY0 + 15) continue;
            double verticalDist = tableY0 - bY1;
            if (verticalDist > 160) continue;

            // Check if block contains multiple side-by-side titles separated by 2+ spaces or newline
            String resolvedTitle = text;
            if (text.contains("  ") || text.contains("\n")) {
                String[] segments = text.split(" {2,}|\n");
                if (segments.length >= 2) {
                    double blockWidth = Math.max(1.0, b.getX1() - b.getX0());
                    double segWidth = blockWidth / segments.length;
                    for (int s = 0; s < segments.length; s++) {
                        String seg = segments[s].trim();
                        if (seg.isEmpty()) continue;
                        double segX0 = b.getX0() + s * segWidth;
                        double segX1 = segX0 + segWidth;
                        if ((segX0 <= tableX1 + 40) && (segX1 >= tableX0 - 40)) {
                            resolvedTitle = seg;
                            break;
                        }
                    }
                }
            }

            double score = 0.0;
            score += b.fontSize() * 4.0;
            if (b.isLikelyHeading()) score += 25.0;

            score += Math.max(0, 100.0 - verticalDist);

            boolean horizOverlap = (b.getX0() <= tableX1 + 20) && (b.getX1() >= tableX0 - 20);
            if (horizOverlap) score += 50.0;

            if (score > bestScore) {
                bestScore = score;
                bestHeading = b;
                bestTitleCandidate = resolvedTitle;
            }
        }

        return (bestTitleCandidate != null && !bestTitleCandidate.isBlank()) ? bestTitleCandidate.trim() : pageSection;
    }

    private String buildSurroundingContext(List<RawPdfTextBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (RawPdfTextBlock b : blocks) {
            sb.append(b.text()).append(" ");
            if (sb.length() > 500) break;
        }
        return sb.toString().trim();
    }

    private byte[] loadCroppedOrPageImage(int pageNum, double[] bbox, String relPageImgPath) {
        try {
            // First look for full page render
            Path pagePath = Path.of(extractionOutputDir, "pages", "page_" + pageNum + ".png");
            if (!pagePath.toFile().exists() && relPageImgPath != null) {
                pagePath = Path.of(extractionOutputDir, relPageImgPath);
            }

            if (pagePath.toFile().exists()) {
                // If bbox is valid, crop with padding
                if (bbox != null && bbox.length == 4 && bbox[2] > bbox[0] && bbox[3] > bbox[1]) {
                    BufferedImage fullImage = ImageIO.read(pagePath.toFile());
                    if (fullImage != null) {
                        // PDF coordinate -> image coordinate (150 DPI / 72 DPI ≈ 2.0833)
                        double scale = (double) fullImage.getWidth() / 612.0; // standard width or calculate
                        int x0 = (int) Math.max(0, (bbox[0] - 20) * scale);
                        int y0 = (int) Math.max(0, (bbox[1] - 35) * scale);
                        int x1 = (int) Math.min(fullImage.getWidth(), (bbox[2] + 20) * scale);
                        int y1 = (int) Math.min(fullImage.getHeight(), (bbox[3] + 35) * scale);

                        int w = Math.max(10, x1 - x0);
                        int h = Math.max(10, y1 - y0);

                        if (x0 + w <= fullImage.getWidth() && y0 + h <= fullImage.getHeight()) {
                            BufferedImage cropped = fullImage.getSubimage(x0, y0, w, h);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(cropped, "png", baos);
                            return baos.toByteArray();
                        }
                    }
                }

                // Fallback: return full page image
                return Files.readAllBytes(pagePath);
            }
        } catch (Exception e) {
            log.warn("Could not crop/load image for page {}: {}", pageNum, e.getMessage());
        }
        return new byte[0];
    }
}
