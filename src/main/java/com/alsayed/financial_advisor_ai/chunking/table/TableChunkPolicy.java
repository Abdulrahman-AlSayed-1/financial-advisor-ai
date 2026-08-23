package com.alsayed.financial_advisor_ai.chunking.table;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config-driven policy for the table chunking hierarchy.
 *
 * <h3>Two-Tier Model</h3>
 * <pre>
 * CanonicalTable
 * ├── Tier 1: Table Summary Chunk  [ALWAYS]
 * │            Contains: title, columns, entity list, row/col counts, page, section
 * └── Tier 2: Row Group Chunks     [DATA PRESERVATION]
 *              Small tables (rows <= smallTableThreshold): one chunk per row
 *              Large tables: rows are grouped into bounded chunks of rowGroupSize
 * </pre>
 *
 * <p>Row groups preserve EVERY table value.  No data is silently discarded.
 * A table with 100 rows and groupSize=15 produces 1 summary + 7 groups = 8 chunks
 * instead of potentially hundreds of per-cell chunks.
 */

@ConfigurationProperties(prefix = "app.chunking.table")
public class TableChunkPolicy {

    /**
     * Number of rows per group chunk for large tables.
     * Each group chunk contains all cells for those rows in structured text.
     */
    private int rowGroupSize = 15;

    /**
     * Tables with at most this many rows will use individual row chunks
     * instead of groups (more precise for small tables).
     */
    private int smallTableThreshold = 20;

    /**
     * Soft character budget per chunk.
     * If a row group would exceed this, it is split into smaller sub-groups.
     * This prevents extremely wide tables (many columns × row-group-size) from
     * creating over-sized embedding inputs.
     */
    private int maxCharsPerChunk = 1200;

    /** Always emit a table-level summary chunk. */
    private boolean alwaysCreateSummary = true;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public int getRowGroupSize() { return rowGroupSize; }
    public void setRowGroupSize(int rowGroupSize) { this.rowGroupSize = rowGroupSize; }

    public int getSmallTableThreshold() { return smallTableThreshold; }
    public void setSmallTableThreshold(int smallTableThreshold) { this.smallTableThreshold = smallTableThreshold; }

    public int getMaxCharsPerChunk() { return maxCharsPerChunk; }
    public void setMaxCharsPerChunk(int maxCharsPerChunk) { this.maxCharsPerChunk = maxCharsPerChunk; }

    public boolean isAlwaysCreateSummary() { return alwaysCreateSummary; }
    public void setAlwaysCreateSummary(boolean alwaysCreateSummary) { this.alwaysCreateSummary = alwaysCreateSummary; }

    // ── Policy decisions ──────────────────────────────────────────────────────

    /**
     * Returns true when the table is small enough to use individual row chunks.
     */
    public boolean isSmallTable(int rowCount) {
        return rowCount <= smallTableThreshold;
    }

    /**
     * Compute the actual group size for a given table, respecting the char budget.
     * The result is always >= 1.
     *
     * @param rowCount        total number of data rows
     * @param estimatedRowLen average character length of a rendered row
     * @return number of rows to include per group chunk
     */
    public int effectiveGroupSize(int rowCount, int estimatedRowLen) {
        if (estimatedRowLen <= 0) return rowGroupSize;
        // How many rows fit within the char budget?
        int byBudget = Math.max(1, maxCharsPerChunk / estimatedRowLen);
        return Math.min(rowGroupSize, byBudget);
    }
}
