package com.alsayed.financial_advisor_ai.chunking;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Generic table parser that converts PyMuPDF markdown or structured table data into CanonicalTable representation.
 * 
 * This parser handles:
 * - Multi-level headers (preserves hierarchical column structure)
 * - Hierarchical rows (preserves parent-child relationships)
 * - Correlation matrices (preserves row/column entity pairs)
 * - Standard financial tables (preserves metric/period/unit relationships)
 * 
 * The output CanonicalTable maintains complete structural integrity for downstream chunking.
 */
@Slf4j
@Component
public class CanonicalTableParser {
    
    /**
     * Parse a markdown table string directly into a CanonicalTable.
     */
    public CanonicalTable parseMarkdownTable(String markdownText, String tableId, String section, int pageNumber) {
        return parseMarkdownTable(markdownText, tableId, section, section, pageNumber, List.of());
    }

    /**
     * Parse a markdown table string with spatially associated text blocks into a unified CanonicalTable.
     */
    public CanonicalTable parseMarkdownTable(String markdownText, String tableId, String section, int pageNumber, List<RawPdfTextBlock> associatedTextBlocks) {
        return parseMarkdownTable(markdownText, tableId, section, section, pageNumber, associatedTextBlocks);
    }

    /**
     * Parse a markdown table string with distinct tableTitle and section into a unified CanonicalTable.
     */
    public CanonicalTable parseMarkdownTable(String markdownText, String tableId, String tableTitle, String section, int pageNumber, List<RawPdfTextBlock> associatedTextBlocks) {
        CanonicalTable table = new CanonicalTable();
        table.setTableId(tableId);
        table.setTitle(tableTitle != null && !tableTitle.isBlank() ? tableTitle : (section != null ? section : ""));
        table.setSection(section != null && !section.isBlank() ? section : (tableTitle != null ? tableTitle : ""));
        table.setPageNumber(pageNumber);
        parseFromMarkdown(table, markdownText);

        if (associatedTextBlocks != null && !associatedTextBlocks.isEmpty()) {
            appendAssociatedTextRows(table, associatedTextBlocks);
        }

        table.detectTableType();
        return table;
    }

    private void appendAssociatedTextRows(CanonicalTable table, List<RawPdfTextBlock> associatedTextBlocks) {
        List<String[]> headerLevels = table.getHeaderLevels();
        if (headerLevels == null || headerLevels.isEmpty()) return;

        String[] headers = headerLevels.get(headerLevels.size() - 1);
        int colCount = headers.length;
        int dataColStart = (colCount > 1 && (headers[0].equalsIgnoreCase("Entity") || headers[0].equalsIgnoreCase("Name") || headers[0].equalsIgnoreCase("Category") || headers[0].equalsIgnoreCase("Security / Metric") || headers[0].equalsIgnoreCase("Security") || headers[0].isEmpty())) ? 1 : 0;

        List<CanonicalTable.CanonicalRow> existingRows = table.getRows();
        if (existingRows == null) {
            existingRows = new ArrayList<>();
            table.setRows(existingRows);
        }

        Set<String> existingEntities = existingRows.stream()
                .map(CanonicalTable.CanonicalRow::getRowLabel)
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .map(String::trim)
                .collect(Collectors.toSet());

        for (RawPdfTextBlock block : associatedTextBlocks) {
            if (block.text() == null || block.text().isBlank()) continue;
            String[] lines = block.text().split("\n");

            String currentEntity = null;
            List<String> currentValues = new ArrayList<>();

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                boolean isNumeric = line.matches("^-?\\$?\\d+[\\.,]?\\d*%?$") || line.matches("^[-—–]$");
                if (isNumeric) {
                    currentValues.add(line);
                } else {
                    if (currentEntity != null && !currentValues.isEmpty()) {
                        addSynthesizedRow(table, existingRows, existingEntities, currentEntity, currentValues, headers, dataColStart);
                        currentValues.clear();
                    }
                    currentEntity = line;
                }
            }

            if (currentEntity != null && !currentValues.isEmpty()) {
                addSynthesizedRow(table, existingRows, existingEntities, currentEntity, currentValues, headers, dataColStart);
            }
        }
    }

    private void addSynthesizedRow(CanonicalTable table, List<CanonicalTable.CanonicalRow> existingRows,
                                  Set<String> existingEntities, String entity, List<String> values,
                                  String[] headers, int dataColStart) {
        String normalizedEntity = entity.toLowerCase().trim();
        if (existingEntities.contains(normalizedEntity)) {
            return;
        }
        existingEntities.add(normalizedEntity);

        CanonicalTable.CanonicalRow row = new CanonicalTable.CanonicalRow();
        row.setRowIndex(existingRows.size());
        row.setRowLabel(entity);

        List<CanonicalTable.CanonicalCell> cells = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            int headerIdx = dataColStart + i;
            String colName = (headerIdx < headers.length) ? headers[headerIdx] : "Col" + i;
            String val = values.get(i);

            TableDimensions dims = extractTableDimensions(colName);
            String period = (dims.period != null && !dims.period.isEmpty()) ? dims.period : colName;
            String unit = (dims.unit != null && !dims.unit.isEmpty()) ? dims.unit : "%";
            String columnPath = colName;

            CanonicalTable.CanonicalCell cell = new CanonicalTable.CanonicalCell();
            cell.setRowIndex(row.getRowIndex());
            cell.setColumnIndex(i);
            cell.setValue(val);
            cell.setColumnPath(columnPath);
            cell.setMetric(table.getSection());
            cell.setPeriod(period);
            cell.setUnit(unit);
            cell.setRowEntity(entity);
            cells.add(cell);
        }

        row.setCells(cells);
        existingRows.add(row);
    }


    /**
     * Parse table from markdown text when structured metadata is not available.
     */
    private void parseFromMarkdown(CanonicalTable table, String markdownText) {
        String normalized = normalizeMultiLineMarkdown(markdownText);
        String[] lines = normalized.split("\n");
        if (lines.length < 2) {
            log.warn("Not enough lines for a valid markdown table");
            return;
        }
        
        // Parse header levels
        List<String[]> headerLevels = detectMultiLevelHeadersFromMarkdown(lines, table.getSection());
        
        // Find data start index (after header separator line)
        int dataStartIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.matches("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?$") || line.contains("---")) {
                dataStartIndex = i + 1;
                break;
            }
        }

        // Check for Quilt / Periodic Return Table (where cells contain [Entity Name, Value] arranged across period columns)
        if (isQuiltTable(lines, dataStartIndex)) {
            parseQuiltTable(table, lines, dataStartIndex);
            return;
        }

        // If the first row is actually data (>50% numeric) and has ~11-13 columns (standard return analysis table)
        // or ~4 columns (standard category flow/rate table: Entity, 1-Mo., 3-Mo., 12-Mo.),
        // infer the standard period headers and start data from row 0.
        String[] firstRow = parseMarkdownRow(lines[0]);
        if (!looksLikeHeaders(firstRow)) {
            if (firstRow.length >= 11) {
                String[] standardPeriods = new String[]{"Entity", "1-Mo.", "3-Mo.", "YTD", "1Y", "3Y", "5Y", "10Y", "2023", "2022", "2021", "2020", "2019"};
                String[] inferred = new String[firstRow.length];
                for (int p = 0; p < firstRow.length; p++) {
                    inferred[p] = (p < standardPeriods.length) ? standardPeriods[p] : "Col" + p;
                }
                headerLevels = new ArrayList<>();
                headerLevels.add(inferred);
                dataStartIndex = 0;
            } else if (firstRow.length == 4) {
                String[] standardFlowPeriods = new String[]{"Category", "1 Mo.", "3 Mo.", "12 Mo."};
                headerLevels = new ArrayList<>();
                headerLevels.add(standardFlowPeriods);
                dataStartIndex = 0;
            }
        }

        if (dataStartIndex == 0 && headerLevels.isEmpty()) {
            dataStartIndex = 2; // fallback
        }
        table.setHeaderLevels(headerLevels);
        
        // Parse data rows
        List<CanonicalTable.CanonicalRow> canonicalRows = new ArrayList<>();
        for (int i = dataStartIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            String[] cells = parseMarkdownRow(line);
            if (cells.length == 0) continue;
            if (cells[0].trim().matches("^:?-+:?$") || cells[0].trim().contains("---")) continue;
            
            CanonicalTable.CanonicalRow canonicalRow = parseRow(
                canonicalRows.size(), cells, headerLevels, table.getSection());
            canonicalRows.add(canonicalRow);
        }
        table.setRows(canonicalRows);
    }

    private String normalizeMultiLineMarkdown(String markdownText) {
        if (markdownText == null || markdownText.isBlank()) return "";
        String[] rawLines = markdownText.split("\n");
        StringBuilder cleaned = new StringBuilder();
        for (String l : rawLines) {
            String trimmed = l.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("|") && cleaned.length() > 0 && !cleaned.toString().endsWith("\n")) {
                cleaned.append("\n");
            } else if (!trimmed.startsWith("|") && cleaned.length() > 0) {
                cleaned.append(" ");
            }
            cleaned.append(trimmed);
        }
        return cleaned.toString();
    }

    private static final java.util.regex.Pattern QUILT_CELL_PATTERN =
            java.util.regex.Pattern.compile("^(.*?)(?:\\s+|\\n)+(-?\\d+(?:\\.\\d+)?%?)$");

    private boolean isQuiltTable(String[] lines, int dataStartIndex) {
        int quiltCellCount = 0;
        int totalCellCount = 0;
        int checkedRows = 0;

        for (int i = dataStartIndex; i < lines.length && checkedRows < 10; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.matches("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?$") || line.contains("---")) continue;
            String[] cells = parseMarkdownRow(line);
            if (cells.length < 5) continue;
            checkedRows++;
            for (String c : cells) {
                String val = c.replace("\n", " ").trim();
                if (val.isEmpty()) continue;
                totalCellCount++;
                if (QUILT_CELL_PATTERN.matcher(val).matches()) {
                    quiltCellCount++;
                }
            }
        }
        return totalCellCount > 0 && ((double) quiltCellCount / totalCellCount) >= 0.50;
    }

    private void parseQuiltTable(CanonicalTable table, String[] lines, int dataStartIndex) {
        String[] defaultPeriods = new String[]{"2014", "2015", "2016", "2017", "2018", "2019", "2020", "2021", "2022", "2023", "YTD 2024"};

        // Find column count from first data row
        int colCount = 11;
        for (int i = dataStartIndex; i < lines.length; i++) {
            String[] cells = parseMarkdownRow(lines[i]);
            if (cells.length > 0 && !cells[0].contains("---")) {
                colCount = cells.length;
                break;
            }
        }

        String[] periodHeaders = new String[colCount];
        for (int c = 0; c < colCount; c++) {
            periodHeaders[c] = (c < defaultPeriods.length) ? defaultPeriods[c] : "Col" + c;
        }

        List<String[]> headerLevels = new ArrayList<>();
        headerLevels.add(periodHeaders);
        table.setHeaderLevels(headerLevels);

        Map<String, List<CanonicalTable.CanonicalCell>> entityCells = new LinkedHashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.matches("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?$") || line.contains("---")) continue;

            String[] cells = parseMarkdownRow(line);
            for (int col = 0; col < cells.length && col < colCount; col++) {
                String cellText = cells[col].replace("\n", " ").trim();
                if (cellText.isEmpty()) continue;

                var matcher = QUILT_CELL_PATTERN.matcher(cellText);
                if (matcher.matches()) {
                    String entity = matcher.group(1).trim();
                    String val = matcher.group(2).trim();
                    String period = periodHeaders[col];

                    CanonicalTable.CanonicalCell cell = new CanonicalTable.CanonicalCell();
                    cell.setValue(val);
                    cell.setColumnPath(period);
                    cell.setMetric("Total Return");
                    cell.setPeriod(period);
                    cell.setUnit("%");
                    cell.setRowEntity(entity);
                    cell.setColumnIndex(col);

                    entityCells.computeIfAbsent(entity, k -> new ArrayList<>()).add(cell);
                }
            }
        }

        List<CanonicalTable.CanonicalRow> rows = new ArrayList<>();
        int rIdx = 0;
        for (var entry : entityCells.entrySet()) {
            CanonicalTable.CanonicalRow row = new CanonicalTable.CanonicalRow();
            row.setRowIndex(rIdx++);
            row.setRowLabel(entry.getKey());
            row.setCells(entry.getValue());
            rows.add(row);
        }
        table.setRows(rows);
    }
    
    /**
     * Detect multi-level headers from markdown text.
     * This handles tables like:
     * | Metric | 1-Year | 3-Year |
     * | Return | YTD    | 1-Year |
     * 
     * Also handles horizontal metric layouts:
     * | | Coupon (%) | Price ($) | Yield (%) | ... | 1-Mo. | 3-Mo. | YTD | ... |
     */
    private void detectMultiLevelHeaders(CanonicalTable table, String markdownText) {
        String[] lines = markdownText.split("\n");
        List<String[]> detectedLevels = detectMultiLevelHeadersFromMarkdown(lines, table.getSection());
        
        // Check for horizontal metric layout (metrics in row 1, periods in same row)
        if (detectedLevels.size() > 0) {
            String[] firstHeader = detectedLevels.get(0);
            if (hasHorizontalMetricLayout(firstHeader)) {
                // Split the header row into two levels: metrics and periods
                List<String[]> horizontalLevels = splitHorizontalMetrics(firstHeader);
                if (horizontalLevels.size() > 1) {
                    log.info("Detected horizontal metric layout for table {}", table.getTableId());
                    table.setHeaderLevels(horizontalLevels);
                    return;
                }
            }
        }
        
        // If we inferred a parent metric level, use it
        if (detectedLevels.size() > 1) {
            log.info("Detected {} header levels for table {}", detectedLevels.size(), table.getTableId());
            table.setHeaderLevels(detectedLevels);
        }
    }
    
    /**
     * Check if the header row has a horizontal metric layout
     * (metric names followed by period names in the same row).
     * This is more conservative to avoid false positives with multi-level headers.
     */
    private boolean hasHorizontalMetricLayout(String[] headers) {
        int metricCount = 0;
        int periodCount = 0;
        
        String[] knownPeriods = {"1-Mo.", "3-Mo.", "YTD", "1Y", "3Y", "5Y", "10Y", "12-Mo."};
        
        for (String header : headers) {
            String trimmed = header.trim();
            
            // Check if it's a period
            boolean isPeriod = false;
            for (String period : knownPeriods) {
                if (trimmed.equals(period)) {
                    isPeriod = true;
                    break;
                }
            }
            
            if (isPeriod) {
                periodCount++;
            } else if (!trimmed.isEmpty() && !trimmed.equals("Averages") && !isYearColumn(trimmed)) {
                // Only count as metric if it's not empty, not "Averages", and not a year
                // This helps distinguish true metric names from empty cells or categories
                metricCount++;
            }
        }
        
        log.debug("Horizontal layout check: metricCount={}, periodCount={}", metricCount, periodCount);
        
        // More conservative: need at least 3 metrics and 3 periods to be considered horizontal layout
        // This avoids false positives with tables that have a few period columns
        return metricCount >= 3 && periodCount >= 3;
    }
    
    /**
     * Check if a data row has a horizontal metric layout (for CCC Index type tables)
     */
    private boolean hasHorizontalMetricLayoutInDataRow(String[] dataRow) {
        int metricCount = 0;
        int periodCount = 0;
        
        String[] knownPeriods = {"1-Mo.", "3-Mo.", "YTD", "1Y", "3Y", "5Y", "10Y", "12-Mo."};
        
        for (String cell : dataRow) {
            String trimmed = cell.trim();
            
            // Check if it's a period
            boolean isPeriod = false;
            for (String period : knownPeriods) {
                if (trimmed.equals(period)) {
                    isPeriod = true;
                    break;
                }
            }
            
            if (isPeriod) {
                periodCount++;
            } else if (!trimmed.isEmpty() && !trimmed.equals("Averages") && !isYearColumn(trimmed)) {
                // Only count as metric if it's not empty, not "Averages", and not a year
                metricCount++;
            }
        }
        
        log.debug("Horizontal layout check in data row: metricCount={}, periodCount={}", metricCount, periodCount);
        
        // Need at least 3 metrics and 3 periods to be considered horizontal layout
        return metricCount >= 3 && periodCount >= 3;
    }
    
    /**
     * Split a horizontal header row into two levels: metrics and periods.
     */
    private List<String[]> splitHorizontalMetrics(String[] headers) {
        List<String[]> levels = new ArrayList<>();
        
        String[] metrics = new String[headers.length];
        String[] periods = new String[headers.length];
        
        String[] knownPeriods = {"1-Mo.", "3-Mo.", "YTD", "1Y", "3Y", "5Y", "10Y", "12-Mo."};
        
        // Track the last non-empty metric to use for period columns
        String lastMetric = "";
        
        // Detect if we're in a period block (consecutive period columns)
        boolean inPeriodBlock = false;
        
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i].trim();
            
            boolean isPeriod = false;
            for (String period : knownPeriods) {
                if (header.equals(period)) {
                    isPeriod = true;
                    break;
                }
            }
            
            if (isPeriod) {
                if (!inPeriodBlock) {
                    // Start of a new period block
                    inPeriodBlock = true;
                }
                periods[i] = header;
            } else if (header.isEmpty() || header.equals("Averages")) {
                metrics[i] = "";
                periods[i] = "";
                inPeriodBlock = false; // End of period block
            } else {
                metrics[i] = header;
                periods[i] = "";
                lastMetric = header; // Update last metric
                inPeriodBlock = false; // End of period block
            }
        }
        
        // Second pass: assign metrics to period columns
        // If we have a period block with 3+ consecutive periods, use "Total Return"
        for (int i = 0; i < headers.length; i++) {
            if (!periods[i].isEmpty()) {
                // Count how many consecutive periods from this position
                int consecutivePeriods = 0;
                for (int j = i; j < headers.length; j++) {
                    if (!periods[j].isEmpty()) {
                        consecutivePeriods++;
                    } else {
                        break;
                    }
                }
                
                // If we have 3+ consecutive periods, use "Total Return"
                if (consecutivePeriods >= 3) {
                    metrics[i] = "Total Return";
                } else {
                    metrics[i] = lastMetric;
                }
            }
        }
        
        // Third pass: ensure all period columns in a block have the same metric
        // Find the first period in each block and propagate its metric
        for (int i = 0; i < headers.length; i++) {
            if (!periods[i].isEmpty()) {
                String blockMetric = metrics[i];
                // Propagate this metric to all consecutive periods in this block
                for (int j = i + 1; j < headers.length; j++) {
                    if (!periods[j].isEmpty()) {
                        metrics[j] = blockMetric;
                    } else {
                        break;
                    }
                }
            }
        }
        
        levels.add(metrics);
        levels.add(periods);
        
        return levels;
    }
    
    /**
     * Detect multi-level headers from markdown lines.
     * Enhanced to detect metric → period hierarchies where a parent metric spans multiple period columns.
     */
    private List<String[]> detectMultiLevelHeadersFromMarkdown(String[] lines, String section) {
        List<String[]> headerLevels = new ArrayList<>();
        
        if (lines.length < 3) {
            return headerLevels;
        }
        
        // First line is always a header level
        String[] firstHeaders = parseMarkdownRow(lines[0]);
        if (firstHeaders.length == 0) {
            return headerLevels;
        }
        
        // Check for horizontal metric layout FIRST (before multi-level inference)
        if (hasHorizontalMetricLayout(firstHeaders)) {
            // This will be handled by the horizontal layout logic in detectMultiLevelHeaders
            // Just return the single level for now
            headerLevels.add(firstHeaders);
            return headerLevels;
        }
        
        // Check if we need to infer a parent metric level for period columns
        // Pattern: first row has empty/mixed cells, second row has periods
        if (lines.length > 2) {
            String[] potentialHeaders = parseMarkdownRow(lines[1]);
            if (potentialHeaders.length > 0 && looksLikeHeaders(potentialHeaders)) {
                headerLevels.add(firstHeaders);
                headerLevels.add(potentialHeaders);
                
                // Check third line for additional header level
                if (lines.length > 4) {
                    String[] potentialHeaders3 = parseMarkdownRow(lines[3]);
                    if (potentialHeaders3.length > 0 && looksLikeHeaders(potentialHeaders3)) {
                        headerLevels.add(potentialHeaders3);
                    }
                }
            } else {
                // Second line is not headers, check if first row has period columns that need a parent metric
                if (hasPeriodColumns(firstHeaders)) {
                    // Infer a parent metric level from the section title
                    String[] parentMetricLevel = new String[firstHeaders.length];
                    String parentMetric = inferParentMetricFromSection(section);
                    for (int i = 0; i < parentMetricLevel.length; i++) {
                        if (isPeriodColumn(firstHeaders[i])) {
                            parentMetricLevel[i] = parentMetric;
                        } else {
                            parentMetricLevel[i] = firstHeaders[i]; // Keep non-period headers as-is
                        }
                    }
                    // Insert parent metric level before the period level
                    headerLevels.add(parentMetricLevel);
                    headerLevels.add(firstHeaders);
                } else {
                    // No period columns, just use the first headers
                    headerLevels.add(firstHeaders);
                }
            }
        } else {
            // Not enough lines, just use the first headers
            headerLevels.add(firstHeaders);
        }
        
        return headerLevels;
    }
    
    /**
     * Check if the headers contain period columns (YTD, 1Y, 3Y, etc.)
     * More specific to avoid false positives with metrics like "Average", "Minimum", "Maximum"
     */
    private boolean hasPeriodColumns(String[] headers) {
        String[] periods = {"1-Mo.", "3-Mo.", "YTD", "1Y", "3Y", "5Y", "10Y", "12-Mo.", "2023", "2022", "2021", "2020", "2019"};
        int periodCount = 0;
        for (String header : headers) {
            for (String period : periods) {
                if (header.trim().equals(period)) {
                    periodCount++;
                    break;
                }
            }
        }
        return periodCount >= 3; // At least 3 period columns indicates a period hierarchy
    }
    
    /**
     * Check if a header cell is a period column.
     * Only true period columns (time-based), not metrics like "Average", "Minimum", "Maximum"
     */
    private boolean isPeriodColumn(String header) {
        String[] periods = {"1-Mo.", "3-Mo.", "YTD", "1Y", "3Y", "5Y", "10Y", "12-Mo.", "2023", "2022", "2021", "2020", "2019"};
        String normalizedHeader = header.trim().replace(" ", ""); // Normalize "3 - Mo." to "3-Mo."
        for (String period : periods) {
            if (normalizedHeader.equals(period) || header.trim().equals(period)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a header cell is a year column (2023, 2022, etc.).
     */
    private boolean isYearColumn(String header) {
        String[] years = {"2023", "2022", "2021", "2020", "2019", "2018", "2017", "2016", "2015"};
        for (String year : years) {
            if (header.trim().equals(year)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Infer parent metric name from section title when a metric → period hierarchy is detected.
     */
    private String inferParentMetricFromSection(String section) {
        if (section == null || section.isEmpty()) {
            return "Return (%)";
        }
        
        // Extract metric from section title
        // Pattern: "Asset Class Return Analysis (%)" -> "Return (%)"
        // Pattern: "Key Rates (%)" -> "Key Rates"
        // Pattern: "Corporate Bond Market Update" -> "Return (%)"
        
        if (section.contains("Return")) {
            return "Return (%)";
        } else if (section.contains("Rates")) {
            return "Key Rates";
        } else if (section.contains("Correlation")) {
            return "Return Correlation";
        } else {
            return "Return (%)"; // Default for financial tables
        }
    }
    
    /**
     * Check if a row looks like headers (not data).
     * Headers contain text, data contains numbers.
     */
    private boolean looksLikeHeaders(String[] cells) {
        if (cells == null || cells.length == 0) return false;
        int numericCount = 0;
        int separatorCount = 0;
        int textCount = 0;
        for (String cell : cells) {
            String trimmed = cell.trim();
            if (trimmed.isEmpty() || trimmed.matches("^:?-+:?$") || trimmed.contains("---")) {
                separatorCount++;
            } else if (isYearColumn(trimmed) || trimmed.matches("^(?:19|20)\\d{2}$")) {
                textCount++;
            } else if (trimmed.matches("^-?\\$?[\\d,]+(?:\\.\\d+)?%?$") || trimmed.matches("^[-—–]$") || trimmed.equalsIgnoreCase("N/A")) {
                numericCount++;
            } else {
                textCount++;
            }
        }
        if (separatorCount >= cells.length / 2) {
            return false;
        }
        return numericCount < cells.length / 2 && textCount > 0;
    }
    
    /**
     * Parse a single row into CanonicalRow with CanonicalCells.
     */
    private CanonicalTable.CanonicalRow parseRow(int rowIndex, String[] cells, 
                                                  List<String[]> headerLevels, String section) {
        CanonicalTable.CanonicalRow row = new CanonicalTable.CanonicalRow();
        row.setRowIndex(rowIndex);
        
        // Extract row label and category
        String rowLabel = cells[0].trim();
        String category = "";
        int valueStartIndex = 1;
        
        // Handle hierarchical rows (category in first column, entity in second)
        if (rowLabel.isEmpty() && cells.length > 1) {
            rowLabel = cells[1].trim();
            valueStartIndex = 2;
        } else if (!rowLabel.isEmpty() && cells.length > 1) {
            // Check if first cell is a category
            String potentialCategory = rowLabel;
            String potentialEntity = cells[1].trim();
            if (!potentialEntity.isEmpty() && isCategoryName(potentialCategory) && !potentialEntity.matches("^-?\\d+\\.?\\d*%?$")) {
                category = potentialCategory;
                rowLabel = potentialEntity;
                valueStartIndex = 2;
            }
        }
        
        row.setRowLabel(rowLabel);
        row.setCategory(category);
        
        // Parse cells
        List<CanonicalTable.CanonicalCell> canonicalCells = new ArrayList<>();
        for (int colIndex = valueStartIndex; colIndex < cells.length; colIndex++) {
            String value = cells[colIndex].trim();
            if (value.isEmpty()) continue;
            
            // Get column path from headers - preserve full path for correlation matrices
            String columnPath = "";
            String mostSpecificHeader = "";
            if (!headerLevels.isEmpty() && colIndex < headerLevels.get(0).length) {
                StringBuilder path = new StringBuilder();
                // Build full column path for correlation matrices
                for (int level = 0; level < headerLevels.size(); level++) {
                    String[] headers = headerLevels.get(level);
                    if (colIndex < headers.length) {
                        String header = headers[colIndex].trim();
                        if (!header.isEmpty() && !header.matches("^-+$")) {
                            if (path.length() > 0) {
                                path.append(" | ");
                            }
                            path.append(header);
                        }
                    }
                }
                columnPath = path.toString();
                
                // Also track the most specific header for period detection
                for (int level = headerLevels.size() - 1; level >= 0; level--) {
                    String[] headers = headerLevels.get(level);
                    if (colIndex < headers.length) {
                        String header = headers[colIndex].trim();
                        if (!header.isEmpty() && !header.matches("^-+$")) {
                            mostSpecificHeader = header;
                            break;
                        }
                    }
                }
            }
            
            // Extract semantic dimensions
            TableDimensions dimensions = extractTableDimensions(columnPath);
            
            // Special handling for tables with period columns but no explicit parent metric
            // If the column path contains only periods (YTD, 1Y, etc.) without a metric, infer from section
            if (dimensions.metric.isEmpty() && !dimensions.period.isEmpty()) {
                // Check if this looks like a period-only column (common in financial return tables)
                if (isPeriodColumn(mostSpecificHeader) || isYearColumn(mostSpecificHeader)) {
                    dimensions.metric = inferParentMetricFromSection(section);
                }
            }
            
            CanonicalTable.CanonicalCell cell = new CanonicalTable.CanonicalCell();
            cell.setRowIndex(rowIndex);
            cell.setColumnIndex(colIndex);
            cell.setValue(value);
            cell.setColumnPath(columnPath);
            cell.setMetric(dimensions.metric.isEmpty() ? extractMetricFromSection(section) : dimensions.metric);
            cell.setPeriod(dimensions.period);
            cell.setUnit(dimensions.unit);
            cell.setRowEntity(rowLabel);
            
            canonicalCells.add(cell);
        }
        
        row.setCells(canonicalCells);
        return row;
    }
    
    /**
     * Parse a markdown table row into cells.
     */
    private String[] parseMarkdownRow(String row) {
        row = row.trim();
        if (row.startsWith("|")) {
            row = row.substring(1);
        }
        if (row.endsWith("|")) {
            row = row.substring(0, row.length() - 1);
        }
        
        String[] cells = row.split("\\|");
        String[] trimmed = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            trimmed[i] = cells[i].trim();
        }
        return trimmed;
    }
    
    /**
     * Extract metric, period, and unit from a column header.
     */
    private TableDimensions extractTableDimensions(String header) {
        TableDimensions dimensions = new TableDimensions();
        
        // Extract unit (typically in parentheses)
        java.util.regex.Pattern unitPattern = java.util.regex.Pattern.compile("\\(([^)]+)\\)$");
        java.util.regex.Matcher unitMatcher = unitPattern.matcher(header);
        if (unitMatcher.find()) {
            dimensions.unit = unitMatcher.group(1);
            header = header.substring(0, unitMatcher.start()).trim();
        }
        
        // Split by " | " to handle multi-level headers
        String[] headerParts = header.split("\\s*\\|\\s*");
        
        // Find the rightmost period (most specific period)
        String[] periods = {"12-Mo. Ago", "1-Mo. Ago", "12-Mo.", "1-Mo.", "3-Mo.", "1-Year", "1Y", "3-Year", "3Y", 
                          "5-Year", "5Y", "10-Year", "10Y", "YTD", "Current", "Average", "Minimum", "Maximum", 
                          "Median", "2023", "2022", "2021", "2020", "2019"};
        
        for (String period : periods) {
            for (int i = headerParts.length - 1; i >= 0; i--) {
                if (headerParts[i].contains(period)) {
                    dimensions.period = period;
                    break;
                }
            }
            if (!dimensions.period.isEmpty()) break;
        }
        
        // Check if all parts are periods - if so, treat entire path as periods, no metric
        boolean allPeriods = true;
        for (String part : headerParts) {
            if (!isPeriodColumn(part) && !isYearColumn(part) && !part.isEmpty()) {
                allPeriods = false;
                break;
            }
        }
        
        if (allPeriods) {
            // All parts are periods, so metric should be inferred from section
            dimensions.metric = "";
        } else {
            // Extract metric from non-period parts
            StringBuilder metricBuilder = new StringBuilder();
            for (String part : headerParts) {
                if (!isPeriodColumn(part) && !isYearColumn(part) && !part.isEmpty()) {
                    if (metricBuilder.length() > 0) {
                        metricBuilder.append(" | ");
                    }
                    metricBuilder.append(part);
                }
            }
            dimensions.metric = metricBuilder.toString().trim();
        }
        
        return dimensions;
    }
    
    /**
     * Extract metric from section/table title.
     */
    private String extractMetricFromSection(String section) {
        if (section == null || section.isEmpty()) {
            return "";
        }
        
        String title = section.trim();
        
        if (title.contains("Return")) {
            if (title.contains("(%)")) {
                return "Return (%)";
            }
            return "Return";
        }
        
        if (title.contains("Yield")) {
            if (title.contains("(%)")) {
                return "Yield (%)";
            }
            return "Yield";
        }
        
        if (title.contains("Spread")) {
            if (title.contains("(bps)")) {
                return "Spread (bps)";
            }
            return "Spread";
        }
        
        if (title.contains("Correlation")) {
            return "Correlation";
        }
        
        return title;
    }
    
    /**
     * Check if a string looks like a category name.
     */
    private boolean isCategoryName(String text) {
        return text.equals("Sectors") || text.equals("Equities") || text.equals("Bonds") || 
               text.equals("Alternatives") || text.equals("Transition");
    }
    
    /**
     * Internal class for extracted table dimensions.
     */
    private static class TableDimensions {
        String metric = "";
        String period = "";
        String unit = "";
    }
}
