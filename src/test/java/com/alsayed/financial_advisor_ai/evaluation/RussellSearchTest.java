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
public class RussellSearchTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void searchRussell() {
        String sql = "SELECT id, metadata->>'page_number' as page, metadata->>'title' as title, content FROM vector_store WHERE content ILIKE '%russell 2000%' LIMIT 5";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (var r : rows) {
            System.out.printf("[Page %s] %s\nContent: %s\n\n", r.get("page"), r.get("title"), r.get("content"));
        }
    }
}
