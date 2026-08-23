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
public class FindNumbersTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Find 30.8 and 6.2 in database")
    void findNumbers() throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, content, metadata FROM vector_store WHERE content ILIKE '%30.8%' OR content ILIKE '%6.2%'");

        System.out.printf("Found %d chunks with 30.8 or 6.2:%n", rows.size());
        for (Map<String, Object> r : rows) {
            String metaStr = r.get("metadata") != null ? r.get("metadata").toString() : "{}";
            Map<String, Object> meta = objectMapper.readValue(metaStr, new TypeReference<>() {});
            System.out.printf("Chunk [%s] Page: %s | Type: %s | Sec: '%s' | Tbl: '%s' | Ent: '%s'%nContent: %s%n%n",
                    r.get("id"), meta.get("page_number"), meta.get("content_type"), meta.get("section"), meta.get("table_name"), meta.get("row_entity"),
                    ((String) r.get("content")).replace("\n", " "));
        }
    }
}
