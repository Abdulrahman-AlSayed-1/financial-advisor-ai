package com.alsayed.financial_advisor_ai.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class FindMunicipalGeneralObligationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchAllMunicipalEvidence() throws Exception {
        System.out.println("=== SEARCH FOR MUNICIPAL / GO / AAA / SIFMA ===");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, metadata->>'page_number' as page, metadata->>'chunk_type' as type,
                       metadata->>'row_entity' as row_ent, metadata->>'table_name' as tbl,
                       metadata->>'section' as sec, content
                FROM vector_store
                WHERE content ILIKE '%Municipal%'
                   OR content ILIKE '%Muni%'
                   OR content ILIKE '%General Obligation%'
                   OR content ILIKE '%SIFMA%'
                   OR metadata::text ILIKE '%Municipal%'
                   OR metadata::text ILIKE '%Muni%'
                ORDER BY (metadata->>'page_number')::int ASC
                """);

        System.out.printf("Found %d municipal-related chunks:%n", rows.size());
        for (var row : rows) {
            String content = (String) row.get("content");
            System.out.printf("Page: %s | Type: %s | RowEnt: %s | Tbl: %s | ID: %s%nContent snippet:%n%s%n----------------------------------------%n",
                    row.get("page"), row.get("type"), row.get("row_ent"), row.get("tbl"), row.get("id"),
                    content.replace("\n", " ").substring(0, Math.min(200, content.length())));
        }
    }
}
