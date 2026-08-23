package com.alsayed.financial_advisor_ai.evaluation;

import org.junit.jupiter.api.Test;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

public class QuiltTableDecompositionTest {

    @Test
    void testQuiltCellRegex() {
        Pattern pattern = Pattern.compile("^(.*?)(?:\\s+|\\n)+(-?\\d+(?:\\.\\d+)?%?)$");

        String[] testCells = {
                "MLP\n18.90",
                "MLP 18.90",
                "Managed Futures\n18.37",
                "Fixed Income\nArbitrage\n1.10",
                "Equity Market\nNeutral\n-1.19",
                "Long/Short Equity 12.27",
                "Commodity -17.01"
        };

        for (String cell : testCells) {
            String normalized = cell.replace("\n", " ").trim();
            Matcher m = pattern.matcher(normalized);
            assertTrue(m.matches(), "Cell should match quilt cell pattern: " + cell);
            String entity = m.group(1).trim();
            String val = m.group(2).trim();
            System.out.printf("Cell: '%-30s' -> Entity: '%-25s' | Value: '%s'\n", normalized, entity, val);
        }
    }
}
