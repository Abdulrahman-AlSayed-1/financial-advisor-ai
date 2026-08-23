package com.alsayed.financial_advisor_ai.evaluation;

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
public class UnseenV2GeneralizationBenchmarkTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    record UnseenQuery(
            int id,
            int targetPage,
            String category,
            String question,
            String goldEvidenceKeyword
    ) {}

    record UnseenResult(
            int id,
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
            String topRetrievedSection,
            String topRetrievedContentType
    ) {}

    @Test
    @DisplayName("Evaluate 20 Brand-New Unseen Questions (UNSEEN_V2 Benchmark)")
    void evaluateUnseenV2Benchmark() throws Exception {
        List<UnseenQuery> benchmarkQueries = List.of(
                new UnseenQuery(1, 4, "macro_narrative", "What is the team's view on U.S. economic growth and the likelihood of a recession in late 2024?", "growth"),
                new UnseenQuery(2, 6, "macro_labor", "What was the U.S. unemployment rate reported for August 2024?", "4.2"),
                new UnseenQuery(3, 8, "equity_earnings", "What was the year-over-year earnings growth rate for the S&P 500 in Q2 2024?", "earnings"),
                new UnseenQuery(4, 10, "equity_valuation", "What was the trailing 12-month P/E ratio of the S&P 500 compared to its 20-year average?", "average"),
                new UnseenQuery(5, 12, "central_banks", "What actions did the European Central Bank (ECB) take regarding policy rates in September 2024?", "rate"),
                new UnseenQuery(6, 15, "stance_equity", "What is the team's asset allocation stance on Emerging Market Equities?", "overweight"),
                new UnseenQuery(7, 17, "stance_fixed_income", "What is the team's asset allocation stance on Investment Grade Corporate Bonds?", "neutral"),
                new UnseenQuery(8, 19, "stance_duration", "What is the overall portfolio duration stance recommended by the team?", "duration"),
                new UnseenQuery(9, 23, "table_muni", "What was the yield to worst for 10-Year AAA General Obligation Municipal bonds as of September 30, 2024?", "2.8"),
                new UnseenQuery(10, 25, "table_high_yield", "What was the option-adjusted spread (OAS) for US High Yield corporate bonds as of September 30, 2024?", "spread"),
                new UnseenQuery(11, 27, "table_securitized", "What was the yield on 30-year fixed-rate Agency Mortgage-Backed Securities (MBS) as of September 30, 2024?", "mbs"),
                new UnseenQuery(12, 20, "table_global_sovereign", "What was the 10-Year German Bund yield as of September 30, 2024?", "2.12"),
                new UnseenQuery(13, 31, "table_emd", "What was the local currency sovereign debt yield for Emerging Markets as of September 30, 2024?", "local"),
                new UnseenQuery(14, 33, "table_small_cap", "What was the 1-month and YTD return of the Russell 2000 Index through September 30, 2024?", "russell 2000"),
                new UnseenQuery(15, 35, "table_intl_equity", "What was the NTM P/E ratio for MSCI Europe compared to MSCI USA as of September 30, 2024?", "europe"),
                new UnseenQuery(16, 38, "table_factors", "How did the Momentum and Value factors perform on a YTD basis through September 2024?", "momentum"),
                new UnseenQuery(17, 41, "table_sector_div", "What was the dividend yield for the S&P 500 Utilities sector as of September 30, 2024?", "utilities"),
                new UnseenQuery(18, 43, "table_commodities", "What was the 1-year total return for Gold and WTI Crude Oil as of September 30, 2024?", "gold"),
                new UnseenQuery(19, 46, "table_fx", "What was the exchange rate of the Japanese Yen per US Dollar as of September 30, 2024?", "yen"),
                new UnseenQuery(20, 54, "glossary", "How does the document define the ICE BofA US High Yield Index?", "high yield")
        );

        List<UnseenResult> results = new ArrayList<>();
        int hitAt1Count = 0, hitAt3Count = 0, hitAt5Count = 0, hitAt10Count = 0;
        double sumReciprocalRank = 0.0;

        System.out.println("==================================================================================");
        System.out.println("           UNSEEN_V2 GENERALIZATION BENCHMARK EVALUATION (N = 20)");
        System.out.println("==================================================================================");

        for (UnseenQuery q : benchmarkQueries) {
            List<Document> retrieved = hybridRetrievalService.search(q.question(), 10);

            int goldRank = -1;
            String topSection = "";
            String topContentType = "";

            if (!retrieved.isEmpty()) {
                Document topDoc = retrieved.get(0);
                topSection = (String) topDoc.getMetadata().getOrDefault("section", "");
                topContentType = (String) topDoc.getMetadata().getOrDefault("content_type", "");
            }

            for (int r = 0; r < retrieved.size(); r++) {
                Document doc = retrieved.get(r);
                String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
                String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
                String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();
                Object pageObj = doc.getMetadata().get("page_number");
                int docPage = pageObj instanceof Number ? ((Number) pageObj).intValue() : -1;

                if (docPage == q.targetPage() || text.contains(q.goldEvidenceKeyword().toLowerCase())
                        || sec.contains(q.goldEvidenceKeyword().toLowerCase()) || tbl.contains(q.goldEvidenceKeyword().toLowerCase())) {
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

            results.add(new UnseenResult(
                    q.id(), q.targetPage(), q.category(), q.question(), q.goldEvidenceKeyword(),
                    goldRank, h1, h3, h5, h10, rr, topSection, topContentType
            ));

            System.out.printf("Q%02d [%s | P%02d] Rank: %s | Question: %s%n",
                    q.id(), q.category(), q.targetPage(), (goldRank > 0 ? "#" + goldRank : "MISS"), q.question());
        }

        double r1 = (double) hitAt1Count / benchmarkQueries.size();
        double r3 = (double) hitAt3Count / benchmarkQueries.size();
        double r5 = (double) hitAt5Count / benchmarkQueries.size();
        double r10 = (double) hitAt10Count / benchmarkQueries.size();
        double mrr = sumReciprocalRank / benchmarkQueries.size();

        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("UNSEEN_V2 SUMMARY: Recall@1=%.3f | Recall@3=%.3f | Recall@5=%.3f | Recall@10=%.3f | MRR=%.3f%n",
                r1, r3, r5, r10, mrr);
        System.out.println("==================================================================================");

        Map<String, Object> outputReport = Map.of(
                "benchmark_name", "UNSEEN_V2_GENERALIZATION_CORRECTED",
                "total_queries", benchmarkQueries.size(),
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
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File("evaluation/reports/unseen_v2_corrected_report.json"), outputReport);
    }
}
