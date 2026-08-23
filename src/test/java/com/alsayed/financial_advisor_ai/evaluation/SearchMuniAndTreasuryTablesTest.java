package com.alsayed.financial_advisor_ai.evaluation;

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
public class SearchMuniAndTreasuryTablesTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void searchMuni() {
        System.out.println("=== SEARCH MUNI & AAA GO ===");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, metadata->>'page_number' as page, metadata->>'row_entity' as row_ent, metadata->>'table_name' as tbl, content FROM vector_store WHERE content ILIKE '%General Obligation%' OR content ILIKE '%AAA GO%' OR content ILIKE '%Muni%' LIMIT 10");
        for (var row : rows) {
            System.out.printf("ID: %s | Page: %s | RowEnt: %s | Tbl: %s%nContent snippet:%n%s%n------------------------%n",
                    row.get("id"), row.get("page"), row.get("row_ent"), row.get("tbl"),
                    row.get("content").toString().replace("\n", " ").substring(0, Math.min(250, row.get("content").toString().length())));
        }
    }

    @Test
    void search10YTreasury() {
        System.out.println("=== SEARCH 10-YEAR TREASURY ===");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, metadata->>'page_number' as page, metadata->>'row_entity' as row_ent, metadata->>'table_name' as tbl, content FROM vector_store WHERE (content ILIKE '%10-year%' OR content ILIKE '%10-yr%' OR content ILIKE '%10y%') AND (content ILIKE '%treasury%' OR metadata->>'row_entity' ILIKE '%treasury%') LIMIT 10");
        for (var row : rows) {
            System.out.printf("ID: %s | Page: %s | RowEnt: %s | Tbl: %s%nContent snippet:%n%s%n------------------------%n",
                    row.get("id"), row.get("page"), row.get("row_ent"), row.get("tbl"),
                    row.get("content").toString().replace("\n", " ").substring(0, Math.min(250, row.get("content").toString().length())));
        }
    }
}
