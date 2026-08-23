package com.alsayed.financial_advisor_ai.evaluation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MultiLineMarkdownTableTest {

    @Test
    void testCleanMultiLineMarkdown() {
        String raw = """
| Managed Futures
18.37 | Multi-Strategy
3.84 | MLP
18.31 |
| --- | --- | --- |
| Multi-Strategy
6.09 | Long/Short Equity
3.55 | Commodity
11.77 |
""";

        String[] rawLines = raw.split("\n");
        System.out.println("Raw lines count: " + rawLines.length);

        // Normalize newlines inside table cells
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

        String cleanedTable = cleaned.toString();
        System.out.println("Cleaned Table:\n" + cleanedTable);
        for (String row : cleanedTable.split("\n")) {
            System.out.println("ROW: " + row);
        }
    }
}
