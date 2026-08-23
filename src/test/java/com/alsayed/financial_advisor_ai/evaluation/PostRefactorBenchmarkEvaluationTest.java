package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvidenceGroup;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.EvidenceBasedRetrievalEvaluator;
import com.alsayed.financial_advisor_ai.evaluation.rag.RagSystemClient;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
public class PostRefactorBenchmarkEvaluationTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private EvidenceBasedRetrievalEvaluator evidenceEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    record QueryEvalResult(
            int id,
            int page,
            String question,
            String goldKeyword,
            int rank,
            boolean hitAt1,
            boolean hitAt3,
            boolean hitAt5,
            boolean hitAt10,
            double reciprocalRank,
            String topRetrievedSection,
            String topRetrievedContent
    ) {}

    @Test
    @DisplayName("Evaluate BASELINE_V1 Post-Architectural Refactor")
    void evaluateBaselineV1PostRefactor() throws Exception {
        List<Map<String, Object>> queries = List.of(
                Map.of("id", 1, "page", 5, "q", "What was the headline CPI inflation rate for August 2024 and how did core inflation behave?", "gold", "2.5"),
                Map.of("id", 2, "page", 9, "q", "What are the consensus sales growth expectations for S&P 500 companies in 2025?", "gold", "5.5"),
                Map.of("id", 3, "page", 11, "q", "What was the Fed funds target range following the 50 bps rate cut in September 2024?", "gold", "4.75"),
                Map.of("id", 4, "page", 14, "q", "What is the team's allocation view and commentary on Global Real Estate?", "gold", "underweight"),
                Map.of("id", 5, "page", 16, "q", "What is the team's asset allocation stance on Broad Commodities and why?", "gold", "neutral"),
                Map.of("id", 6, "page", 18, "q", "Which asset classes does the team have High Conviction Overweight allocation on?", "gold", "european banks"),
                Map.of("id", 7, "page", 21, "q", "What was the 10-year Treasury yield and 1-Year Total Return as of September 30, 2024?", "gold", "3.78"),
                Map.of("id", 8, "page", 22, "q", "What was the 2-Year U.S. Treasury yield and its 1-month total return as of September 30, 2024?", "gold", "3.64"),
                Map.of("id", 9, "page", 24, "q", "What was the Yield to Worst for BB/B rated Leveraged Loans as of September 30, 2024?", "gold", "8.78"),
                Map.of("id", 10, "page", 28, "q", "What were the AAA Municipal-to-Treasury yield ratios for 5-year, 10-year, and 30-year maturities as of September 30, 2024?", "gold", "66"),
                Map.of("id", 11, "page", 30, "q", "What was the Yield To Worst and Spread for Investment Grade Sovereign EMD as of September 30, 2024?", "gold", "5.02"),
                Map.of("id", 12, "page", 32, "q", "What was the dividend yield for Large Cap Value stocks compared to Large Cap Growth stocks as of September 30, 2024?", "gold", "2.09"),
                Map.of("id", 13, "page", 34, "q", "What was the NTM P/E ratio for the S&P 500 Information Technology sector relative to its 10-year average as of September 30, 2024?", "gold", "28.5"),
                Map.of("id", 14, "page", 44, "q", "What was the 1-Year spot return of the British Pound and Euro against the USD as of September 30, 2024?", "gold", "10.02"),
                Map.of("id", 15, "page", 53, "q", "How does the report define the Bloomberg U.S. Aggregate Bond Index?", "gold", "investment grade")
        );

        List<QueryEvalResult> results = new ArrayList<>();
        int hitAt1Count = 0, hitAt3Count = 0, hitAt5Count = 0, hitAt10Count = 0;
        double sumReciprocalRank = 0.0;

        System.out.println("==================================================================================");
        System.out.println("           POST-REFACTOR BASELINE_V1 EVALUATION RESULTS (N = 15)");
        System.out.println("==================================================================================");

        for (Map<String, Object> qMap : queries) {
            int id = (Integer) qMap.get("id");
            int page = (Integer) qMap.get("page");
            String qText = (String) qMap.get("q");
            String gold = (String) qMap.get("gold");

            List<Document> retrieved = hybridRetrievalService.search(qText, 10);

            int goldRank = -1;
            String topSection = "";
            String topContent = "";

            if (!retrieved.isEmpty()) {
                topSection = (String) retrieved.get(0).getMetadata().getOrDefault("section", "");
                topContent = retrieved.get(0).getText();
            }

            for (int r = 0; r < retrieved.size(); r++) {
                Document doc = retrieved.get(r);
                String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
                String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
                String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();
                Object pageObj = doc.getMetadata().get("page_number");
                int docPage = pageObj instanceof Number ? ((Number) pageObj).intValue() : -1;

                if (docPage == page || text.contains(gold.toLowerCase()) || sec.contains(gold.toLowerCase()) || tbl.contains(gold.toLowerCase())) {
                    goldRank = r + 1;
                    break;
                }
            }

            boolean h1 = (goldRank == 1);
            boolean h3 = (goldRank >= 1 && goldRank <= 3);
            boolean h5 = (goldRank >= 1 && goldRank <= 5);
            boolean h10 = (goldRank >= 1 && goldRank <= 10);
            double rr = (goldRank > 0) ? (1.0 / goldRank) : 0.0;

            if (h1) hitAt1Count++;
            if (h3) hitAt3Count++;
            if (h5) hitAt5Count++;
            if (h10) hitAt10Count++;
            sumReciprocalRank += rr;

            results.add(new QueryEvalResult(id, page, qText, gold, goldRank, h1, h3, h5, h10, rr, topSection, topContent));

            System.out.printf("Q%02d [P%02d] Rank: %s | Question: %s%n",
                    id, page, (goldRank > 0 ? "#" + goldRank : "MISS"), qText);
        }

        double r1 = (double) hitAt1Count / queries.size();
        double r3 = (double) hitAt3Count / queries.size();
        double r5 = (double) hitAt5Count / queries.size();
        double r10 = (double) hitAt10Count / queries.size();
        double mrr = sumReciprocalRank / queries.size();

        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("BASELINE_V1 SUMMARY: Recall@1=%.3f | Recall@3=%.3f | Recall@5=%.3f | Recall@10=%.3f | MRR=%.3f%n",
                r1, r3, r5, r10, mrr);
        System.out.println("==================================================================================");

        Map<String, Object> outputReport = Map.of(
                "benchmark_name", "BASELINE_V1_POST_REFACTOR",
                "total_queries", queries.size(),
                "metrics", Map.of(
                        "recall_at_1", r1,
                        "recall_at_3", r3,
                        "recall_at_5", r5,
                        "recall_at_10", r10,
                        "mrr", mrr
                ),
                "queries", results
        );

        new File("evaluation/reports").mkdirs();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File("evaluation/reports/baseline_v1_post_refactor.json"), outputReport);
    }

    private EvaluationQuestion createQuestion(String id, String text, List<String> requiredTerms) {
        EvaluationQuestion q = new EvaluationQuestion();
        q.setId(id);
        q.setQuestionText(text);
        q.setEvidenceGroups(List.of(new EvidenceGroup(requiredTerms)));
        return q;
    }

    @Test
    @DisplayName("Evaluate Legacy Regression Benchmark (Q1–Q11) Post-Architectural Refactor")
    void evaluateLegacyQ1ToQ11PostRefactor() throws Exception {
        List<EvaluationQuestion> questions = List.of(
                createQuestion("Q1", "What was the headline CPI inflation rate for August 2024 and how did core inflation behave?", List.of("2.5")),
                createQuestion("Q2", "What are the consensus sales growth expectations for S&P 500 companies in 2025?", List.of("5.5")),
                createQuestion("Q3", "What was the Fed funds target range following the 50 bps rate cut in September 2024?", List.of("4.75", "5.00")),
                createQuestion("Q4", "What is the team's allocation view and commentary on Global Real Estate?", List.of("Underweight")),
                createQuestion("Q5", "What is the team's asset allocation stance on Broad Commodities and why?", List.of("Neutral")),
                createQuestion("Q6", "What were the YTD total returns for the best and worst performing S&P 500 sectors through September 2024?", List.of("Utilities", "30.8", "Energy", "6.2")),
                createQuestion("Q7", "What was the 10-year historical return correlation between the S&P 500 and the Bloomberg US Aggregate?", List.of("0.24", "correlation")),
                createQuestion("Q8", "What was the 2-Year U.S. Treasury yield and its 1-month total return as of September 30, 2024?", List.of("3.64", "1.19")),
                createQuestion("Q9", "What was the trailing 12-month default rate for US speculative grade corporate bonds as of September 2024?", List.of("4.69", "default")),
                createQuestion("Q10", "What were the AAA Municipal-to-Treasury yield ratios for 5-year, 10-year, and 30-year maturities as of September 30, 2024?", List.of("66", "70", "84")),
                createQuestion("Q11", "How does the report define the Bloomberg U.S. Aggregate Bond Index?", List.of("investment grade", "taxable"))
        );

        int hitCount = 0;
        System.out.println("==================================================================================");
        System.out.println("           POST-REFACTOR LEGACY BENCHMARK (Q1–Q11) EVALUATION");
        System.out.println("==================================================================================");

        for (EvaluationQuestion q : questions) {
            List<Document> retrieved = hybridRetrievalService.search(q.getQuestionText(), 10);
            var evalResult = evidenceEvaluator.evaluateEvidence(q, retrieved, 10);

            boolean pass = evalResult.matched();
            if (pass) hitCount++;

            System.out.printf("[%s] %s -> %s (Hit@10: %b)%n",
                    q.getId(), pass ? "PASS" : "FAIL", q.getQuestionText(), pass);
        }

        double hitRate = (double) hitCount / questions.size();
        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("LEGACY BENCHMARK SUMMARY: Passed %d / %d (%.1f%%)%n",
                hitCount, questions.size(), hitRate * 100.0);
        System.out.println("==================================================================================");
    }
}
