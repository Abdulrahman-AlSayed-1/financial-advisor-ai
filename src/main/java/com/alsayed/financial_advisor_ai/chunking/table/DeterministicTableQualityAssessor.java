package com.alsayed.financial_advisor_ai.chunking.table;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfTable;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic quality assessment for PyMuPDF table extraction.
 * Replaces hardcoded/assumed confidence scores with observable structural properties.
 */
@Slf4j
@Component
public class DeterministicTableQualityAssessor {

    public record TableAssessment(
        boolean isAcceptableDeterministic,
        double score,
        List<String> rejectionReasons
    ) {}

    /**
     * Assess whether a table extracted by PyMuPDF is sufficiently well-structured
     * for deterministic parsing, or whether it requires Vision understanding.
     */
    public TableAssessment assess(RawPdfTable table, List<RawPdfTextBlock> pageText) {
        List<String> reasons = new ArrayList<>();

        if (table == null) {
            return new TableAssessment(false, 0.0, List.of("Table is null"));
        }

        // 1. Grid Dimension Check
        if (table.rowCount() < 2 || table.colCount() < 2) {
            reasons.add("Degenerate dimensions: " + table.rowCount() + "x" + table.colCount());
        }

        // 2. Row/Column Uniformity
        if (table.rawGrid() == null || table.rawGrid().isEmpty()) {
            reasons.add("Raw grid is empty");
        } else {
            for (int r = 0; r < table.rawGrid().size(); r++) {
                List<String> row = table.rawGrid().get(r);
                if (row == null || row.size() != table.colCount()) {
                    int sz = row != null ? row.size() : 0;
                    reasons.add("Row " + r + " size mismatch: " + sz + " vs expected " + table.colCount());
                }
            }

            // 3. Null / Empty Cell Ratio
            long totalCells = (long) table.rowCount() * table.colCount();
            long populatedCells = table.rawGrid().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(c -> c != null && !c.isBlank())
                .count();

            double fillRatio = (double) populatedCells / Math.max(totalCells, 1);
            if (fillRatio < 0.35) {
                reasons.add("Sparse table: fill ratio " + String.format("%.2f", fillRatio) + " < 0.35");
            }

            // 4. Header Detection Validity
            List<String> firstRow = table.rawGrid().get(0);
            if (firstRow != null) {
                long emptyHeaders = firstRow.stream().filter(h -> h == null || h.isBlank()).count();
                if (emptyHeaders > firstRow.size() / 2) {
                    reasons.add("Invalid header: > 50% empty column names");
                }
                long numericOrNullHeaders = firstRow.stream().filter(h -> {
                    if (h == null || h.isBlank()) return true;
                    String clean = h.replaceAll("[%,$]", "").trim();
                    return clean.matches("^-?\\d+(\\.\\d+)?.*");
                }).count();
                if (numericOrNullHeaders == firstRow.size() || (firstRow.size() > 1 && numericOrNullHeaders >= firstRow.size() && (firstRow.get(0) == null || firstRow.get(0).isBlank()))) {
                    reasons.add("Invalid header: column names are numeric data values or nulls");
                }
            }

            // 5. Multi-line / Merged Cell indicator
            long noneValueCount = table.rawGrid().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::isNull)
                .count();
            if (noneValueCount > totalCells * 0.40) {
                reasons.add("High null cell count (" + noneValueCount + ") indicates unhandled merged cells");
            }
        }

        boolean acceptable = reasons.isEmpty();
        double score = acceptable ? 1.0 : Math.max(0.0, 1.0 - (reasons.size() * 0.25));

        if (!acceptable) {
            log.debug("Table index {} rejected from deterministic path: {}", table.tableIndex(), reasons);
        }

        return new TableAssessment(acceptable, score, reasons);
    }
}
