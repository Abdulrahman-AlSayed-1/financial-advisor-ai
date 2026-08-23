package com.alsayed.financial_advisor_ai.chunking;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.*;

/**
 * Canonical intermediate representation for tables.
 * 
 * This class preserves the complete structural information of a table
 * including multi-level headers, row hierarchies, and cell relationships.
 * It serves as the foundation for generating semantically rich chunks
 * that maintain row/column/metric/period/value relationships.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CanonicalTable {
    private String tableId;
    private String title;
    private String section;
    private int pageNumber;
    private List<String[]> headerLevels;  // Multi-level headers (e.g., [metric level, period level])
    private List<CanonicalRow> rows;
    private String tableType;  // "standard", "correlation", "hierarchical", etc.
    
    /**
     * Represents a single row in the canonical table.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CanonicalRow {
        private int rowIndex;
        private String rowLabel;  // The primary entity name (e.g., "S&P 500 Utilities")
        private String category;  // Parent category if hierarchical (e.g., "Sectors", "Equities")
        private List<CanonicalCell> cells;
    }
    
    /**
     * Represents a single cell with complete semantic context.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CanonicalCell {
        private int rowIndex;
        private int columnIndex;
        private String value;
        private String columnPath;  // Full hierarchical column path (e.g., "Return (%) | YTD")
        private String metric;      // Extracted metric name
        private String period;      // Extracted period (e.g., "YTD", "1-Year", "5 Years ended...")
        private String unit;       // Extracted unit (e.g., "%", "bps")
        
        /**
         * For correlation matrices, both row and column entities matter.
         */
        private String rowEntity;
        private String columnEntity;
    }
    
    /**
     * Validate the structural integrity of the table.
     * Returns a list of validation errors (empty if valid).
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        
        if (headerLevels == null || headerLevels.isEmpty()) {
            errors.add("No header levels defined");
            return errors;
        }
        
        int expectedColumns = headerLevels.get(0).length;
        
        // Check header level consistency
        for (int i = 0; i < headerLevels.size(); i++) {
            String[] level = headerLevels.get(i);
            if (level.length != expectedColumns) {
                errors.add(String.format("Header level %d has %d columns, expected %d", 
                    i, level.length, expectedColumns));
            }
        }
        
        // Check row consistency
        if (rows != null) {
            for (CanonicalRow row : rows) {
                if (row.getCells() != null && row.getCells().size() != expectedColumns) {
                    errors.add(String.format("Row %d has %d cells, expected %d columns", 
                        row.getRowIndex(), row.getCells().size(), expectedColumns));
                }
            }
        }
        
        return errors;
    }
    
    /**
     * Get the combined column path for a specific column index.
     * Joins all header levels for that column into a hierarchical path.
     */
    public String getColumnPath(int columnIndex) {
        if (headerLevels == null || columnIndex < 0) {
            return "";
        }
        
        StringBuilder path = new StringBuilder();
        for (int level = 0; level < headerLevels.size(); level++) {
            String[] headers = headerLevels.get(level);
            if (columnIndex < headers.length) {
                String header = headers[columnIndex].trim();
                if (!header.isEmpty()) {
                    if (path.length() > 0) {
                        path.append(" | ");
                    }
                    path.append(header);
                }
            }
        }
        return path.toString();
    }
    
    /**
     * Detect the table type based on structural characteristics.
     */
    public void detectTableType() {
        if (title == null) {
            this.tableType = "standard";
            return;
        }
        
        String lowerTitle = title.toLowerCase();
        
        // Correlation matrix detection
        if (lowerTitle.contains("correlation")) {
            this.tableType = "correlation";
            return;
        }
        
        // Check for hierarchical structure (categories in first column)
        boolean hasCategories = false;
        if (rows != null) {
            for (CanonicalRow row : rows) {
                if (row.getCategory() != null && !row.getCategory().isEmpty()) {
                    hasCategories = true;
                    break;
                }
            }
        }
        
        if (hasCategories) {
            this.tableType = "hierarchical";
        } else {
            this.tableType = "standard";
        }
    }
}
