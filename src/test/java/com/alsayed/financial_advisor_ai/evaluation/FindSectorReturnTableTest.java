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
public class FindSectorReturnTableTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Find Sector Return Table Chunks")
    void findSectorReturnTable() throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, content, metadata FROM vector_store WHERE content ILIKE '%utilities%' AND content ILIKE '%30.8%'");

        System.out.printf("Found %d chunks with Utilities AND 30.8:%n", rows.size());
        for (Map<String, Object> r : rows) {
            String metaStr = r.get("metadata") != null ? r.get("metadata").toString() : "{}";
            Map<String, Object> meta = objectMapper.readValue(metaStr, new TypeReference<>() {});
            System.out.printf("Chunk [%s] Page: %s | Type: %s | Sec: '%s' | Tbl: '%s' | TblID: '%s' | Ent: '%s'%nContent:%n%s%n%n",
                    r.get("id"), meta.get("page_number"), meta.get("content_type"), meta.get("section"), meta.get("table_name"), meta.get("table_id"), meta.get("row_entity"),
                    r.get("content"));
        }

        List<Map<String, Object>> energyRows = jdbcTemplate.queryForList(
                "SELECT id, content, metadata FROM vector_store WHERE content ILIKE '%energy%' AND content ILIKE '%6.2%'");

        System.out.printf("Found %d chunks with Energy AND 6.2:%n", energyRows.size());
        for (Map<String, Object> r : energyRows) {
            String metaStr = r.get("metadata") != null ? r.get("metadata").toString() : "{}";
            Map<String, Object> meta = objectMapper.readValue(metaStr, new TypeReference<>() {});
            System.out.printf("Chunk [%s] Page: %s | Type: %s | Sec: '%s' | Tbl: '%s' | TblID: '%s' | Ent: '%s'%nContent:%n%s%n%n",
                    r.get("id"), meta.get("page_number"), meta.get("content_type"), meta.get("section"), meta.get("table_name"), meta.get("table_id"), meta.get("row_entity"),
                    r.get("content"));
        }
    }
}
