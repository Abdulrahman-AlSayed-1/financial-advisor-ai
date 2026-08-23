package com.alsayed.financial_advisor_ai.evaluation;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TenorExtractionPrototypeTest {

    private static final Pattern TENOR_PATTERN = Pattern.compile(
            "\\b(\\d+)\\s*(?:-|\\s*)?(yr|year|years|mo|month|months|y|m)\\b", Pattern.CASE_INSENSITIVE);

    public static Set<String> extractTenors(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> tenors = new LinkedHashSet<>();
        Matcher matcher = TENOR_PATTERN.matcher(text);
        while (matcher.find()) {
            String num = matcher.group(1);
            String unit = matcher.group(2).toLowerCase();
            if (unit.startsWith("y")) {
                tenors.add(num + "y");
            } else if (unit.startsWith("m")) {
                tenors.add(num + "m");
            }
        }
        return tenors;
    }

    @Test
    void testTenorExtractionOnQueries() {
        List<String> testQueries = List.of(
                "What was the yield to worst for 10-Year AAA General Obligation Municipal bonds as of September 30, 2024?",
                "What was the 10-year Treasury yield and 1-Year Total Return as of September 30, 2024?",
                "What was the 2-Year U.S. Treasury yield and its 1-month total return as of September 30, 2024?",
                "What was the 10-Year German Bund yield as of September 30, 2024?",
                "What was the 1-Year spot return of the British Pound and Euro against the USD as of September 30, 2024?",
                "What is the team's view on U.S. economic growth?",
                "What was headline CPI in August 2024?"
        );

        for (String q : testQueries) {
            System.out.printf("Query: %s%n  -> Extracted Tenors: %s%n", q, extractTenors(q));
        }
    }

    @Test
    void testTenorMatchingOnEntities() {
        String query = "What was the 10-year Treasury yield?";
        Set<String> queryTenors = extractTenors(query);

        List<String> entities = List.of(
                "10-Yr Treasury",
                "2-yr. Treasury",
                "30-Yr Treasury",
                "10Y Treasury",
                "U.S. Treasury Yields",
                "10-Yr AAA GO",
                "5-Yr AAA GO"
        );

        for (String ent : entities) {
            Set<String> entTenors = extractTenors(ent);
            boolean matches = !queryTenors.isEmpty() && !entTenors.isEmpty() && !Collections.disjoint(queryTenors, entTenors);
            boolean conflicts = !queryTenors.isEmpty() && !entTenors.isEmpty() && Collections.disjoint(queryTenors, entTenors);
            System.out.printf("Entity: '%-20s' | Tenors: %-10s | Match: %-5s | Conflict: %-5s%n",
                    ent, entTenors, matches, conflicts);
        }
    }
}
