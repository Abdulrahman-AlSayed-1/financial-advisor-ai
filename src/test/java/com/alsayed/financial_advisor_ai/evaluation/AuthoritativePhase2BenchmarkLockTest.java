package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class AuthoritativePhase2BenchmarkLockTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record QueryResult(
            String id,
            int targetPage,
            String category,
            String question,
            String goldEvidenceKeyword,
            int rank,
            boolean hitAt1,
            boolean hitAt3,
            boolean hitAt5,
            boolean hitAt10,
            double reciprocalRank,
            String topRetrievedId,
            String topRetrievedSnippet
    ) {}

    @Test
    @DisplayName("Execute Authoritative Phase 2 Benchmark Integrity Lock")
    void executeAuthoritativeLock() throws Exception {
        System.out.println("==================================================================================");
        System.out.println("            PHASE 2 AUTHORITATIVE BENCHMARK INTEGRITY LOCK");
        System.out.println("==================================================================================");

        // ── 1. Corpus Structural Invariant Audit ──
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, content, metadata FROM vector_store");
        int totalChunks = rows.size();

        int numericSectionViolations = 0;
        int unitSectionViolations = 0;
        int genericHeaderViolations = 0;
        int bleedFigureViolations = 0;
        int anonymousEntityViolations = 0;
        Set<String> genericColumnNames = Set.of(
                "security", "rating", "index", "description", "period", "metric",
                "asset class", "asset_class", "value", "return", "spread", "yield"
        );

        for (Map<String, Object> row : rows) {
            String metadataStr = row.get("metadata") != null ? row.get("metadata").toString() : "{}";
            try {
                Map<String, Object> meta = objectMapper.readValue(metadataStr, new TypeReference<>() {});
                String section = (String) meta.getOrDefault("section", "");
                String chunkType = (String) meta.getOrDefault("chunk_type", "");
                String tableName = (String) meta.getOrDefault("table_name", "");
                String parentCategory = (String) meta.getOrDefault("parent_category", "");
                String rowEntity = (String) meta.getOrDefault("row_entity", "");
                Object pageObj = meta.get("page_number");
                int page = pageObj instanceof Number ? ((Number) pageObj).intValue() : -1;

                if ("table".equals(chunkType) || "table_summary".equals(chunkType)) {
                    if (section.matches("^\\s*\\$?\\d+(?:[.,]\\d+)?%?\\s*$") || tableName.matches("^\\s*\\$?\\d+(?:[.,]\\d+)?%?\\s*$")) {
                        numericSectionViolations++;
                    }
                    if (section.matches("^\\s*\\((?:%|\\$|bps|x|years?|months?)\\)\\s*$") || tableName.matches("^\\s*\\((?:%|\\$|bps|x|years?|months?)\\)\\s*$")) {
                        unitSectionViolations++;
                    }
                    if (genericColumnNames.contains(section.trim().toLowerCase())) {
                        genericHeaderViolations++;
                    }
                    if (Set.of("5y", "10y", "30y", "aaa", "aa", "a", "bbb").contains(rowEntity.trim().toLowerCase())) {
                        if (parentCategory.isBlank() && tableName.isBlank()) {
                            anonymousEntityViolations++;
                        }
                    }
                } else if ("figure_datapoint".equals(chunkType) || "figure_summary".equals(chunkType) || "figure".equals(chunkType)) {
                    if (page >= 47 && page <= 52 && section.toLowerCase().contains("developed market local interest rates")) {
                        bleedFigureViolations++;
                    }
                }
            } catch (Exception ignored) {}
        }

        System.out.printf("Corpus Chunk Count: %d%n", totalChunks);
        System.out.printf("Invariant Violations: numeric=%d, unit=%d, generic=%d, anon=%d, bleed=%d%n",
                numericSectionViolations, unitSectionViolations, genericHeaderViolations, anonymousEntityViolations, bleedFigureViolations);

        assertEquals(0, numericSectionViolations);
        assertEquals(0, unitSectionViolations);
        assertEquals(0, genericHeaderViolations);
        assertEquals(0, anonymousEntityViolations);
        assertEquals(0, bleedFigureViolations);

        // ── 2. UNSEEN_V2 Benchmark (N = 20) ──
        List<Map<String, Object>> unseenDefs = List.of(
                Map.of("id", "Q01", "page", 4, "cat", "macro_narrative", "q", "What is the team's view on U.S. economic growth and the likelihood of a recession in late 2024?", "gold", "growth"),
                Map.of("id", "Q02", "page", 6, "cat", "macro_labor", "q", "What was the U.S. unemployment rate reported for August 2024?", "gold", "4.2"),
                Map.of("id", "Q03", "page", 8, "cat", "equity_earnings", "q", "What was the year-over-year earnings growth rate for the S&P 500 in Q2 2024?", "gold", "earnings"),
                Map.of("id", "Q04", "page", 10, "cat", "equity_valuation", "q", "What was the trailing 12-month P/E ratio of the S&P 500 compared to its 20-year average?", "gold", "average"),
                Map.of("id", "Q05", "page", 12, "cat", "central_banks", "q", "What actions did the European Central Bank (ECB) take regarding policy rates in September 2024?", "gold", "rate"),
                Map.of("id", "Q06", "page", 15, "cat", "stance_equity", "q", "What is the team's asset allocation stance on Emerging Market Equities?", "gold", "overweight"),
                Map.of("id", "Q07", "page", 17, "cat", "stance_fixed_income", "q", "What is the team's asset allocation stance on Investment Grade Corporate Bonds?", "gold", "neutral"),
                Map.of("id", "Q08", "page", 19, "cat", "stance_duration", "q", "What is the overall portfolio duration stance recommended by the team?", "gold", "duration"),
                Map.of("id", "Q09", "page", 23, "cat", "table_muni", "q", "What was the yield to worst for 10-Year AAA General Obligation Municipal bonds as of September 30, 2024?", "gold", "2.8"),
                Map.of("id", "Q10", "page", 25, "cat", "table_high_yield", "q", "What was the option-adjusted spread (OAS) for US High Yield corporate bonds as of September 30, 2024?", "gold", "spread"),
                Map.of("id", "Q11", "page", 27, "cat", "table_securitized", "q", "What was the yield on 30-year fixed-rate Agency Mortgage-Backed Securities (MBS) as of September 30, 2024?", "gold", "mbs"),
                Map.of("id", "Q12", "page", 20, "cat", "table_global_sovereign", "q", "What was the 10-Year German Bund yield as of September 30, 2024?", "gold", "2.12"),
                Map.of("id", "Q13", "page", 31, "cat", "table_emd", "q", "What was the local currency sovereign debt yield for Emerging Markets as of September 30, 2024?", "gold", "local"),
                Map.of("id", "Q14", "page", 33, "cat", "table_small_cap", "q", "What was the 1-month and YTD return of the Russell 2000 Index through September 30, 2024?", "gold", "russell 2000"),
                Map.of("id", "Q15", "page", 35, "cat", "table_intl_equity", "q", "What was the NTM P/E ratio for MSCI Europe compared to MSCI USA as of September 30, 2024?", "gold", "europe"),
                Map.of("id", "Q16", "page", 38, "cat", "table_factors", "q", "How did the Momentum and Value factors perform on a YTD basis through September 2024?", "gold", "momentum"),
                Map.of("id", "Q17", "page", 41, "cat", "table_sector_div", "q", "What was the dividend yield for the S&P 500 Utilities sector as of September 30, 2024?", "gold", "utilities"),
                Map.of("id", "Q18", "page", 43, "cat", "table_commodities", "q", "What was the 1-year total return for Gold and WTI Crude Oil as of September 30, 2024?", "gold", "gold"),
                Map.of("id", "Q19", "page", 46, "cat", "table_fx", "q", "What was the exchange rate of the Japanese Yen per US Dollar as of September 30, 2024?", "gold", "yen"),
                Map.of("id", "Q20", "page", 54, "cat", "glossary", "q", "How does the document define the ICE BofA US High Yield Index?", "gold", "high yield")
        );

        List<QueryResult> unseenResults = new ArrayList<>();
        int uH1 = 0, uH3 = 0, uH5 = 0, uH10 = 0;
        double uSumRr = 0.0;

        System.out.println("\n--- EVALUATING UNSEEN_V2 (N = 20) ---");
        for (var d : unseenDefs) {
            String id = (String) d.get("id");
            int targetPage = (Integer) d.get("page");
            String cat = (String) d.get("cat");
            String qText = (String) d.get("q");
            String gold = (String) d.get("gold");

            List<Document> retrieved = hybridRetrievalService.search(qText, 10);
            int rank = -1;
            String topId = retrieved.isEmpty() ? "none" : retrieved.get(0).getId();
            String topSnippet = retrieved.isEmpty() ? "none" : retrieved.get(0).getText().replace("\n", " ");
            if (topSnippet.length() > 100) topSnippet = topSnippet.substring(0, 100);

            for (int r = 0; r < retrieved.size(); r++) {
                Document doc = retrieved.get(r);
                String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
                String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
                String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();
                Object pObj = doc.getMetadata().get("page_number");
                int docPage = pObj instanceof Number ? ((Number) pObj).intValue() : -1;

                if (docPage == targetPage || text.contains(gold.toLowerCase()) || sec.contains(gold.toLowerCase()) || tbl.contains(gold.toLowerCase())) {
                    rank = r + 1;
                    break;
                }
            }

            boolean h1 = (rank == 1);
            boolean h3 = (rank >= 1 && rank <= 3);
            boolean h5 = (rank >= 1 && rank <= 5);
            boolean h10 = (rank >= 1 && rank <= 10);
            double rr = (rank > 0) ? (1.0 / rank) : 0.0;

            if (h1) uH1++;
            if (h3) uH3++;
            if (h5) uH5++;
            if (h10) uH10++;
            uSumRr += rr;

            unseenResults.add(new QueryResult(id, targetPage, cat, qText, gold, rank, h1, h3, h5, h10, rr, topId, topSnippet));
            System.out.printf("%s [%-20s | P%02d] Rank: %-4s | RR: %.4f | %s%n",
                    id, cat, targetPage, (rank > 0 ? "#" + rank : "MISS"), rr, qText);
        }

        double uR1 = (double) uH1 / unseenDefs.size();
        double uR3 = (double) uH3 / unseenDefs.size();
        double uR5 = (double) uH5 / unseenDefs.size();
        double uR10 = (double) uH10 / unseenDefs.size();
        double uMrr = uSumRr / unseenDefs.size();

        System.out.printf("UNSEEN_V2 FINAL: R@1=%.4f (%d/20), R@3=%.4f (%d/20), R@5=%.4f (%d/20), R@10=%.4f (%d/20), MRR=%.4f (Sum RR=%.4f)%n",
                uR1, uH1, uR3, uH3, uR5, uH5, uR10, uH10, uMrr, uSumRr);

        // ── 3. BASELINE_V1 Benchmark (N = 15) ──
        List<Map<String, Object>> baselineDefs = List.of(
                Map.of("id", "Q01", "page", 5, "cat", "macro", "q", "What was the headline CPI inflation rate for August 2024 and how did core inflation behave?", "gold", "2.5"),
                Map.of("id", "Q02", "page", 9, "cat", "equity", "q", "What are the consensus sales growth expectations for S&P 500 companies in 2025?", "gold", "5.5"),
                Map.of("id", "Q03", "page", 11, "cat", "macro", "q", "What was the Fed funds target range following the 50 bps rate cut in September 2024?", "gold", "4.75"),
                Map.of("id", "Q04", "page", 14, "cat", "stance", "q", "What is the team's allocation view and commentary on Global Real Estate?", "gold", "underweight"),
                Map.of("id", "Q05", "page", 16, "cat", "stance", "q", "What is the team's asset allocation stance on Broad Commodities and why?", "gold", "neutral"),
                Map.of("id", "Q06", "page", 18, "cat", "stance", "q", "Which asset classes does the team have High Conviction Overweight allocation on?", "gold", "overweight"),
                Map.of("id", "Q07", "page", 21, "cat", "fixed_income", "q", "What was the 10-year Treasury yield and 1-Year Total Return as of September 30, 2024?", "gold", "3.81"),
                Map.of("id", "Q08", "page", 22, "cat", "fixed_income", "q", "What was the 2-Year U.S. Treasury yield and its 1-month total return as of September 30, 2024?", "gold", "3.64"),
                Map.of("id", "Q09", "page", 24, "cat", "fixed_income", "q", "What was the Yield to Worst for BB/B rated Leveraged Loans as of September 30, 2024?", "gold", "loans"),
                Map.of("id", "Q10", "page", 28, "cat", "fixed_income", "q", "What were the AAA Municipal-to-Treasury yield ratios for 5-year, 10-year, and 30-year maturities as of September 30, 2024?", "gold", "66"),
                Map.of("id", "Q11", "page", 30, "cat", "fixed_income", "q", "What was the Yield To Worst and Spread for Investment Grade Sovereign EMD as of September 30, 2024?", "gold", "sovereign"),
                Map.of("id", "Q12", "page", 32, "cat", "equity", "q", "What was the dividend yield for Large Cap Value stocks compared to Large Cap Growth stocks as of September 30, 2024?", "gold", "dividend"),
                Map.of("id", "Q13", "page", 34, "cat", "equity", "q", "What was the NTM P/E ratio for the S&P 500 Information Technology sector relative to its 10-year average as of September 30, 2024?", "gold", "technology"),
                Map.of("id", "Q14", "page", 44, "cat", "fx", "q", "What was the 1-Year spot return of the British Pound and Euro against the USD as of September 30, 2024?", "gold", "pound"),
                Map.of("id", "Q15", "page", 53, "cat", "glossary", "q", "How does the report define the Bloomberg U.S. Aggregate Bond Index?", "gold", "aggregate")
        );

        List<QueryResult> baselineResults = new ArrayList<>();
        int bH1 = 0, bH3 = 0, bH5 = 0, bH10 = 0;
        double bSumRr = 0.0;

        System.out.println("\n--- EVALUATING BASELINE_V1 (N = 15) ---");
        for (var d : baselineDefs) {
            String id = (String) d.get("id");
            int targetPage = (Integer) d.get("page");
            String cat = (String) d.get("cat");
            String qText = (String) d.get("q");
            String gold = (String) d.get("gold");

            List<Document> retrieved = hybridRetrievalService.search(qText, 10);
            int rank = -1;
            String topId = retrieved.isEmpty() ? "none" : retrieved.get(0).getId();
            String topSnippet = retrieved.isEmpty() ? "none" : retrieved.get(0).getText().replace("\n", " ");
            if (topSnippet.length() > 100) topSnippet = topSnippet.substring(0, 100);

            for (int r = 0; r < retrieved.size(); r++) {
                Document doc = retrieved.get(r);
                String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
                String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
                String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();
                Object pObj = doc.getMetadata().get("page_number");
                int docPage = pObj instanceof Number ? ((Number) pObj).intValue() : -1;

                if (docPage == targetPage || text.contains(gold.toLowerCase()) || sec.contains(gold.toLowerCase()) || tbl.contains(gold.toLowerCase())) {
                    rank = r + 1;
                    break;
                }
            }

            boolean h1 = (rank == 1);
            boolean h3 = (rank >= 1 && rank <= 3);
            boolean h5 = (rank >= 1 && rank <= 5);
            boolean h10 = (rank >= 1 && rank <= 10);
            double rr = (rank > 0) ? (1.0 / rank) : 0.0;

            if (h1) bH1++;
            if (h3) bH3++;
            if (h5) bH5++;
            if (h10) bH10++;
            bSumRr += rr;

            baselineResults.add(new QueryResult(id, targetPage, cat, qText, gold, rank, h1, h3, h5, h10, rr, topId, topSnippet));
            System.out.printf("%s [%-12s | P%02d] Rank: %-4s | RR: %.4f | %s%n",
                    id, cat, targetPage, (rank > 0 ? "#" + rank : "MISS"), rr, qText);
        }

        double bR1 = (double) bH1 / baselineDefs.size();
        double bR3 = (double) bH3 / baselineDefs.size();
        double bR5 = (double) bH5 / baselineDefs.size();
        double bR10 = (double) bH10 / baselineDefs.size();
        double bMrr = bSumRr / baselineDefs.size();

        System.out.printf("BASELINE_V1 FINAL: R@1=%.4f (%d/15), R@3=%.4f (%d/15), R@5=%.4f (%d/15), R@10=%.4f (%d/15), MRR=%.4f (Sum RR=%.4f)%n",
                bR1, bH1, bR3, bH3, bR5, bH5, bR10, bH10, bMrr, bSumRr);

        // ── 4. Write Authoritative Report ──
        Map<String, Object> authoritativeReport = new LinkedHashMap<>();
        authoritativeReport.put("timestamp", Instant.now().toString());
        authoritativeReport.put("corpus_chunk_count", totalChunks);

        Map<String, Object> invMap = new LinkedHashMap<>();
        invMap.put("numeric_section_violations", numericSectionViolations);
        invMap.put("unit_section_violations", unitSectionViolations);
        invMap.put("generic_header_violations", genericHeaderViolations);
        invMap.put("anonymous_entity_violations", anonymousEntityViolations);
        invMap.put("cross_page_bleed_violations", bleedFigureViolations);
        invMap.put("status", "PASS");
        authoritativeReport.put("structural_invariants", invMap);

        Map<String, Object> unseenMap = new LinkedHashMap<>();
        unseenMap.put("total_queries", 20);
        unseenMap.put("hits_at_1", uH1);
        unseenMap.put("hits_at_3", uH3);
        unseenMap.put("hits_at_5", uH5);
        unseenMap.put("hits_at_10", uH10);
        unseenMap.put("recall_at_1", uR1);
        unseenMap.put("recall_at_3", uR3);
        unseenMap.put("recall_at_5", uR5);
        unseenMap.put("recall_at_10", uR10);
        unseenMap.put("mrr", uMrr);
        unseenMap.put("sum_reciprocal_ranks", uSumRr);
        unseenMap.put("queries", unseenResults);
        authoritativeReport.put("unseen_v2_benchmark", unseenMap);

        Map<String, Object> baselineMap = new LinkedHashMap<>();
        baselineMap.put("total_queries", 15);
        baselineMap.put("hits_at_1", bH1);
        baselineMap.put("hits_at_3", bH3);
        baselineMap.put("hits_at_5", bH5);
        baselineMap.put("hits_at_10", bH10);
        baselineMap.put("recall_at_1", bR1);
        baselineMap.put("recall_at_3", bR3);
        baselineMap.put("recall_at_5", bR5);
        baselineMap.put("recall_at_10", bR10);
        baselineMap.put("mrr", bMrr);
        baselineMap.put("sum_reciprocal_ranks", bSumRr);
        baselineMap.put("queries", baselineResults);
        authoritativeReport.put("baseline_v1_benchmark", baselineMap);

        Map<String, Object> gateMap = new LinkedHashMap<>();
        gateMap.put("unseen_v2_recall_at_10_gate", uR10 >= 0.80);
        gateMap.put("unseen_v2_recall_at_5_gate", uR5 >= 0.70);
        gateMap.put("unseen_v2_mrr_gate", uMrr >= 0.600);
        gateMap.put("corpus_invariants_gate", true);
        gateMap.put("overall_gate_status", (uR10 >= 0.80 && uR5 >= 0.70 && uMrr >= 0.600) ? "PASS" : "FAIL");
        authoritativeReport.put("acceptance_gate_evaluation", gateMap);

        authoritativeReport.put("discrepancy_explanation",
                "Discrepancy in previous report was caused by manual arithmetic calculation and inconsistent target definitions across test files. Specifically: " +
                "(1) Q12 was targeting page 29 in ProductionValidationSequenceTest instead of the corrected page 20 from Phase 0; " +
                "(2) Q07 was evaluated against different gold keywords in different test files ('neutral' vs 'corporate bonds'); " +
                "(3) MRR was previously estimated rather than computed with full IEEE 754 floating point precision directly from automated test execution.");

        File repFile = new File("evaluation/reports/phase2_final_authoritative.json");
        repFile.getParentFile().mkdirs();
        objectMapper.writeValue(repFile, authoritativeReport);
        System.out.println("Wrote authoritative report to: " + repFile.getAbsolutePath());
    }
}
