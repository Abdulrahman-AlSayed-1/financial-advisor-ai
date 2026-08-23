package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.chunking.table.DeterministicTableQualityAssessor;
import com.alsayed.financial_advisor_ai.model.raw.*;
import com.alsayed.financial_advisor_ai.model.region.ContentRegion;
import com.alsayed.financial_advisor_ai.model.region.RegionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Classifies page regions into processing pipelines based strictly on observable primitives.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentClassifier {

    private final DeterministicTableQualityAssessor tableAssessor;

    public List<ContentRegion> classifyPage(RawPdfPage page) {
        List<ContentRegion> regions = new ArrayList<>();
        List<double[]> occupiedBboxes = new ArrayList<>();

        int pageNumber = page.pageNumber();
        double pageWidth = page.width();
        double pageHeight = page.height();

        // 1. First, detect full Text-Based Structured Tables from text blocks (e.g. Page 20 Key Rates)
        List<RawPdfTextBlock> candidateTextBlocks = new ArrayList<>();
        if (page.textBlocks() != null) {
            for (RawPdfTextBlock b : page.textBlocks()) {
                double[] bBbox = b.bbox() != null ? b.bbox() : new double[]{0, 0, 0, 0};
                if (!isHeaderOrFooter(bBbox, pageHeight, b.fontSize())) {
                    candidateTextBlocks.add(b);
                }
            }
        }
        List<ContentRegion> textBasedTables = detectAndExtractTextBasedTables(pageNumber, candidateTextBlocks, occupiedBboxes);
        regions.addAll(textBasedTables);

        // 2. Process PyMuPDF Structural Tables (if not overlapping with complete text tables)
        if (page.tables() != null) {
            for (RawPdfTable table : page.tables()) {
                double[] tBbox = table.bbox() != null ? table.bbox() : new double[]{0, 0, pageWidth, pageHeight};
                if (intersectsAny(tBbox, occupiedBboxes)) {
                    continue;
                }

                var assessment = tableAssessor.assess(table, page.textBlocks());
                if (assessment.isAcceptableDeterministic()) {
                    occupiedBboxes.add(tBbox);
                    List<RawPdfTextBlock> associatedText = findAssociatedTextBlocks(table, candidateTextBlocks, occupiedBboxes);
                    regions.add(new ContentRegion(
                            pageNumber,
                            RegionType.SIMPLE_TABLE,
                            tBbox,
                            associatedText,
                            table,
                            null,
                            null,
                            null,
                            null
                    ));
                } else if (!isPageChart(page, table)) {
                    occupiedBboxes.add(tBbox);
                    List<RawPdfTextBlock> associatedText = findAssociatedTextBlocks(table, candidateTextBlocks, occupiedBboxes);
                    regions.add(new ContentRegion(
                            pageNumber,
                            RegionType.COMPLEX_TABLE,
                            tBbox,
                            associatedText,
                            table,
                            null,
                            null,
                            null,
                            null
                    ));
                }
            }
        }

        // 3. Process Embedded Images & Vector Drawings (Figure/Chart Candidates)
        if (page.images() != null) {
            for (RawPdfImage img : page.images()) {
                double[] bbox = img.bbox() != null ? img.bbox() : new double[]{0, 0, 0, 0};
                if (isDecorative(bbox, pageHeight, img.widthPx(), img.heightPx())) {
                    continue;
                }

                if (!intersectsAny(bbox, occupiedBboxes)) {
                    occupiedBboxes.add(bbox);
                    List<RawPdfTextBlock> enclosed = findEnclosedTextBlocks(bbox, page.textBlocks());
                    regions.add(new ContentRegion(
                            pageNumber,
                            RegionType.FIGURE_CANDIDATE,
                            bbox,
                            enclosed,
                            null,
                            img,
                            null,
                            img.imagePath(),
                            null
                    ));
                }
            }
        }

        List<double[]> tableBboxes = new ArrayList<>(occupiedBboxes);

        // 3. Process Vector Drawings (Cluster vector graphics into Figure Candidates)
        if (page.drawings() != null) {
            List<RawPdfDrawing> candidateDrawings = new ArrayList<>();
            for (RawPdfDrawing drawing : page.drawings()) {
                if (isPageBackgroundDrawing(drawing, pageWidth, pageHeight)) {
                    continue; // Skip full-page background canvas rectangles
                }

                double[] bbox = drawing.bbox() != null ? drawing.bbox() : new double[]{0, 0, 0, 0};
                if (!intersectsAny(bbox, occupiedBboxes)) {
                    candidateDrawings.add(drawing);
                }
            }

            // Cluster spatially adjacent vector drawings
            List<List<RawPdfDrawing>> drawingClusters = clusterDrawings(candidateDrawings);
            for (List<RawPdfDrawing> cluster : drawingClusters) {
                double[] clusterBbox = computeUnionBbox(cluster);
                double clusterWidth = clusterBbox[2] - clusterBbox[0];
                double clusterHeight = clusterBbox[3] - clusterBbox[1];

                // A valid visual chart cluster has either:
                // 1) >= 3 vector primitives spanning a substantial area (width >= 80, height >= 50)
                // 2) A single substantial drawing (width >= 100, height >= 80)
                boolean isSubstantial = (cluster.size() >= 3 && clusterWidth >= 80 && clusterHeight >= 50)
                        || (clusterWidth >= 100 && clusterHeight >= 80);

                if (isSubstantial && !intersectsAny(clusterBbox, occupiedBboxes)) {
                    // Expand cluster bbox vertically to encompass attached chart titles / axis labels
                    double[] expandedBbox = expandBboxToEncloseNearbyText(clusterBbox, page.textBlocks(), occupiedBboxes, pageHeight);
                    occupiedBboxes.add(expandedBbox);
                    List<RawPdfTextBlock> enclosed = findEnclosedTextBlocks(expandedBbox, page.textBlocks());
                    regions.add(new ContentRegion(
                            pageNumber,
                            RegionType.FIGURE_CANDIDATE,
                            expandedBbox,
                            enclosed,
                            null,
                            null,
                            cluster.get(0),
                            null,
                            null
                    ));
                }
            }
        }

        // 3. Process Text Blocks that are not inside structured table bboxes
        // Note: Narrative text outside structured table regions is preserved as standalone text
        List<RawPdfTextBlock> standaloneText = new ArrayList<>();
        if (page.textBlocks() != null) {
            for (RawPdfTextBlock block : page.textBlocks()) {
                double[] bBbox = block.bbox() != null ? block.bbox() : new double[]{0, 0, 0, 0};
                // Skip header/footer line text (top 5% or bottom 5% of page if small font)
                if (isHeaderOrFooter(bBbox, pageHeight, block.fontSize())) {
                    continue;
                }

                if (!intersectsAny(bBbox, tableBboxes)) {
                    standaloneText.add(block);
                }
            }
        }

        if (!standaloneText.isEmpty()) {
            regions.add(new ContentRegion(
                    pageNumber,
                    RegionType.TEXT,
                    new double[]{0, 0, pageWidth, pageHeight},
                    standaloneText,
                    null,
                    null,
                    null,
                    null,
                    null
            ));
        }

        return regions;
    }

    private List<RawPdfTextBlock> findEnclosedTextBlocks(double[] bbox, List<RawPdfTextBlock> textBlocks) {
        List<RawPdfTextBlock> enclosed = new ArrayList<>();
        if (bbox == null || bbox.length < 4 || textBlocks == null) {
            return enclosed;
        }
        for (RawPdfTextBlock block : textBlocks) {
            if (block.bbox() != null && intersects(bbox, block.bbox())) {
                enclosed.add(block);
            }
        }
        return enclosed;
    }

    private List<RawPdfTextBlock> findAssociatedTextBlocks(RawPdfTable table, List<RawPdfTextBlock> textBlocks, List<double[]> occupiedBboxes) {
        List<RawPdfTextBlock> associated = new ArrayList<>();
        if (table == null || textBlocks == null || textBlocks.isEmpty()) {
            return associated;
        }

        double[] tableBbox = table.bbox();
        if (tableBbox == null || tableBbox.length < 4) {
            return associated;
        }

        double tableX0 = tableBbox[0];
        double tableY0 = tableBbox[1];
        double tableX1 = tableBbox[2];
        double tableY1 = tableBbox[3];

        for (RawPdfTextBlock block : textBlocks) {
            if (block.bbox() == null || block.bbox().length < 4) continue;
            double bX0 = block.bbox()[0];
            double bY0 = block.bbox()[1];
            double bX1 = block.bbox()[2];
            double bY1 = block.bbox()[3];

            // Check if structurally tabular (repeating entity + numbers pattern)
            if (isTabularTextBlock(block.text(), table.colCount())) {
                // Check spatial proximity: either overlapping, directly below (within 350pt), or horizontally aligned
                boolean verticallyNear = (bY0 >= tableY0 - 30 && bY0 <= tableY1 + 350);
                boolean horizontallyAligned = (bX0 <= tableX1 + 50 && bX1 >= tableX0 - 50);

                if (verticallyNear && horizontallyAligned) {
                    associated.add(block);
                    occupiedBboxes.add(block.bbox());
                }
            }
        }
        return associated;
    }

    private boolean isTabularTextBlock(String text, int expectedColumns) {
        if (text == null || text.isBlank()) return false;
        String[] lines = text.split("\n");
        if (lines.length < 6) return false;

        int entityCount = 0;
        int numericCount = 0;
        int currentConsecutiveNumbers = 0;
        List<Integer> numberCountsPerEntity = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            boolean isNumeric = line.matches("^-?\\$?\\d+[\\.,]?\\d*%?$") || line.matches("^[-—–]$");
            if (isNumeric) {
                numericCount++;
                currentConsecutiveNumbers++;
            } else {
                if (currentConsecutiveNumbers > 0) {
                    numberCountsPerEntity.add(currentConsecutiveNumbers);
                    currentConsecutiveNumbers = 0;
                }
                entityCount++;
            }
        }
        if (currentConsecutiveNumbers > 0) {
            numberCountsPerEntity.add(currentConsecutiveNumbers);
        }

        // Tabular if we have >= 2 entities, each followed by multiple numbers
        if (entityCount >= 2 && !numberCountsPerEntity.isEmpty()) {
            long entitiesWithNumbers = numberCountsPerEntity.stream().filter(c -> c >= 2).count();
            return entitiesWithNumbers >= 2 && numericCount >= entityCount * 2;
        }
        return false;
    }

    private boolean isPageBackgroundDrawing(RawPdfDrawing drawing, double pageWidth, double pageHeight) {
        if (drawing == null || drawing.bbox() == null || drawing.bbox().length < 4) return false;
        double w = drawing.getWidth();
        double h = drawing.getHeight();
        double x0 = drawing.bbox()[0];
        double y0 = drawing.bbox()[1];

        // Background canvas covering >= 80% of page dimensions and starting near top-left origin
        boolean coversPage = (w >= pageWidth * 0.80 && h >= pageHeight * 0.80);
        boolean nearOrigin = (x0 <= 25.0 && y0 <= 25.0);

        return coversPage && (nearOrigin || drawing.rectCount() == 1 || drawing.lineCount() == 0);
    }

    private List<ContentRegion> detectAndExtractTextBasedTables(int pageNumber, List<RawPdfTextBlock> standaloneText, List<double[]> occupiedBboxes) {
        List<ContentRegion> tableRegions = new ArrayList<>();
        if (standaloneText == null || standaloneText.size() < 2) {
            return tableRegions;
        }

        Set<RawPdfTextBlock> consumed = new HashSet<>();

        for (int i = 0; i < standaloneText.size(); i++) {
            RawPdfTextBlock block = standaloneText.get(i);
            if (consumed.contains(block)) continue;

            // Check if this block is either a column header block OR the start of tabular row blocks
            String[] lines = block.text() != null ? block.text().split("\n") : new String[0];
            
            // Case A: Explicit single-block or multi-block Column Header followed by row blocks
            if (isTableColumnHeaderBlock(lines)) {
                int expectedCols = lines.length;
                List<RawPdfTextBlock> rowBlocks = new ArrayList<>();

                for (int j = i + 1; j < standaloneText.size(); j++) {
                    RawPdfTextBlock candidate = standaloneText.get(j);
                    if (consumed.contains(candidate)) break;

                    String[] rowLines = candidate.text() != null ? candidate.text().split("\n") : new String[0];
                    if (isTabularRowBlock(rowLines, expectedCols)) {
                        rowBlocks.add(candidate);
                    } else {
                        break;
                    }
                }

                if (rowBlocks.size() >= 2) {
                    consumed.add(block);
                    consumed.addAll(rowBlocks);

                    int colCount = expectedCols;
                    StringBuilder md = new StringBuilder();
                    md.append("| ").append(String.join(" | ", lines)).append(" |\n");
                    md.append("| ").append(String.join(" | ", Collections.nCopies(colCount, "---"))).append(" |\n");

                    double minX0 = block.getX0(), minY0 = block.getY0();
                    double maxX1 = block.getX1(), maxY1 = block.getY1();

                    for (RawPdfTextBlock rowBlock : rowBlocks) {
                        String[] rLines = rowBlock.text().split("\n");
                        md.append("| ").append(String.join(" | ", rLines)).append(" |\n");
                        minX0 = Math.min(minX0, rowBlock.getX0());
                        minY0 = Math.min(minY0, rowBlock.getY0());
                        maxX1 = Math.max(maxX1, rowBlock.getX1());
                        maxY1 = Math.max(maxY1, rowBlock.getY1());
                    }

                    double[] tableBbox = new double[]{minX0, minY0, maxX1, maxY1};
                    occupiedBboxes.add(tableBbox);

                    RawPdfTable synthesizedTable = new RawPdfTable(
                            i, tableBbox, rowBlocks.size() + 1, colCount,
                            List.of(), md.toString(), List.of()
                    );

                    String tableHeaderContext = resolveHeaderContext(standaloneText, i, tableBbox);
                    tableRegions.add(new ContentRegion(
                            pageNumber, RegionType.SIMPLE_TABLE, tableBbox, rowBlocks,
                            synthesizedTable, null, null, null, null
                    ));
                    continue;
                }
            }

            // Case B: Row-based table detection (when headers are split into separate horizontal blocks above)
            if (isGenericTabularRowBlock(lines)) {
                List<RawPdfTextBlock> rowBlocks = new ArrayList<>();
                rowBlocks.add(block);

                int expectedValues = lines.length - 1; // line 0 is entity, lines 1..N are numbers

                for (int j = i + 1; j < standaloneText.size(); j++) {
                    RawPdfTextBlock candidate = standaloneText.get(j);
                    if (consumed.contains(candidate)) break;

                    String[] rowLines = candidate.text() != null ? candidate.text().split("\n") : new String[0];
                    if (isGenericTabularRowBlock(rowLines) && Math.abs((rowLines.length - 1) - expectedValues) <= 1) {
                        rowBlocks.add(candidate);
                    } else if (isValidSectionOrTableTitle(candidate.text(), candidate.fontSize()) && j + 1 < standaloneText.size()) {
                        // Section subheader within continuous table — consume header and continue accumulating data rows
                        consumed.add(candidate);
                    } else {
                        break;
                    }
                }

                if (rowBlocks.size() >= 2) {
                    // Find header band blocks in the table header zone above data rows
                    double firstRowY0 = block.getY0();
                    List<RawPdfTextBlock> headerBandBlocks = new ArrayList<>();
                    for (int k = 0; k < i; k++) {
                        RawPdfTextBlock prev = standaloneText.get(k);
                        if (prev.getY1() <= firstRowY0 && prev.getY0() >= 135) {
                            String txt = prev.text() != null ? prev.text().trim() : "";
                            boolean isPageTitleOrBanner = txt.equalsIgnoreCase("BONDS")
                                    || txt.equalsIgnoreCase("Averages")
                                    || txt.equalsIgnoreCase("Total Returns (%)")
                                    || txt.equalsIgnoreCase("Averages Total Returns (%)");
                            boolean isFirstColCategoryLabel = prev.getX0() <= 60 && prev.getX1() <= 150
                                    && !txt.toLowerCase().contains("coupon") && !txt.toLowerCase().contains("index");

                            if (!isPageTitleOrBanner && !isFirstColCategoryLabel) {
                                headerBandBlocks.add(prev);
                            }
                        }
                    }

                    headerBandBlocks.sort(Comparator.comparingDouble(RawPdfTextBlock::getX0));
                    List<String> discoveredHeaders = discoverHeadersDynamically(headerBandBlocks, expectedValues);

                    consumed.add(block);
                    consumed.addAll(rowBlocks);
                    consumed.addAll(headerBandBlocks);

                    StringBuilder md = new StringBuilder();
                    md.append("| ").append(String.join(" | ", discoveredHeaders)).append(" |\n");
                    md.append("| ").append(String.join(" | ", Collections.nCopies(discoveredHeaders.size(), "---"))).append(" |\n");

                    double minX0 = block.getX0(), minY0 = block.getY0();
                    double maxX1 = block.getX1(), maxY1 = block.getY1();

                    for (RawPdfTextBlock rowBlock : rowBlocks) {
                        String[] rLines = rowBlock.text().split("\n");
                        md.append("| ").append(String.join(" | ", rLines)).append(" |\n");
                        minX0 = Math.min(minX0, rowBlock.getX0());
                        minY0 = Math.min(minY0, rowBlock.getY0());
                        maxX1 = Math.max(maxX1, rowBlock.getX1());
                        maxY1 = Math.max(maxY1, rowBlock.getY1());
                    }

                    double[] tableBbox = new double[]{minX0, minY0, maxX1, maxY1};
                    occupiedBboxes.add(tableBbox);

                    RawPdfTable synthesizedTable = new RawPdfTable(
                            i, tableBbox, rowBlocks.size() + 1, discoveredHeaders.size(),
                            List.of(), md.toString(), List.of()
                    );

                    tableRegions.add(new ContentRegion(
                            pageNumber, RegionType.SIMPLE_TABLE, tableBbox, rowBlocks,
                            synthesizedTable, null, null, null, null
                    ));
                }
            }
        }
        standaloneText.removeIf(consumed::contains);
        return tableRegions;
    }

    private List<String> discoverHeadersDynamically(List<RawPdfTextBlock> headerBandBlocks, int expectedValues) {
        List<String> discovered = new ArrayList<>();
        discovered.add("Security / Metric");

        if (headerBandBlocks == null || headerBandBlocks.isEmpty()) {
            for (int k = 1; k <= expectedValues; k++) {
                discovered.add("Col " + k);
            }
            return discovered;
        }

        List<RawPdfTextBlock> sorted = new ArrayList<>(headerBandBlocks);
        sorted.sort(Comparator.comparingDouble(RawPdfTextBlock::getX0));

        List<String> rawTokens = new ArrayList<>();
        for (RawPdfTextBlock hb : sorted) {
            String txt = hb.text() != null ? hb.text().trim() : "";
            String[] lines = txt.split("\n");
            for (String l : lines) {
                String trimmed = l.trim();
                if (!trimmed.isEmpty()) {
                    rawTokens.add(trimmed);
                }
            }
        }

        List<String> combined = new ArrayList<>();
        for (int idx = 0; idx < rawTokens.size(); idx++) {
            String token = rawTokens.get(idx);
            if ((token.startsWith("(") || token.equalsIgnoreCase("Worst (%)") || token.equalsIgnoreCase("($)") || token.equalsIgnoreCase("(%)"))
                    && !combined.isEmpty()) {
                int lastIdx = combined.size() - 1;
                String prev = combined.get(lastIdx);
                if (!prev.contains("(") && !prev.matches(".*\\d+.*")) {
                    combined.set(lastIdx, prev + " " + token);
                    continue;
                }
            }
            combined.add(token);
        }

        List<String> validCols = new ArrayList<>();
        for (String c : combined) {
            if (c.equalsIgnoreCase("Index") || c.equalsIgnoreCase("BONDS") || c.equalsIgnoreCase("Averages") || c.equalsIgnoreCase("Total Returns (%)")) {
                continue;
            }
            validCols.add(c);
        }

        if (!validCols.isEmpty()) {
            discovered.addAll(validCols);
        }

        while (discovered.size() < expectedValues + 1) {
            discovered.add("Col " + discovered.size());
        }
        if (discovered.size() > expectedValues + 1) {
            discovered = new ArrayList<>(discovered.subList(0, expectedValues + 1));
        }

        return discovered;
    }

    public static boolean isValidSectionOrTableTitle(String text, double fontSize) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.length() < 2 || trimmed.length() > 140) return false;

        // 1. Reject numeric-only values (e.g., "300%", "200", "8%", "4.69%", "0.94", "100.00")
        if (trimmed.matches("^\\s*\\$?\\d+(?:[.,]\\d+)?%?\\s*$")) {
            return false;
        }

        // 2. Reject series of numeric/axis ticks (e.g. "0% 50% 100% 150% 200% 250% 300% 350%")
        String[] tokens = trimmed.split("\\s+");
        int numTokens = 0;
        for (String tok : tokens) {
            if (tok.matches("^\\$?\\d+(?:[.,]\\d+)?%?$") || tok.matches("^[-—–]$")) {
                numTokens++;
            }
        }
        if (tokens.length >= 2 && numTokens >= tokens.length - 1) {
            return false;
        }

        // 3. Reject unit-only strings (e.g. "(%)", "($)", "(bps)", "(x)", "(yrs.)", "(years)")
        if (trimmed.matches("^\\s*\\((?:%|\\$|bps|x|years?|months?|yrs\\.?)\\)\\s*$")) {
            return false;
        }

        // 4. Reject generic table/column header labels and chart legend markers
        String lower = trimmed.toLowerCase();
        Set<String> genericHeaders = Set.of(
                "security", "averages", "current", "median", "total", "index", "coupon", "price",
                "higher", "lower", "high", "low", "number of holdings",
                "rating", "1-mo.", "1-mo. ago", "3-mo.", "12-mo.", "12-mo. ago", "ytd", "1y", "3y", "5y", "10y",
                "– – – = + ++", "past performance is no guarantee of future results",
                "source:", "data as of", "minimum", "maximum", "duration", "maturity",
                "coupon (%)", "price ($)", "yield to worst (%)", "duration (yrs.)", "maturity (yrs.)"
        );
        if (genericHeaders.contains(lower)) {
            return false;
        }

        // 5. Reject if multi-line block has more than 3 lines
        if (trimmed.lines().count() > 3) {
            return false;
        }

        return true;
    }

    private String resolveHeaderContext(List<RawPdfTextBlock> blocks, int tableBlockIndex, double[] tableBbox) {
        double tableY0 = tableBbox != null && tableBbox.length > 1 ? tableBbox[1] : Double.MAX_VALUE;
        double tableX0 = tableBbox != null && tableBbox.length > 0 ? tableBbox[0] : 0;
        double tableX1 = tableBbox != null && tableBbox.length > 2 ? tableBbox[2] : Double.MAX_VALUE;

        RawPdfTextBlock bestCandidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < tableBlockIndex && i < blocks.size(); i++) {
            RawPdfTextBlock b = blocks.get(i);
            String text = b.text() != null ? b.text().trim() : "";
            if (!isValidSectionOrTableTitle(text, b.fontSize())) continue;

            double bY1 = b.getY1();
            if (bY1 > tableY0 + 15) continue;
            double verticalDist = tableY0 - bY1;
            if (verticalDist > 200) continue;

            double score = 0.0;
            score += b.fontSize() * 5.0;
            if (b.isLikelyHeading()) score += 30.0;
            score += Math.max(0, 100.0 - verticalDist);

            boolean horizOverlap = (b.getX0() <= tableX1 + 20) && (b.getX1() >= tableX0 - 20);
            if (horizOverlap) score += 50.0;

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = b;
            }
        }

        return (bestCandidate != null && !bestCandidate.text().isBlank()) ? bestCandidate.text().trim() : "";
    }

    private boolean isGenericTabularRowBlock(String[] lines) {
        if (lines == null || lines.length < 4) return false;
        String entityLine = lines[0].trim();
        if (entityLine.isEmpty() || entityLine.matches("^-?\\$?\\d+[\\.,]?\\d*%?$")) return false;

        int numericCount = 0;
        for (int i = 1; i < lines.length; i++) {
            String val = lines[i].trim();
            if (val.matches("^-?\\$?\\d+[\\.,]?\\d*%?$") || val.matches("^[-—–]$") || val.equalsIgnoreCase("N/A")) {
                numericCount++;
            }
        }
        return numericCount >= lines.length - 2 && numericCount >= 3;
    }

    private boolean isTableColumnHeaderBlock(String[] lines) {
        if (lines == null || lines.length < 3) return false;
        int textCount = 0;
        int numericCount = 0;
        boolean hasKeyword = false;
        boolean hasChartPanelIndicator = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();

            if (lower.contains("market local interest rates") || lower.contains("sovereign currency yields")
                    || (lower.contains("developed market") && lower.contains("emerging market"))) {
                hasChartPanelIndicator = true;
            }

            if (line.matches("^-?\\$?\\d+[\\.,]?\\d*%?$")) {
                numericCount++;
            } else {
                textCount++;
                if (lower.contains("current") || lower.contains("ago") || lower.contains("average") ||
                    lower.contains("minimum") || lower.contains("maximum") || lower.contains("ytd") ||
                    lower.contains("1-mo") || lower.contains("3-mo") || lower.contains("yield") ||
                    lower.contains("return") || lower.contains("spread") || lower.contains("duration") ||
                    lower.contains("price") || lower.contains("coupon") || lower.contains("security") ||
                    lower.contains("metric") || lower.contains("sector") || lower.contains("category")) {
                    hasKeyword = true;
                }
            }
        }

        if (hasChartPanelIndicator) {
            return false;
        }

        return textCount >= 3 && numericCount <= 1 && (hasKeyword || textCount >= 4);
    }

    private boolean isTabularRowBlock(String[] lines, int expectedCols) {
        if (lines == null || lines.length < 3) return false;
        if (lines.length != expectedCols && lines.length != expectedCols - 1) return false;

        // Line 0 is entity (non-numeric)
        String entityLine = lines[0].trim();
        if (entityLine.isEmpty() || entityLine.matches("^-?\\$?\\d+[\\.,]?\\d*%?$")) return false;

        // Remaining lines are numbers/dashes
        int numericCount = 0;
        for (int i = 1; i < lines.length; i++) {
            String val = lines[i].trim();
            if (val.matches("^-?\\$?\\d+[\\.,]?\\d*%?$") || val.matches("^[-—–]$") || val.equalsIgnoreCase("N/A")) {
                numericCount++;
            }
        }
        return numericCount >= lines.length - 2;
    }

    private boolean isDecorative(double[] bbox, double pageHeight, int widthPx, int heightPx) {
        // Small icon / bullet
        if (widthPx > 0 && widthPx < 60 && heightPx > 0 && heightPx < 60) {
            return true;
        }
        // Header / footer line in top 8% or bottom 8%
        double y0 = bbox.length > 1 ? bbox[1] : 0.0;
        return (y0 < pageHeight * 0.08 || y0 > pageHeight * 0.92) && (widthPx < 100 || heightPx < 40);
    }

    private boolean isHeaderOrFooter(double[] bbox, double pageHeight, double fontSize) {
        double y0 = bbox.length > 1 ? bbox[1] : 0.0;
        return (y0 < pageHeight * 0.05 || y0 > pageHeight * 0.95) && fontSize <= 9.0;
    }

    private boolean intersectsAny(double[] bbox, List<double[]> others) {
        for (double[] o : others) {
            if (intersects(bbox, o)) return true;
        }
        return false;
    }

    private boolean isPageChart(RawPdfPage page, RawPdfTable table) {
        if (page == null) return false;

        // 1. Check if page has multiple non-background vector drawings (like Page 45 with 28 bar strokes)
        if (page.drawings() != null) {
            long nonBgDrawings = page.drawings().stream()
                    .filter(d -> !isPageBackgroundDrawing(d, page.width(), page.height()))
                    .count();
            if (nonBgDrawings >= 5) {
                return true;
            }
        }

        // 2. Check if page text blocks contain axis tick patterns (e.g. 0.00, 10.00, 20.00) or chart titles
        if (page.textBlocks() != null) {
            for (RawPdfTextBlock b : page.textBlocks()) {
                String text = b.text() != null ? b.text().toLowerCase() : "";
                if (text.contains("0.00") && text.contains("10.00") && text.contains("20.00")) {
                    return true;
                }
                if (text.contains("market local interest rates") || text.contains("local sovereign currency yields")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean intersects(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        return !(a[2] < b[0] || a[0] > b[2] || a[3] < b[1] || a[1] > b[3]);
    }

    private List<List<RawPdfDrawing>> clusterDrawings(List<RawPdfDrawing> drawings) {
        List<List<RawPdfDrawing>> clusters = new ArrayList<>();
        if (drawings == null || drawings.isEmpty()) return clusters;

        for (RawPdfDrawing d : drawings) {
            double[] dBbox = d.bbox() != null ? d.bbox() : new double[]{0, 0, 0, 0};
            boolean added = false;
            for (List<RawPdfDrawing> cluster : clusters) {
                double[] cBbox = computeUnionBbox(cluster);
                // If drawing is within 60 points of existing cluster bbox, merge into cluster
                if (isNear(dBbox, cBbox, 60.0)) {
                    cluster.add(d);
                    added = true;
                    break;
                }
            }
            if (!added) {
                List<RawPdfDrawing> newCluster = new ArrayList<>();
                newCluster.add(d);
                clusters.add(newCluster);
            }
        }

        // Iteratively merge clusters that are near each other
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < clusters.size(); i++) {
                for (int j = i + 1; j < clusters.size(); j++) {
                    double[] b1 = computeUnionBbox(clusters.get(i));
                    double[] b2 = computeUnionBbox(clusters.get(j));
                    if (isNear(b1, b2, 60.0)) {
                        clusters.get(i).addAll(clusters.get(j));
                        clusters.remove(j);
                        merged = true;
                        break;
                    }
                }
                if (merged) break;
            }
        }

        return clusters;
    }

    private boolean isNear(double[] b1, double[] b2, double tolerance) {
        if (b1 == null || b2 == null || b1.length < 4 || b2.length < 4) return false;
        double expandedB2MinX = b2[0] - tolerance;
        double expandedB2MinY = b2[1] - tolerance;
        double expandedB2MaxX = b2[2] + tolerance;
        double expandedB2MaxY = b2[3] + tolerance;
        return !(b1[2] < expandedB2MinX || b1[0] > expandedB2MaxX || b1[3] < expandedB2MinY || b1[1] > expandedB2MaxY);
    }

    private double[] computeUnionBbox(List<RawPdfDrawing> drawings) {
        if (drawings == null || drawings.isEmpty()) return new double[]{0, 0, 0, 0};
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
        for (RawPdfDrawing d : drawings) {
            double[] b = d.bbox() != null ? d.bbox() : new double[]{0, 0, 0, 0};
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[2]);
            maxY = Math.max(maxY, b[3]);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private double[] expandBboxToEncloseNearbyText(double[] clusterBbox, List<RawPdfTextBlock> textBlocks, List<double[]> occupiedBboxes, double pageHeight) {
        if (clusterBbox == null || textBlocks == null) return clusterBbox;
        double minX = clusterBbox[0];
        double minY = clusterBbox[1];
        double maxX = clusterBbox[2];
        double maxY = clusterBbox[3];

        for (RawPdfTextBlock b : textBlocks) {
            double[] bBbox = b.bbox();
            if (bBbox == null || bBbox.length < 4) continue;
            if (isHeaderOrFooter(bBbox, pageHeight, b.fontSize())) continue;
            if (intersectsAny(bBbox, occupiedBboxes)) continue;

            // If text block is within 40 points vertically and overlaps horizontally with the chart cluster
            boolean vertNear = (bBbox[3] >= minY - 40 && bBbox[1] <= maxY + 30);
            boolean horizOverlap = (bBbox[2] >= minX - 30 && bBbox[0] <= maxX + 30);
            if (vertNear && horizOverlap) {
                minX = Math.min(minX, bBbox[0]);
                minY = Math.min(minY, bBbox[1]);
                maxX = Math.max(maxX, bBbox[2]);
                maxY = Math.max(maxY, bBbox[3]);
            }
        }
        return new double[]{minX, minY, maxX, maxY};
    }
}
