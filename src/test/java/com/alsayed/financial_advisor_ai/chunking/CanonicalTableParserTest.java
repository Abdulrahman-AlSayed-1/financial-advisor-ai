package com.alsayed.financial_advisor_ai.chunking;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CanonicalTableParser covering various table structures.
 * 
 * These tests ensure that:
 * 1. Multi-level headers are preserved
 * 2. Row/column relationships are maintained
 * 3. Hierarchical rows are handled correctly
 * 4. Correlation matrices are parsed properly
 * 5. Metric/period/unit extraction works correctly
 */
class CanonicalTableParserTest {
    
    private final CanonicalTableParser parser = new CanonicalTableParser();
    
    @Test
    void testSimpleRowColumnTable() {
        String markdown = "| Entity | Value |\n|-------|-------|\n| A | 10 |\n| B | 20 |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_1", "Test Section", 1);
        
        assertNotNull(table);
        assertEquals(2, table.getRows().size());
        assertEquals("A", table.getRows().get(0).getRowLabel());
        assertEquals("B", table.getRows().get(1).getRowLabel());
        
        // Check that cells have proper structure
        CanonicalTable.CanonicalCell cell = table.getRows().get(0).getCells().get(0);
        assertEquals("10", cell.getValue());
        assertNotNull(cell.getColumnPath());
    }
    
    @Test
    void testMultiLevelColumnHeaders() {
        // Table with multi-level headers like the Page 40 table
        String markdown = "|          | 1-Mo. | 3-Mo. | YTD   |\n"
                        + "|----------|-------|-------|-------|\n"
                        + "| Entity A | 1.5   | 2.3   | 10.5  |\n"
                        + "| Entity B | 0.8   | 1.9   | 8.2   |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_2", "Multi-Level Test", 2);
        
        assertNotNull(table);
        assertTrue(table.getHeaderLevels().size() >= 1);
        
        // Check that period information is preserved in cells
        CanonicalTable.CanonicalRow row = table.getRows().get(0);
        assertTrue(row.getCells().size() >= 3);
        
        // At least one cell should have period information
        boolean hasPeriod = row.getCells().stream().anyMatch(c -> c.getPeriod() != null && !c.getPeriod().isEmpty());
        assertTrue(hasPeriod, "At least one cell should have period information");
    }
    
    @Test
    void testHierarchicalRows() {
        // Table with hierarchical rows (category + entity)
        String markdown = "| Category | Entity | Value |\n"
                        + "|----------|--------|-------|\n"
                        + "| Equities | S&P 500 | 100 |\n"
                        + "|          | Nasdaq  | 95  |\n"
                        + "| Bonds    | Treasury| 50  |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_3", "Hierarchical Test", 3);
        
        assertNotNull(table);
        
        // Check that category is preserved
        CanonicalTable.CanonicalRow row1 = table.getRows().get(0);
        assertEquals("Equities", row1.getCategory());
        assertEquals("S&P 500", row1.getRowLabel());
        
        CanonicalTable.CanonicalRow row2 = table.getRows().get(1);
        assertEquals("Nasdaq", row2.getRowLabel());
    }
    
    @Test
    void testCorrelationMatrix() {
        // Simplified correlation matrix
        String markdown = "|           | S&P 500 | Bonds |\n"
                        + "|-----------|---------|-------|\n"
                        + "| S&P 500   | 1.00    | 0.55  |\n"
                        + "| Bonds     | 0.55    | 1.00  |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_4", "Correlation Table", 4);
        
        assertNotNull(table);
        table.detectTableType();
        assertEquals("correlation", table.getTableType());
        
        // Check that row entities are preserved
        assertEquals("S&P 500", table.getRows().get(0).getRowLabel());
        assertEquals("Bonds", table.getRows().get(1).getRowLabel());
    }
    
    @Test
    void testFinancialReturnTable() {
        // Table like Page 40 with multiple periods
        String markdown = "|          | YTD   | 1Y    | 3Y    |\n"
                        + "|----------|-------|-------|-------|\n"
                        + "| Sector A | 30.63 | 35.20 | 25.10 |\n"
                        + "| Sector B | 30.31 | 32.15 | 28.40 |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_5", "Asset Class Return Analysis (%)", 40);
        
        assertNotNull(table);
        
        // Critical: Check that YTD period is preserved
        CanonicalTable.CanonicalRow row = table.getRows().get(0);
        Optional<CanonicalTable.CanonicalCell> ytdCell = row.getCells().stream()
            .filter(c -> "YTD".equals(c.getPeriod()))
            .findFirst();
        
        assertTrue(ytdCell.isPresent(), "YTD period should be preserved in cells");
        assertEquals("30.63", ytdCell.get().getValue());
    }
    
    @Test
    void testTableValidation() {
        // Table with inconsistent column counts
        String markdown = "| A | B | C |\n"
                        + "|---|---|---|\n"
                        + "| 1 | 2 | 3 |\n"
                        + "| 4 | 5 |";  // Missing third column
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_6", "Invalid Table", 5);
        
        List<String> errors = table.validate();
        assertFalse(errors.isEmpty(), "Validation should detect inconsistent column counts");
    }
    
    @Test
    void testTableWithPercentagesAndDecimals() {
        String markdown = "| Entity | Value (%) | Decimal |\n"
                        + "|--------|----------|---------|\n"
                        + "| A      | 25.5     | 0.255  |\n"
                        + "| B      | 10.2     | 0.102  |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_7", "Mixed Types", 6);
        
        assertNotNull(table);
        
        // Check that percentage values are preserved
        CanonicalTable.CanonicalCell percentageCell = table.getRows().get(0).getCells().get(0);
        assertEquals("25.5", percentageCell.getValue());
        
        // Check that decimal values are preserved
        CanonicalTable.CanonicalCell decimalCell = table.getRows().get(0).getCells().get(1);
        assertEquals("0.255", decimalCell.getValue());
    }
    
    @Test
    void testLargeTableRequiringMultipleChunks() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Entity |");
        for (int i = 0; i < 15; i++) {
            sb.append(" Col").append(i).append(" |");
        }
        sb.append("\n|--------|");
        for (int i = 0; i < 15; i++) {
            sb.append("-------|");
        }
        
        for (int row = 0; row < 20; row++) {
            sb.append("\n| Row").append(row).append(" |");
            for (int i = 0; i < 15; i++) {
                sb.append(" ").append(row * i).append(" |");
            }
        }
        
        CanonicalTable table = parser.parseMarkdownTable(sb.toString(), "test_table_8", "Large Table", 7);
        
        assertNotNull(table);
        assertEquals(20, table.getRows().size());
        
        // Each row should have the correct number of cells
        for (CanonicalTable.CanonicalRow row : table.getRows()) {
            assertEquals(15, row.getCells().size());
        }
    }
    
    @Test
    void testTableWithMergedCells() {
        // Table with empty cells (merged)
        String markdown = "| Category | Entity | Value1 | Value2 |\n"
                        + "|----------|--------|--------|--------|\n"
                        + "| Equities |        | 100    | 105    |\n"
                        + "|          | S&P 500| 95     | 100    |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_9", "Merged Cells", 8);
        
        assertNotNull(table);
        // Should handle empty cells gracefully
        assertTrue(table.getRows().size() >= 1);
    }
    
    @Test
    void testTableWhereSameEntityAppearsInMultiplePeriods() {
        String markdown = "| Entity | 2023 | 2022 | 2021 |\n"
                        + "|--------|------|------|------|\n"
                        + "| A      | 10   | 8    | 12   |\n"
                        + "| B      | 15   | 14   | 16   |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_10", "Multi-Period", 9);
        
        assertNotNull(table);
        
        // Check that different periods are preserved for the same entity
        CanonicalTable.CanonicalRow row = table.getRows().get(0);
        List<String> periods = new ArrayList<>();
        for (CanonicalTable.CanonicalCell cell : row.getCells()) {
            if (cell.getPeriod() != null && !cell.getPeriod().isEmpty()) {
                periods.add(cell.getPeriod());
            }
        }
        
        // Should have at least some period information
        assertFalse(periods.isEmpty(), "Period information should be preserved");
    }
    
    @Test
    void testTableWithSimilarValuesOnlyDistinguishedByColumn() {
        // Critical test for Q6/Q7 scenario
        String markdown = "| Entity | YTD   | 1Y    |\n"
                        + "|--------|-------|-------|\n"
                        + "| A      | 30.63 | 35.20 |\n"
                        + "| B      | 30.31 | 32.15 |\n"
                        + "| C      | 28.50 | 30.10 |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_11", "Similar Values", 10);
        
        assertNotNull(table);
        
        // Critical: Each cell must have column context to distinguish similar values
        CanonicalTable.CanonicalRow rowA = table.getRows().get(0);
        CanonicalTable.CanonicalCell ytdCell = rowA.getCells().get(0);
        CanonicalTable.CanonicalCell oneYearCell = rowA.getCells().get(1);
        
        // Both cells should have different periods
        assertNotEquals(ytdCell.getPeriod(), oneYearCell.getPeriod(), 
            "Cells in different columns should have different period contexts");
        
        // Values should be preserved
        assertEquals("30.63", ytdCell.getValue());
        assertEquals("35.20", oneYearCell.getValue());
    }
    
    @Test
    void testColumnPathGeneration() {
        String markdown = "| Metric | 1-Year | 3-Year |\n"
                        + "| Return | YTD    | 1-Year |\n"
                        + "|--------|--------|--------|\n"
                        + "| A      | 10     | 15     |";
        
        CanonicalTable table = parser.parseMarkdownTable(markdown, "test_table_12", "Column Path Test", 11);
        
        assertNotNull(table);
        
        // Check that column path combines header levels
        String columnPath = table.getColumnPath(1);
        assertNotNull(columnPath);
        // Should contain information from both header levels
        assertTrue(columnPath.contains("1-Year") || columnPath.contains("YTD"));
    }
    

}
