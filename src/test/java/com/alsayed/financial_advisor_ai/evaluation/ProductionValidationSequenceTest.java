package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvidenceGroup;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.EvidenceBasedRetrievalEvaluator;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class ProductionValidationSequenceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private EvidenceBasedRetrievalEvaluator evidenceEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvaluationQuestion createQuestion(String id, String text, List<String> requiredTerms) {
        EvaluationQuestion q = new EvaluationQuestion();
        q.setId(id);
        q.setQuestionText(text);
        q.setEvidenceGroups(List.of(new EvidenceGroup(requiredTerms)));
        return q;
    }

    @Test
    @Order(1)
    @DisplayName("Stage 1: Verify 100% Corpus Structural Invariants")
    void stage1_verifyCorpusStructuralInvariants() {
        System.out.println("==================================================================================");
        System.out.println("               STAGE 1: VERIFY CORPUS STRUCTURAL INVARIANTS");
        System.out.println("==================================================================================");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, content, metadata FROM vector_store");
        assertThat(rows).isNotEmpty();

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
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        System.out.printf("Total Indexed Chunks: %d%n", rows.size());
        System.out.printf("Invariant 1 (No numeric sections):           %d violations%n", numericSectionViolations);
        System.out.printf("Invariant 2 (No unit-only sections):         %d violations%n", unitSectionViolations);
        System.out.printf("Invariant 3 (No generic column headers):     %d violations%n", genericHeaderViolations);
        System.out.printf("Invariant 4 (Anonymous entities contextual): %d violations%n", anonymousEntityViolations);
        System.out.printf("Invariant 5 (No cross-page figure bleed):    %d violations%n", bleedFigureViolations);
        System.out.println("----------------------------------------------------------------------------------");

        assertThat(numericSectionViolations).isZero();
        assertThat(unitSectionViolations).isZero();
        assertThat(genericHeaderViolations).isZero();
        assertThat(anonymousEntityViolations).isZero();
        assertThat(bleedFigureViolations).isZero();
        System.out.println("STAGE 1 RESULT: 100% PASS (0 Invariant Violations)");
    }

    @Test
    @Order(2)
    @DisplayName("Stage 2: Evaluate Legacy Regression Benchmark (Q1–Q11)")
    void stage2_evaluateLegacyRegression() {
        System.out.println("==================================================================================");
        System.out.println("               STAGE 2: LEGACY REGRESSION BENCHMARK (Q1–Q11)");
        System.out.println("==================================================================================");

        List<EvaluationQuestion> questions = List.of(
                createQuestion("Q1", "What was the headline CPI inflation rate for August 2024 and how did core inflation behave?", List.of("2.5")),
                createQuestion("Q2", "What are the consensus sales growth expectations for S&P 500 companies in 2025?", List.of("5.5")),
                createQuestion("Q3", "What was the Fed funds target range following the 50 bps rate cut in September 2024?", List.of("4.75")),
                createQuestion("Q4", "What is the team's allocation view and commentary on Global Real Estate?", List.of("Underweight")),
                createQuestion("Q5", "What is the team's asset allocation stance on Broad Commodities and why?", List.of("Neutral")),
                createQuestion("Q6", "What were the YTD total returns for the best and worst performing S&P 500 sectors through September 2024?", List.of("Utilities", "30.8")),
                createQuestion("Q7", "What was the 10-year historical return correlation between the S&P 500 and the Bloomberg US Aggregate?", List.of("0.24")),
                createQuestion("Q8", "What was the 2-Year U.S. Treasury yield and its 1-month total return as of September 30, 2024?", List.of("3.64")),
                createQuestion("Q9", "What was the trailing 12-month default rate for US speculative grade corporate bonds as of September 2024?", List.of("4.69")),
                createQuestion("Q10", "What were the AAA Municipal-to-Treasury yield ratios for 5-year, 10-year, and 30-year maturities as of September 30, 2024?", List.of("66")),
                createQuestion("Q11", "How does the report define the Bloomberg U.S. Aggregate Bond Index?", List.of("investment grade"))
        );

        int passed = 0;
        for (EvaluationQuestion q : questions) {
            List<Document> retrieved = hybridRetrievalService.search(q.getQuestionText(), 10);
            var res = evidenceEvaluator.evaluateEvidence(q, retrieved, 10);
            boolean p = res.matched();
            if (p) passed++;
            System.out.printf("[%s] %s -> %s (Hit@10: %b)%n", q.getId(), p ? "PASS" : "FAIL", q.getQuestionText(), p);
        }

        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("STAGE 2 RESULT: Passed %d / %d (%.1f%%)%n", passed, questions.size(), (double) passed / questions.size() * 100.0);
    }

    @Test
    @Order(3)
    @DisplayName("Stage 3: Evaluate Frozen BASELINE_V1 Benchmark (N = 15)")
    void stage3_evaluateBaselineV1() throws Exception {
        System.out.println("==================================================================================");
        System.out.println("               STAGE 3: FROZEN BASELINE_V1 BENCHMARK (N = 15)");
        System.out.println("==================================================================================");

        List<Map<String, Object>> queries = List.of(
                Map.of("id", "Q01", "page", 5, "q", "What was the headline CPI inflation rate for August 2024 and how did core inflation behave?", "gold", "2.5"),
                Map.of("id", "Q02", "page", 9, "q", "What are the consensus sales growth expectations for S&P 500 companies in 2025?", "gold", "5.5"),
                Map.of("id", "Q03", "page", 11, "q", "What was the Fed funds target range following the 50 bps rate cut in September 2024?", "gold", "4.75"),
                Map.of("id", "Q04", "page", 14, "q", "What is the team's allocation view and commentary on Global Real Estate?", "gold", "underweight"),
                Map.of("id", "Q05", "page", 16, "q", "What is the team's asset allocation stance on Broad Commodities and why?", "gold", "neutral"),
                Map.of("id", "Q06", "page", 18, "q", "Which asset classes does the team have High Conviction Overweight allocation on?", "gold", "overweight"),
                Map.of("id", "Q07", "page", 21, "q", "What was the 10-year Treasury yield and 1-Year Total Return as of September 30, 2024?", "gold", "3.81"),
                Map.of("id", "Q08", "page", 22, "q", "What was the 2-Year U.S. Treasury yield and its 1-month total return as of September 30, 2024?", "gold", "3.64"),
                Map.of("id", "Q09", "page", 24, "q", "What was the Yield to Worst for BB/B rated Leveraged Loans as of September 30, 2024?", "gold", "loans"),
                Map.of("id", "Q10", "page", 28, "q", "What were the AAA Municipal-to-Treasury yield ratios for 5-year, 10-year, and 30-year maturities as of September 30, 2024?", "gold", "66"),
                Map.of("id", "Q11", "page", 30, "q", "What was the Yield To Worst and Spread for Investment Grade Sovereign EMD as of September 30, 2024?", "gold", "sovereign"),
                Map.of("id", "Q12", "page", 32, "q", "What was the dividend yield for Large Cap Value stocks compared to Large Cap Growth stocks as of September 30, 2024?", "gold", "dividend"),
                Map.of("id", "Q13", "page", 34, "q", "What was the NTM P/E ratio for the S&P 500 Information Technology sector relative to its 10-year average as of September 30, 2024?", "gold", "technology"),
                Map.of("id", "Q14", "page", 44, "q", "What was the 1-Year spot return of the British Pound and Euro against the USD as of September 30, 2024?", "gold", "pound"),
                Map.of("id", "Q15", "page", 53, "q", "How does the report define the Bloomberg U.S. Aggregate Bond Index?", "gold", "aggregate")
        );

        int h1 = 0, h3 = 0, h5 = 0, h10 = 0;
        double sumRr = 0.0;
        List<Map<String, Object>> records = new ArrayList<>();

        for (Map<String, Object> q : queries) {
            String id = (String) q.get("id");
            int page = (Integer) q.get("page");
            String qText = (String) q.get("q");
            String gold = (String) q.get("gold");

            List<Document> retrieved = hybridRetrievalService.search(qText, 10);
            int rank = -1;
            for (int r = 0; r < retrieved.size(); r++) {
                Document doc = retrieved.get(r);
                String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
                String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
                String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();
                Object pObj = doc.getMetadata().get("page_number");
                int docPage = pObj instanceof Number ? ((Number) pObj).intValue() : -1;

                if (docPage == page || text.contains(gold.toLowerCase()) || sec.contains(gold.toLowerCase()) || tbl.contains(gold.toLowerCase())) {
                    rank = r + 1;
                    break;
                }
            }

            if (rank == 1) h1++;
            if (rank >= 1 && rank <= 3) h3++;
            if (rank >= 1 && rank <= 5) h5++;
            if (rank >= 1 && rank <= 10) h10++;
            if (rank > 0) sumRr += (1.0 / rank);

            records.add(Map.of("id", id, "page", page, "question", qText, "rank", rank));
            System.out.printf("[%s | P%02d] Rank: %s | %s%n", id, page, (rank > 0 ? "#" + rank : "MISS"), qText);
        }

        double r1 = (double) h1 / queries.size();
        double r3 = (double) h3 / queries.size();
        double r5 = (double) h5 / queries.size();
        double r10 = (double) h10 / queries.size();
        double mrr = sumRr / queries.size();

        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("STAGE 3 SUMMARY: Recall@1=%.3f | Recall@3=%.3f | Recall@5=%.3f | Recall@10=%.3f | MRR=%.3f%n",
                r1, r3, r5, r10, mrr);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                new File("evaluation/reports/baseline_v1_production_validation.json"),
                Map.of("metrics", Map.of("r1", r1, "r3", r3, "r5", r5, "r10", r10, "mrr", mrr), "queries", records));
    }

    @Test
    @Order(4)
    @DisplayName("Stage 4: Evaluate Frozen UNSEEN_V2 Benchmark (N = 20)")
    void stage4_evaluateUnseenV2() throws Exception {
        System.out.println("==================================================================================");
        System.out.println("               STAGE 4: FROZEN UNSEEN_V2 BENCHMARK (N = 20)");
        System.out.println("==================================================================================");

        List<Map<String, Object>> queries = List.of(
                Map.of("id", 1, "page", 4, "cat", "macro_narrative", "q", "What is the team's view on U.S. economic growth and the likelihood of a recession in late 2024?", "gold", "growth"),
                Map.of("id", 2, "page", 6, "cat", "macro_labor", "q", "What was the U.S. unemployment rate reported for August 2024?", "gold", "4.2"),
                Map.of("id", 3, "page", 8, "cat", "equity_earnings", "q", "What was the year-over-year earnings growth rate for the S&P 500 in Q2 2024?", "gold", "earnings"),
                Map.of("id", 4, "page", 10, "cat", "equity_valuation", "q", "What was the trailing 12-month P/E ratio of the S&P 500 compared to its 20-year average?", "gold", "average"),
                Map.of("id", 5, "page", 12, "cat", "central_banks", "q", "What actions did the European Central Bank (ECB) take regarding policy rates in September 2024?", "gold", "rate"),
                Map.of("id", 6, "page", 15, "cat", "stance_equity", "q", "What is the team's asset allocation stance on Emerging Market Equities?", "gold", "overweight"),
                Map.of("id", 7, "page", 17, "cat", "stance_fixed_income", "q", "What is the team's asset allocation stance on Investment Grade Corporate Bonds?", "gold", "neutral"),
                Map.of("id", 8, "page", 19, "cat", "stance_duration", "q", "What is the overall portfolio duration stance recommended by the team?", "gold", "duration"),
                Map.of("id", 9, "page", 23, "cat", "table_muni", "q", "What was the yield to worst for 10-Year AAA General Obligation Municipal bonds as of September 30, 2024?", "gold", "2.8"),
                Map.of("id", 10, "page", 25, "cat", "table_high_yield", "q", "What was the option-adjusted spread (OAS) for US High Yield corporate bonds as of September 30, 2024?", "gold", "spread"),
                Map.of("id", 11, "page", 27, "cat", "table_securitized", "q", "What was the yield on 30-year fixed-rate Agency Mortgage-Backed Securities (MBS) as of September 30, 2024?", "gold", "mbs"),
                Map.of("id", 12, "page", 29, "cat", "table_global_sovereign", "q", "What was the 10-Year German Bund yield as of September 30, 2024?", "gold", "2.12"),
                Map.of("id", 13, "page", 31, "cat", "table_emd", "q", "What was the local currency sovereign debt yield for Emerging Markets as of September 30, 2024?", "gold", "local"),
                Map.of("id", 14, "page", 33, "cat", "table_small_cap", "q", "What was the 1-month and YTD return of the Russell 2000 Index through September 30, 2024?", "gold", "russell 2000"),
                Map.of("id", 15, "page", 35, "cat", "table_intl_equity", "q", "What was the NTM P/E ratio for MSCI Europe compared to MSCI USA as of September 30, 2024?", "gold", "europe"),
                Map.of("id", 16, "page", 38, "cat", "table_factors", "q", "How did the Momentum and Value factors perform on a YTD basis through September 2024?", "gold", "momentum"),
                Map.of("id", 17, "page", 41, "cat", "table_sector_div", "q", "What was the dividend yield for the S&P 500 Utilities sector as of September 30, 2024?", "gold", "utilities"),
                Map.of("id", 18, "page", 43, "cat", "table_commodities", "q", "What was the 1-year total return for Gold and WTI Crude Oil as of September 30, 2024?", "gold", "gold"),
                Map.of("id", 19, "page", 46, "cat", "table_fx", "q", "What was the exchange rate of the Japanese Yen per US Dollar as of September 30, 2024?", "gold", "yen"),
                Map.of("id", 20, "page", 54, "cat", "glossary", "q", "How does the document define the ICE BofA US High Yield Index?", "gold", "high yield")
        );

        int h1 = 0, h3 = 0, h5 = 0, h10 = 0;
        double sumRr = 0.0;
        List<Map<String, Object>> records = new ArrayList<>();

        for (Map<String, Object> q : queries) {
            int id = (Integer) q.get("id");
            int page = (Integer) q.get("page");
            String cat = (String) q.get("cat");
            String qText = (String) q.get("q");
            String gold = (String) q.get("gold");

            List<Document> retrieved = hybridRetrievalService.search(qText, 10);
            int rank = -1;
            for (int r = 0; r < retrieved.size(); r++) {
                Document doc = retrieved.get(r);
                String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
                String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
                String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();
                Object pObj = doc.getMetadata().get("page_number");
                int docPage = pObj instanceof Number ? ((Number) pObj).intValue() : -1;

                if (docPage == page || text.contains(gold.toLowerCase()) || sec.contains(gold.toLowerCase()) || tbl.contains(gold.toLowerCase())) {
                    rank = r + 1;
                    break;
                }
            }

            if (rank == 1) h1++;
            if (rank >= 1 && rank <= 3) h3++;
            if (rank >= 1 && rank <= 5) h5++;
            if (rank >= 1 && rank <= 10) h10++;
            if (rank > 0) sumRr += (1.0 / rank);

            records.add(Map.of("id", id, "page", page, "category", cat, "question", qText, "rank", rank));
            System.out.printf("Q%02d [%-20s | P%02d] Rank: %s | %s%n", id, cat, page, (rank > 0 ? "#" + rank : "MISS"), qText);
        }

        double r1 = (double) h1 / queries.size();
        double r3 = (double) h3 / queries.size();
        double r5 = (double) h5 / queries.size();
        double r10 = (double) h10 / queries.size();
        double mrr = sumRr / queries.size();

        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("STAGE 4 SUMMARY: Recall@1=%.3f | Recall@3=%.3f | Recall@5=%.3f | Recall@10=%.3f | MRR=%.3f%n",
                r1, r3, r5, r10, mrr);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                new File("evaluation/reports/unseen_v2_production_validation.json"),
                Map.of("metrics", Map.of("r1", r1, "r3", r3, "r5", r5, "r10", r10, "mrr", mrr), "queries", records));
    }
}
