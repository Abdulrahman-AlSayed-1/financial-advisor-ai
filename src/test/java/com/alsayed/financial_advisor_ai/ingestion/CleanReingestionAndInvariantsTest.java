package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.service.IngestionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=false",
        "app.ingestion.force=false"
})
public class CleanReingestionAndInvariantsTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Corpus Invariants Verification After Clean Re-Ingestion")
    void testCorpusInvariantsAfterCleanReingestion() throws Exception {
        // Step 1: Trigger clean re-ingestion
        ingestionService.run();

        // Step 2: Query all chunks from PostgreSQL
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, content, metadata FROM vector_store");
        System.out.println("Total chunks in vector store: " + rows.size());
        assertThat(rows).isNotEmpty();

        int tableChunks = 0;
        int figureChunks = 0;
        int paragraphChunks = 0;

        List<String> violations = new ArrayList<>();

        Set<String> genericColumnNames = Set.of(
                "security", "averages", "current", "median", "total", "index", "coupon", "price",
                "rating", "1-mo.", "1-mo. ago", "3-mo.", "12-mo.", "12-mo. ago", "ytd", "1y", "3y", "5y", "10y",
                "– – – = + ++", "past performance is no guarantee of future results",
                "source:", "data as of", "minimum", "maximum", "duration", "maturity",
                "coupon (%)", "price ($)", "yield to worst (%)", "duration (yrs.)", "maturity (yrs.)"
        );

        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String content = row.get("content") != null ? row.get("content").toString() : "";
            String metadataJson = row.get("metadata") != null ? row.get("metadata").toString() : "";

            Map<String, Object> meta = parseMetadata(metadataJson);
            String chunkType = (String) meta.getOrDefault("chunk_type", "");
            String section = (String) meta.getOrDefault("section", "");
            String tableName = (String) meta.getOrDefault("table_name", "");
            Object pageObj = meta.get("page_number");
            int page = pageObj instanceof Number ? ((Number) pageObj).intValue() : -1;

            if ("table".equals(chunkType) || "table_summary".equals(chunkType)) {
                tableChunks++;

                // Invariant 1: Table section and table_name must not be numeric
                if (section.matches("^\\s*\\$?\\d+(?:[.,]\\d+)?%?\\s*$")) {
                    violations.add(String.format("Chunk %s (Page %d) has numeric section: '%s'", id, page, section));
                }
                if (tableName.matches("^\\s*\\$?\\d+(?:[.,]\\d+)?%?\\s*$")) {
                    violations.add(String.format("Chunk %s (Page %d) has numeric table_name: '%s'", id, page, tableName));
                }

                // Invariant 2: Table section and table_name must not be unit-only
                if (section.matches("^\\s*\\((?:%|\\$|bps|x|years?|months?)\\)\\s*$")) {
                    violations.add(String.format("Chunk %s (Page %d) has unit-only section: '%s'", id, page, section));
                }
                if (tableName.matches("^\\s*\\((?:%|\\$|bps|x|years?|months?)\\)\\s*$")) {
                    violations.add(String.format("Chunk %s (Page %d) has unit-only table_name: '%s'", id, page, tableName));
                }

                // Invariant 3: Table section must not be a generic column header
                if (genericColumnNames.contains(section.trim().toLowerCase())) {
                    violations.add(String.format("Chunk %s (Page %d) has generic column header as section: '%s'", id, page, section));
                }

                // Invariant 4: Anonymous entities must have parent_category or non-empty table_name
                String rowEntity = (String) meta.get("row_entity");
                if (rowEntity != null && Set.of("5y", "10y", "30y", "aaa", "aa", "a", "bbb").contains(rowEntity.trim().toLowerCase())) {
                    String parentCat = (String) meta.get("parent_category");
                    if ((parentCat == null || parentCat.isBlank()) && (tableName == null || tableName.isBlank())) {
                        violations.add(String.format("Chunk %s (Page %d) has anonymous entity '%s' with no parent category or table name", id, page, rowEntity));
                    }
                }
            } else if ("figure_datapoint".equals(chunkType) || "figure_summary".equals(chunkType) || "figure".equals(chunkType)) {
                figureChunks++;

                // Invariant 5: Figure section bleed check (Pages 47-52 must NOT have Page 45 title)
                if (page >= 47 && page <= 52) {
                    if (section.toLowerCase().contains("developed market local interest rates")) {
                        violations.add(String.format("Chunk %s (Page %d) has bleeding Page 45 section: '%s'", id, page, section));
                    }
                }
            } else {
                paragraphChunks++;
            }
        }

        System.out.printf("Corpus Statistics: Total=%d (Tables=%d, Figures=%d, Paragraphs=%d)%n",
                rows.size(), tableChunks, figureChunks, paragraphChunks);

        if (!violations.isEmpty()) {
            System.err.println("Invariant Violations (" + violations.size() + "):");
            violations.forEach(v -> System.err.println(" - " + v));
        }

        assertThat(violations).isEmpty();
        System.out.println("ALL CORPUS STRUCTURAL INVARIANTS PASSED (0 violations)!");
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
