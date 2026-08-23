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
public class FindExactFactsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void find2Point80() {
        System.out.println("=== SEARCH FOR 2.80% or 2.8% or Municipal AAA ===");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, metadata->>'page_number' as page, metadata->>'row_entity' as row_ent, metadata->>'table_name' as tbl, content FROM vector_store WHERE content ILIKE '%2.80%' OR content ILIKE '%2.81%' OR content ILIKE '%2.82%' OR content ILIKE '%2.83%' OR content ILIKE '%2.8%'");
        for (var row : rows) {
            System.out.printf("ID: %s | Page: %s | RowEnt: %s | Tbl: %s%nContent snippet:%n%s%n------------------------%n",
                    row.get("id"), row.get("page"), row.get("row_ent"), row.get("tbl"),
                    row.get("content").toString().replace("\n", " ").substring(0, Math.min(250, row.get("content").toString().length())));
        }
    }

    @Test
    void find3Point81() {
        System.out.println("=== SEARCH FOR 3.81% (10-Yr Treasury Yield) ===");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, metadata->>'page_number' as page, metadata->>'row_entity' as row_ent, metadata->>'table_name' as tbl, content FROM vector_store WHERE content ILIKE '%3.81%'");
        for (var row : rows) {
            System.out.printf("ID: %s | Page: %s | RowEnt: %s | Tbl: %s%nContent snippet:%n%s%n------------------------%n",
                    row.get("id"), row.get("page"), row.get("row_ent"), row.get("tbl"),
                    row.get("content").toString().replace("\n", " ").substring(0, Math.min(250, row.get("content").toString().length())));
        }
    }
}
