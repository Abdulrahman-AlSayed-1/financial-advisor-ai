package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class RetrievalAblationStudyTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    record AblationQuery(
            String id,
            String question,
            Set<Integer> goldPages
    ) {}

    @Test
    @DisplayName("Run 10-Question Retrieval Ablation Study: Pure Vector vs Hybrid vs Production")
    void runAblationStudy() {
        List<AblationQuery> queries = List.of(
                new AblationQuery("Q1", "What was the YTD return for Master Limited Partnerships (MLP) through August 31, 2024?", Set.of(47)),
                new AblationQuery("Q2", "What was the sector weight of Information Technology in the S&P 500 as of September 30, 2024?", Set.of(39)),
                new AblationQuery("Q3", "What was the 1-month return for the Russell 2000 Index?", Set.of(36, 38, 40)),
                new AblationQuery("Q4", "What was the yield to worst for U.S. High Yield Corporate Bonds as of September 30, 2024?", Set.of(22, 24)),
                new AblationQuery("Q5", "Which currency had the lowest spot return against the USD?", Set.of(42, 45)),
                new AblationQuery("Q6", "What was the default rate for European High Yield in 2023?", Set.of(26, 28)),
                new AblationQuery("Q7", "What was the 10-year annualized return for Emerging Markets Equity?", Set.of(40, 41)),
                new AblationQuery("Q8", "What was the correlation between U.S. Core Bonds and Large Cap Growth Equities?", Set.of(48)),
                new AblationQuery("Q9", "What was the policy rate expectation for the European Central Bank by December 2025?", Set.of(21)),
                new AblationQuery("Q10", "What were the net flows for Large Blend ETFs over the past 12 months?", Set.of(49))
        );

        System.out.println("========================================================================================================================");
        System.out.println("RETRIEVAL ABLATION BENCHMARK: PURE VECTOR vs HYBRID PRODUCTION (Gold Page Hit in Top 10)");
        System.out.println("========================================================================================================================");
        System.out.printf("%-4s | %-65s | %-12s | %-12s | %-15s\n", "ID", "Question", "Pure Vector", "Hybrid", "Result");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");

        int pureVectorHits = 0;
        int hybridHits = 0;

        for (AblationQuery q : queries) {
            // A. Pure Vector Search (Raw question embedding, Top 10)
            List<Document> vectorResults = vectorStore.similaritySearch(SearchRequest.builder().query(q.question()).topK(10).build());
            int vectorRank = findFirstPageRank(vectorResults, q.goldPages());

            // B. Hybrid Retrieval (Production pipeline, Top 10)
            List<Document> hybridResults = hybridRetrievalService.search(q.question(), 10);
            int hybridRank = findFirstPageRank(hybridResults, q.goldPages());

            if (vectorRank > 0 && vectorRank <= 10) pureVectorHits++;
            if (hybridRank > 0 && hybridRank <= 10) hybridHits++;

            String vRankStr = vectorRank > 0 ? "Rank #" + vectorRank : "MISS (>10)";
            String hRankStr = hybridRank > 0 ? "Rank #" + hybridRank : "MISS (>10)";
            String delta = (hybridRank > 0 && vectorRank > 0)
                    ? (hybridRank < vectorRank ? "Hybrid +" + (vectorRank - hybridRank) : (hybridRank > vectorRank ? "Vector +" + (hybridRank - vectorRank) : "TIE (#" + hybridRank + ")"))
                    : (hybridRank > 0 ? "Hybrid ONLY" : (vectorRank > 0 ? "Vector ONLY" : "BOTH MISS"));

            System.out.printf("%-4s | %-65s | %-12s | %-12s | %-15s\n",
                    q.id(),
                    q.question().length() > 65 ? q.question().substring(0, 62) + "..." : q.question(),
                    vRankStr,
                    hRankStr,
                    delta);
        }

        System.out.println("========================================================================================================================");
        System.out.printf("SUMMARY: Pure Vector Top-10 Recall: %d/10 (%.1f%%) | Hybrid Production Top-10 Recall: %d/10 (%.1f%%)\n",
                pureVectorHits, (pureVectorHits * 10.0), hybridHits, (hybridHits * 10.0));
        System.out.println("========================================================================================================================");
    }

    private int findFirstPageRank(List<Document> results, Set<Integer> targetPages) {
        if (results == null) return -1;
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            int page = (Integer) doc.getMetadata().getOrDefault("page_number", 0);
            if (targetPages.contains(page)) {
                return i + 1;
            }
        }
        return -1;
    }
}
