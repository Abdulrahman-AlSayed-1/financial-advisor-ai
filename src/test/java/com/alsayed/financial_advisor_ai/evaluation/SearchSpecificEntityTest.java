package com.alsayed.financial_advisor_ai.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class SearchSpecificEntityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Search for German Bund and Factors in Database")
    void searchEntities() throws Exception {
        System.out.println("==================================================================================");
        System.out.println("               SEARCH FOR 'BUND' / 'GERMAN' IN DATABASE");
        System.out.println("==================================================================================");

        List<Map<String, Object>> bundRows = jdbcTemplate.queryForList(
                "SELECT id, content, metadata FROM vector_store WHERE content ILIKE '%bund%' OR content ILIKE '%german%' OR metadata::text ILIKE '%bund%' OR metadata::text ILIKE '%german%'");

        System.out.printf("Found %d rows for 'bund'/'german':%n", bundRows.size());
        for (Map<String, Object> r : bundRows) {
            String metaStr = r.get("metadata") != null ? r.get("metadata").toString() : "{}";
            Map<String, Object> meta = objectMapper.readValue(metaStr, new TypeReference<>() {});
            System.out.printf("Chunk [%s] Page: %s | Type: %s | Sec: '%s' | Tbl: '%s' | Ent: '%s'%nContent: %s%n%n",
                    r.get("id"), meta.get("page_number"), meta.get("content_type"), meta.get("section"), meta.get("table_name"), meta.get("row_entity"),
                    ((String) r.get("content")).replace("\n", " "));
        }

        System.out.println("==================================================================================");
        System.out.println("               SEARCH FOR 'MOMENTUM' / 'VALUE' FACTORS ON PAGE 38/39/40");
        System.out.println("==================================================================================");

        List<Map<String, Object>> factorRows = jdbcTemplate.queryForList(
                "SELECT id, content, metadata FROM vector_store WHERE content ILIKE '%momentum%' OR metadata::text ILIKE '%momentum%'");

        System.out.printf("Found %d rows for 'momentum':%n", factorRows.size());
        for (Map<String, Object> r : factorRows) {
            String metaStr = r.get("metadata") != null ? r.get("metadata").toString() : "{}";
            Map<String, Object> meta = objectMapper.readValue(metaStr, new TypeReference<>() {});
            System.out.printf("Chunk [%s] Page: %s | Type: %s | Sec: '%s' | Tbl: '%s' | Ent: '%s'%nContent: %s%n%n",
                    r.get("id"), meta.get("page_number"), meta.get("content_type"), meta.get("section"), meta.get("table_name"), meta.get("row_entity"),
                    ((String) r.get("content")).replace("\n", " "));
        }
    }
}
