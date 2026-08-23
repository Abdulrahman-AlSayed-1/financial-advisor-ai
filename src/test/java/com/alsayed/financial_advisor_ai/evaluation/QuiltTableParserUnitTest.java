package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.chunking.CanonicalTable;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class QuiltTableParserUnitTest {

    @Test
    void testQuiltTableDecomposition() {
        CanonicalTableParser parser = new CanonicalTableParser();

        String rawQuiltMarkdown = """
| Managed Futures 18.37 | Multi-Strategy 3.84 | MLP 18.31 | Long/Short Equity 13.41 | Fixed Income Arbitrage 1.10 | Long/Short Equity 12.17 | Convertible Arbitrage 10.25 | MLP 40.17 | MLP 30.92 | MLP 26.56 | MLP 18.90 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Multi-Strategy 6.09 | Long/Short Equity 3.55 | Commodity 11.77 | Currency 11.54 | Global Macro -0.11 | Global Macro 10.38 | Long/Short Equity 7.86 | Commodity 27.11 | Managed Futures 19.12 | Long/Short Equity 10.93 | Long/Short Equity 12.27 |
| MLP 4.80 | Convertible Arbitrage 0.81 | Multi-Strategy 4.41 | Multi-Strategy 6.83 | Convertible Arbitrage -2.26 | Event Driven 8.22 | Global Macro 6.53 | Global Macro 9.60 | Global Macro 15.89 | Currency 8.44 | Event Driven 7.94 |
""";

        CanonicalTable table = parser.parseMarkdownTable(rawQuiltMarkdown, "page_47_tbl_0", "Asset Class Return Analysis (%)", "Asset Class Return Analysis (%)", 47, null);

        assertNotNull(table);
        System.out.println("Parsed Table Rows: " + table.getRows().size());
        for (var row : table.getRows()) {
            System.out.printf("Row: '%-25s' | Cells: %d\n", row.getRowLabel(), row.getCells().size());
            for (var cell : row.getCells()) {
                System.out.printf("   [Period: %-10s | Val: %s]\n", cell.getPeriod(), cell.getValue());
            }
        }
    }
}
