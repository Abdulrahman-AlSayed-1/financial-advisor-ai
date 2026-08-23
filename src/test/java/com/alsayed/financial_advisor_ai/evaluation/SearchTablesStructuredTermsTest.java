package com.alsayed.financial_advisor_ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

public class SearchTablesStructuredTermsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Search tables_structured.json for terms")
    void searchTerms() throws Exception {
        File f1 = new File("src/main/resources/docs/tables_structured.json");
        JsonNode root = objectMapper.readTree(f1);

        String[] terms = {"cpi", "2.5", "4.75", "5.00", "momentum", "value", "utilities", "30.8", "6.2", "30.63"};

        for (String term : terms) {
            System.out.printf("%n=== SEARCHING FOR '%s' in tables_structured.json ===%n", term);
            int count = 0;
            for (int i = 0; i < root.size(); i++) {
                JsonNode tbl = root.get(i);
                String jsonStr = tbl.toString().toLowerCase();
                if (jsonStr.contains(term.toLowerCase())) {
                    count++;
                    System.out.printf("  Found in Table #%d (page %s)%n", i, tbl.get("page"));
                }
            }
            System.out.printf("Total matches for '%s': %d%n", term, count);
        }
    }
}
