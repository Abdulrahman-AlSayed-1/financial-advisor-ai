package com.alsayed.financial_advisor_ai.chunking;

import com.alsayed.financial_advisor_ai.chunking.table.TableChunkPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CanonicalTableChunker.
 *
 * Tests the two-tier hierarchy:
 *   Tier 1 — Table Summary Chunk  (always created)
 *   Tier 2 — Row Data Chunks      (individual rows for small tables; groups for large tables)
 *
 * Information preservation invariant:
 *   Every value in the table must appear somewhere in the chunk list.
 */
class CanonicalTableChunkerTest {

    private TableChunkPolicy policy;
    private CanonicalTableChunker chunker;

    @BeforeEach
    void setUp() {
        policy = new TableChunkPolicy();
        chunker = new CanonicalTableChunker(policy);
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private CanonicalTable buildTable(String id, String title, int rowCount, int colCount) {
        CanonicalTable table = new CanonicalTable();
        table.setTableId(id);
        table.setTitle(title);
        table.setSection(title);
        table.setPageNumber(40);
        table.setTableType("standard");

        // Header row
        String[] headers = new String[colCount];
        for (int c = 0; c < colCount; c++) headers[c] = "Col" + c;
        table.setHeaderLevels(java.util.Collections.singletonList(headers));

        // Data rows
        List<CanonicalTable.CanonicalRow> rows = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            CanonicalTable.CanonicalRow row = new CanonicalTable.CanonicalRow();
            row.setRowIndex(r);
            row.setRowLabel("Entity" + r);
            List<CanonicalTable.CanonicalCell> cells = new ArrayList<>();
            for (int c = 0; c < colCount; c++) {
                CanonicalTable.CanonicalCell cell = new CanonicalTable.CanonicalCell();
                cell.setRowIndex(r);
                cell.setColumnIndex(c);
                cell.setValue("val_" + r + "_" + c);
                cell.setMetric("Return (%)");
                cell.setPeriod("YTD");
                cell.setUnit("%");
                cells.add(cell);
            }
            row.setCells(cells);
            rows.add(row);
        }
        table.setRows(rows);
        return table;
    }

    private List<Document> chunk(CanonicalTable table) {
        return chunker.chunk(table, "test-doc", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // ── Tests: Tier 1 — Table Summary ────────────────────────────────────────

    @Test
    void alwaysCreatesSummaryChunk() {
        CanonicalTable table = buildTable("t1", "Asset Returns", 5, 3);
        List<Document> chunks = chunk(table);

        long summaryCount = chunks.stream()
                .filter(d -> "table_summary".equals(d.getMetadata().get("chunk_type")))
                .count();
        assertEquals(1, summaryCount, "Exactly one table_summary chunk must be created");
    }

    @Test
    void summaryChunkContainsTableTitle() {
        CanonicalTable table = buildTable("t1", "U.S. Yields", 5, 2);
        List<Document> chunks = chunk(table);

        Document summary = chunks.stream()
                .filter(d -> "table_summary".equals(d.getMetadata().get("chunk_type")))
                .findFirst().orElseThrow();

        assertThat(summary.getText()).contains("U.S. Yields");
    }

    @Test
    void summaryChunkContainsAllEntityNames() {
        CanonicalTable table = buildTable("t1", "Test Table", 3, 2);
        List<Document> chunks = chunk(table);

        Document summary = chunks.stream()
                .filter(d -> "table_summary".equals(d.getMetadata().get("chunk_type")))
                .findFirst().orElseThrow();

        assertThat(summary.getText()).contains("Entity0", "Entity1", "Entity2");
    }

    // ── Tests: Tier 2a — Small Table (individual row chunks) ──────────────────

    @Test
    void smallTable_createsIndividualRowChunks() {
        policy.setSmallTableThreshold(20);
        // 5 rows <= 20 threshold = individual chunks
        CanonicalTable table = buildTable("small", "Small Table", 5, 2);
        List<Document> chunks = chunk(table);

        long rowChunks = chunks.stream()
                .filter(d -> "table".equals(d.getMetadata().get("chunk_type")))
                .count();
        assertEquals(5, rowChunks, "5-row table should produce 5 row chunks");
    }

    @Test
    void smallTable_totalChunksIsSummaryPlusRows() {
        policy.setSmallTableThreshold(20);
        CanonicalTable table = buildTable("small", "Small Table", 10, 3);
        List<Document> chunks = chunk(table);
        // 1 summary + 10 rows = 11
        assertEquals(11, chunks.size());
    }

    @Test
    void smallTable_preservesSourceDocumentId() {
        CanonicalTable table = buildTable("t1", "Test", 3, 2);
        List<Document> chunks = chunker.chunk(table, "my-document", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(chunks).allMatch(d -> "my-document".equals(d.getMetadata().get("source_document_id")));
    }

    // ── Tests: Tier 2b — Large Table (grouped row chunks) ────────────────────

    @Test
    void largeTable_createsGroupedChunks() {
        policy.setSmallTableThreshold(10);
        policy.setRowGroupSize(5);
        // 25 rows > 10 threshold = grouped
        CanonicalTable table = buildTable("large", "Large Table", 25, 3);
        List<Document> chunks = chunk(table);

        long groupChunks = chunks.stream()
                .filter(d -> "table".equals(d.getMetadata().get("chunk_type")))
                .count();
        // 25 rows / 5 per group = 5 groups
        assertEquals(5, groupChunks, "25-row table with groupSize=5 should produce 5 group chunks");
    }

    @Test
    void largeTable_noCellLevelExplosion() {
        policy.setSmallTableThreshold(10);
        policy.setRowGroupSize(5);
        // 100 rows × 10 cols = 1000 cells. Old behaviour: 1000 chunks. New: 1 summary + 20 groups = 21
        CanonicalTable table = buildTable("wide", "Wide Table", 100, 10);
        List<Document> chunks = chunk(table);

        assertThat(chunks).hasSizeLessThan(50);
    }

    @Test
    void largeTable_preservesAllRowValues() {
        // INFORMATION PRESERVATION INVARIANT
        // Every row value that was ingested must appear in at least one chunk's text
        policy.setSmallTableThreshold(5);
        policy.setRowGroupSize(3);
        CanonicalTable table = buildTable("data", "Data Table", 9, 2);
        List<Document> chunks = chunk(table);

        String allChunkText = chunks.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 2; c++) {
                String expectedValue = "val_" + r + "_" + c;
                assertThat(allChunkText)
                        .as("Value %s (row=%d, col=%d) must appear in some chunk", expectedValue, r, c)
                        .contains(expectedValue);
            }
        }
    }

    @Test
    void largeTable_groupIndexMetadataSet() {
        policy.setSmallTableThreshold(5);
        policy.setRowGroupSize(3);
        CanonicalTable table = buildTable("grouped", "Grouped Table", 9, 2);
        List<Document> chunks = chunk(table);

        List<Document> groupChunks = chunks.stream()
                .filter(d -> "table".equals(d.getMetadata().get("chunk_type")))
                .toList();

        assertThat(groupChunks).allMatch(d -> d.getMetadata().containsKey("group_index"));
    }

    // ── Tests: Metadata / Provenance ─────────────────────────────────────────

    @Test
    void allChunksHavePageNumber() {
        CanonicalTable table = buildTable("t1", "Test", 5, 2);
        List<Document> chunks = chunk(table);
        assertThat(chunks).allMatch(d -> d.getMetadata().containsKey("page_number"));
    }

    @Test
    void allChunksHaveTableId() {
        CanonicalTable table = buildTable("test-id-123", "Test", 5, 2);
        List<Document> chunks = chunk(table);
        assertThat(chunks).allMatch(d -> "test-id-123".equals(d.getMetadata().get("table_id")));
    }

    @Test
    void allChunksHaveSourcePymupdf() {
        CanonicalTable table = buildTable("t1", "Test", 5, 2);
        List<Document> chunks = chunk(table);
        assertThat(chunks).allMatch(d -> "pymupdf".equals(d.getMetadata().get("source")));
    }



    // ── Tests: Duplicate ingestion ────────────────────────────────────────────

    @Test
    void deterministicIds_sameInputSameIds() {
        CanonicalTable table = buildTable("dup-test", "Same Table", 5, 2);
        List<Document> first  = chunk(table);
        List<Document> second = chunk(table);

        List<String> firstIds  = first.stream().map(Document::getId).sorted().toList();
        List<String> secondIds = second.stream().map(Document::getId).sorted().toList();
        assertEquals(firstIds, secondIds, "Chunk IDs must be deterministic for the same input");
    }

    // ── Tests: SP500 Utilities real-data scenario ─────────────────────────────

    @Test
    void sp500UtilitiesYTDValue_isRetrievable() {
        // Simulate the exact chunk structure that should answer Q6: "30.63" for S&P 500 Utilities YTD
        CanonicalTable table = new CanonicalTable();
        table.setTableId("page_40_tbl_0");
        table.setTitle("Asset Class Return Analysis (%)");
        table.setSection("Asset Class Return Analysis");
        table.setPageNumber(40);
        table.setTableType("standard");
        table.setHeaderLevels(java.util.Collections.singletonList(new String[]{"YTD", "1 Year", "3 Year"}));

        CanonicalTable.CanonicalRow row = new CanonicalTable.CanonicalRow();
        row.setRowIndex(0);
        row.setRowLabel("S&P 500 Utilities");
        CanonicalTable.CanonicalCell cell = new CanonicalTable.CanonicalCell();
        cell.setRowIndex(0);
        cell.setColumnIndex(0);
        cell.setValue("30.63");
        cell.setMetric("Return (%)");
        cell.setPeriod("YTD");
        cell.setUnit("%");
        row.setCells(List.of(cell));
        table.setRows(List.of(row));

        List<Document> chunks = chunker.chunk(table, "article_thebeatoct2024",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        // The value 30.63 must appear in at least one chunk
        String allText = chunks.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("30.63");
        assertThat(allText).contains("S&P 500 Utilities");
    }
}
