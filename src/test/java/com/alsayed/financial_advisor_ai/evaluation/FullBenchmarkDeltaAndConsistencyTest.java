package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvidenceGroup;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.EvidenceBasedRetrievalEvaluator;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class FullBenchmarkDeltaAndConsistencyTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private EvidenceBasedRetrievalEvaluator evidenceEvaluator;

    private EvaluationQuestion createQuestion(String id, String text, List<String> requiredTerms) {
        EvaluationQuestion q = new EvaluationQuestion();
        q.setId(id);
        q.setQuestionText(text);
        q.setEvidenceGroups(List.of(new EvidenceGroup(requiredTerms)));
        return q;
    }

    @Test
    @DisplayName("Evaluate and Compare UNSEEN_V2, BASELINE_V1, Legacy Q1-Q11 with Manual Metric Recalculation")
    void evaluateAllBenchmarks() {
        System.out.println("==================================================================================");
        System.out.println("               FULL BENCHMARK DELTA & CONSISTENCY VERIFICATION");
        System.out.println("==================================================================================");

        // 1. UNSEEN_V2 (N = 20)
        List<Map<String, Object>> unseenQueries = List.of(
                Map.of("id", 1, "page", 4, "q", "What is the team's view on U.S. economic growth and the likelihood of a recession in late 2024?", "gold", "growth", "prevRank", 1),
                Map.of("id", 2, "page", 6, "q", "What was the U.S. unemployment rate reported for August 2024?", "gold", "4.2", "prevRank", 4),
                Map.of("id", 3, "page", 8, "q", "What was the year-over-year earnings growth rate for the S&P 500 in Q2 2024?", "gold", "earnings", "prevRank", 1),
                Map.of("id", 4, "page", 10, "q", "What was the trailing 12-month P/E ratio of the S&P 500 compared to its 20-year average?", "gold", "average", "prevRank", 1),
                Map.of("id", 5, "page", 12, "q", "What actions did the European Central Bank (ECB) take regarding policy rates in September 2024?", "gold", "rate", "prevRank", 1),
                Map.of("id", 6, "page", 15, "q", "What is the team's asset allocation stance on Emerging Market Equities?", "gold", "overweight", "prevRank", 6),
                Map.of("id", 7, "page", 17, "q", "What is the team's asset allocation stance on Investment Grade Corporate Bonds?", "gold", "neutral", "prevRank", 9),
                Map.of("id", 8, "page", 19, "q", "What is the overall portfolio duration stance recommended by the team?", "gold", "duration", "prevRank", 1),
                Map.of("id", 9, "page", 23, "q", "What was the yield to worst for 10-Year AAA General Obligation Municipal bonds as of September 30, 2024?", "gold", "municipal", "prevRank", 9),
                Map.of("id", 10, "page", 25, "q", "What was the option-adjusted spread (OAS) for US High Yield corporate bonds as of September 30, 2024?", "gold", "spread", "prevRank", 1),
                Map.of("id", 11, "page", 27, "q", "What was the yield on 30-year fixed-rate Agency Mortgage-Backed Securities (MBS) as of September 30, 2024?", "gold", "mbs", "prevRank", 1),
                Map.of("id", 12, "page", 20, "q", "What was the 10-Year German Bund yield as of September 30, 2024?", "gold", "bund", "prevRank", 6),
                Map.of("id", 13, "page", 31, "q", "What was the local currency sovereign debt yield for Emerging Markets as of September 30, 2024?", "gold", "sovereign", "prevRank", 2),
                Map.of("id", 14, "page", 33, "q", "What was the 1-month and YTD return of the Russell 2000 Index through September 30, 2024?", "gold", "russell", "prevRank", 1),
                Map.of("id", 15, "page", 35, "q", "What was the NTM P/E ratio for MSCI Europe compared to MSCI USA as of September 30, 2024?", "gold", "europe", "prevRank", 1),
                Map.of("id", 16, "page", 38, "q", "How did the Momentum and Value factors perform on a YTD basis through September 2024?", "gold", "momentum", "prevRank", -1),
                Map.of("id", 17, "page", 41, "q", "What was the dividend yield for the S&P 500 Utilities sector as of September 30, 2024?", "gold", "utilities", "prevRank", 8),
                Map.of("id", 18, "page", 43, "q", "What was the 1-year total return for Gold and WTI Crude Oil as of September 30, 2024?", "gold", "gold", "prevRank", 2),
                Map.of("id", 19, "page", 46, "q", "What was the exchange rate of the Japanese Yen per US Dollar as of September 30, 2024?", "gold", "yen", "prevRank", 1),
                Map.of("id", 20, "page", 54, "q", "How does the document define the ICE BofA US High Yield Index?", "gold", "index", "prevRank", 1)
        );

        System.out.println("\n--- UNSEEN_V2 (N = 20) PER-QUERY RANK & DELTAS ---");
        System.out.println("ID  | Target | Prev Rank | Curr Rank | Delta | Status | Question");
        System.out.println("----+--------+-----------+-----------+-------+--------+-------------------------------------------------------");

        int uH1 = 0, uH3 = 0, uH5 = 0, uH10 = 0;
        double uSumRr = 0.0;

        for (var q : unseenQueries) {
            int id = (Integer) q.get("id");
            int page = (Integer) q.get("page");
            String qText = (String) q.get("q");
            String gold = (String) q.get("gold");
            int prevRank = (Integer) q.get("prevRank");

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

            if (rank == 1) uH1++;
            if (rank >= 1 && rank <= 3) uH3++;
            if (rank >= 1 && rank <= 5) uH5++;
            if (rank >= 1 && rank <= 10) uH10++;
            if (rank > 0) uSumRr += (1.0 / rank);

            String deltaStr = (prevRank == rank) ? "  0" : (prevRank == -1 ? "NEW" : (rank == -1 ? "LOST" : String.format("%+3d", prevRank - rank)));
            String status = (rank > 0 && rank <= 10) ? (rank <= 5 ? "PASS (Top5)" : "PASS (Top10)") : "MISS";

            System.out.printf("Q%02d | P%02d   | %-9s | %-9s | %-5s | %-6s | %s%n",
                    id, page, (prevRank > 0 ? "#" + prevRank : "MISS"), (rank > 0 ? "#" + rank : "MISS"), deltaStr, status, qText);
        }

        double uR1 = (double) uH1 / unseenQueries.size();
        double uR3 = (double) uH3 / unseenQueries.size();
        double uR5 = (double) uH5 / unseenQueries.size();
        double uR10 = (double) uH10 / unseenQueries.size();
        double uMrr = uSumRr / unseenQueries.size();

        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("UNSEEN_V2 METRICS: Recall@1=%.3f (%d/20) | Recall@3=%.3f (%d/20) | Recall@5=%.3f (%d/20) | Recall@10=%.3f (%d/20) | MRR=%.3f%n",
                uR1, uH1, uR3, uH3, uR5, uH5, uR10, uH10, uMrr);

        // 2. BASELINE_V1 (N = 15)
        List<Map<String, Object>> baselineQueries = List.of(
                Map.of("id", "Q01", "page", 5, "q", "What was the headline CPI inflation rate for August 2024 and how did core inflation behave?", "gold", "2.5", "prevRank", -1),
                Map.of("id", "Q02", "page", 9, "q", "What are the consensus sales growth expectations for S&P 500 companies in 2025?", "gold", "5.5", "prevRank", 8),
                Map.of("id", "Q03", "page", 11, "q", "What was the Fed funds target range following the 50 bps rate cut in September 2024?", "gold", "4.75", "prevRank", -1),
                Map.of("id", "Q04", "page", 14, "q", "What is the team's allocation view and commentary on Global Real Estate?", "gold", "underweight", "prevRank", 7),
                Map.of("id", "Q05", "page", 16, "q", "What is the team's asset allocation stance on Broad Commodities and why?", "gold", "neutral", "prevRank", 7),
                Map.of("id", "Q06", "page", 18, "q", "Which asset classes does the team have High Conviction Overweight allocation on?", "gold", "overweight", "prevRank", 6),
                Map.of("id", "Q07", "page", 21, "q", "What was the 10-year Treasury yield and 1-Year Total Return as of September 30, 2024?", "gold", "3.81", "prevRank", -1),
                Map.of("id", "Q08", "page", 22, "q", "What was the 2-Year U.S. Treasury yield and its 1-month total return as of September 30, 2024?", "gold", "3.64", "prevRank", 1),
                Map.of("id", "Q09", "page", 24, "q", "What was the Yield to Worst for BB/B rated Leveraged Loans as of September 30, 2024?", "gold", "loans", "prevRank", 1),
                Map.of("id", "Q10", "page", 28, "q", "What were the AAA Municipal-to-Treasury yield ratios for 5-year, 10-year, and 30-year maturities as of September 30, 2024?", "gold", "66", "prevRank", 1),
                Map.of("id", "Q11", "page", 30, "q", "What was the Yield To Worst and Spread for Investment Grade Sovereign EMD as of September 30, 2024?", "gold", "sovereign", "prevRank", 3),
                Map.of("id", "Q12", "page", 32, "q", "What was the dividend yield for Large Cap Value stocks compared to Large Cap Growth stocks as of September 30, 2024?", "gold", "dividend", "prevRank", 1),
                Map.of("id", "Q13", "page", 34, "q", "What was the NTM P/E ratio for the S&P 500 Information Technology sector relative to its 10-year average as of September 30, 2024?", "gold", "technology", "prevRank", 1),
                Map.of("id", "Q14", "page", 44, "q", "What was the 1-Year spot return of the British Pound and Euro against the USD as of September 30, 2024?", "gold", "pound", "prevRank", 3),
                Map.of("id", "Q15", "page", 53, "q", "How does the report define the Bloomberg U.S. Aggregate Bond Index?", "gold", "aggregate", "prevRank", 1)
        );

        System.out.println("\n--- BASELINE_V1 (N = 15) PER-QUERY RANK & DELTAS ---");
        System.out.println("ID  | Target | Prev Rank | Curr Rank | Delta | Status | Question");
        System.out.println("----+--------+-----------+-----------+-------+--------+-------------------------------------------------------");

        int bH1 = 0, bH3 = 0, bH5 = 0, bH10 = 0;
        double bSumRr = 0.0;

        for (var q : baselineQueries) {
            String id = (String) q.get("id");
            int page = (Integer) q.get("page");
            String qText = (String) q.get("q");
            String gold = (String) q.get("gold");
            int prevRank = (Integer) q.get("prevRank");

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

            if (rank == 1) bH1++;
            if (rank >= 1 && rank <= 3) bH3++;
            if (rank >= 1 && rank <= 5) bH5++;
            if (rank >= 1 && rank <= 10) bH10++;
            if (rank > 0) bSumRr += (1.0 / rank);

            String deltaStr = (prevRank == rank) ? "  0" : (prevRank == -1 ? "NEW" : (rank == -1 ? "LOST" : String.format("%+3d", prevRank - rank)));
            String status = (rank > 0 && rank <= 10) ? (rank <= 5 ? "PASS (Top5)" : "PASS (Top10)") : "MISS";

            System.out.printf("%s | P%02d   | %-9s | %-9s | %-5s | %-6s | %s%n",
                    id, page, (prevRank > 0 ? "#" + prevRank : "MISS"), (rank > 0 ? "#" + rank : "MISS"), deltaStr, status, qText);
        }

        double bR1 = (double) bH1 / baselineQueries.size();
        double bR3 = (double) bH3 / baselineQueries.size();
        double bR5 = (double) bH5 / baselineQueries.size();
        double bR10 = (double) bH10 / baselineQueries.size();
        double bMrr = bSumRr / baselineQueries.size();

        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("BASELINE_V1 METRICS: Recall@1=%.3f (%d/15) | Recall@3=%.3f (%d/15) | Recall@5=%.3f (%d/15) | Recall@10=%.3f (%d/15) | MRR=%.3f%n",
                bR1, bH1, bR3, bH3, bR5, bH5, bR10, bH10, bMrr);

        // 3. Legacy Q1-Q11
        List<EvaluationQuestion> legacyQuestions = List.of(
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

        System.out.println("\n--- LEGACY REGRESSION (Q1–Q11) EVALUATION ---");
        int legPassed = 0;
        for (EvaluationQuestion lq : legacyQuestions) {
            List<Document> retrieved = hybridRetrievalService.search(lq.getQuestionText(), 10);
            var res = evidenceEvaluator.evaluateEvidence(lq, retrieved, 10);
            boolean p = res.matched();
            if (p) legPassed++;
            System.out.printf("[%s] %-4s | Matched: %b | %s%n", lq.getId(), p ? "PASS" : "FAIL", p, lq.getQuestionText());
        }
        System.out.printf("LEGACY REGRESSION RESULT: %d / %d (%.1f%%)%n", legPassed, legacyQuestions.size(), (double) legPassed / legacyQuestions.size() * 100.0);
    }
}
