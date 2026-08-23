package com.alsayed.financial_advisor_ai.chunking.table;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicTableQualityAssessorTest {

    private final DeterministicTableQualityAssessor assessor = new DeterministicTableQualityAssessor();

    @Test
    void testAssessWellFormedTable() {
        RawPdfTable wellFormed = new RawPdfTable(
                0,
                new double[]{50, 100, 500, 300},
                3,
                3,
                List.of(
                        List.of("Entity", "Current Yield", "1Y Return"),
                        List.of("US Loans", "9.54%", "10.30%"),
                        List.of("High Yield", "7.20%", "15.35%")
                ),
                "| Entity | Current Yield | 1Y Return |\n|---|---|---|\n| US Loans | 9.54% | 10.30% |\n| High Yield | 7.20% | 15.35% |",
                List.of()
        );

        var result = assessor.assess(wellFormed, List.of());
        assertTrue(result.isAcceptableDeterministic(), "Well-formed table should be acceptable");
        assertEquals(1.0, result.score(), 0.01);
        assertTrue(result.rejectionReasons().isEmpty());
    }

    @Test
    void testAssessSparseOrDegenerateTable() {
        RawPdfTable sparse = new RawPdfTable(
                1,
                new double[]{50, 100, 500, 300},
                4,
                3,
                java.util.Arrays.asList(
                        java.util.Arrays.asList("", null, ""),
                        java.util.Arrays.asList(null, "Value", null),
                        java.util.Arrays.asList("", "", null),
                        java.util.Arrays.asList(null, null, null)
                ),
                "",
                List.of()
        );

        var result = assessor.assess(sparse, List.of());
        assertFalse(result.isAcceptableDeterministic(), "Sparse/degenerate table should be rejected from deterministic path");
        assertFalse(result.rejectionReasons().isEmpty());
    }
}
