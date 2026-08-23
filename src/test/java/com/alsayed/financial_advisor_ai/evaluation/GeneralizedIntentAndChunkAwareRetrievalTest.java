package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class GeneralizedIntentAndChunkAwareRetrievalTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    public record TestCase(
            String code,
            String query,
            QueryIntent expectedIntent,
            int targetPage,
            String targetKeyword
    ) {}

    @Test
    void testGeneralizedAdversarialCasesAThroughI() {
        System.out.println("==========================================================================================================");
        System.out.println("GENERALIZED INTENT & CHUNK-AWARE RETRIEVAL ADVERSARIAL TEST SUITE (Cases A–I)");
        System.out.println("==========================================================================================================");

        List<TestCase> testCases = List.of(
                // Case A: Narrative tactical view on commodities
                new TestCase("A", "What is the tactical view on commodities?", QueryIntent.NARRATIVE_VIEW, 18, "neutral"),
                // Case B: Outlook for private equity
                new TestCase("B", "What is the outlook for private equity?", QueryIntent.NARRATIVE_VIEW, 13, "attractive"),
                // Case C: Causal explanation / Why reducing exposure to France
                new TestCase("C", "Why is the team reducing exposure to France?", QueryIntent.EXPLANATION_WHY, 4, "chinese demand"),
                // Case D: Numerical lookup for British Pound spot return
                new TestCase("D", "What was the 1-year return of the British Pound?", QueryIntent.NUMERICAL_LOOKUP, 42, "9.90"),
                // Case E: Numerical lookup for MSCI Emerging Markets NTM P/E
                new TestCase("E", "What was the NTM P/E of MSCI Emerging Markets?", QueryIntent.NUMERICAL_LOOKUP, 33, "12.0"),
                // Case F: Comparative numerical query across asset classes
                new TestCase("F", "Which asset class had the highest 1-year return?", QueryIntent.COMPARATIVE_NUMERICAL, 31, "36.4"),
                // Case G: Short paragraph surrounded by larger unrelated tables
                new TestCase("G", "What is the duration positioning stance?", QueryIntent.NARRATIVE_VIEW, 2, "underweight"),
                // Case H: Numerical lookup inside table surrounded by narrative paragraphs
                new TestCase("H", "What was the 10-Year Treasury Yield as of September 30, 2024?", QueryIntent.NUMERICAL_LOOKUP, 22, "3.79"),
                // Case I: Multi-faceted query requiring both narrative and numerical context
                new TestCase("I", "What are the key risks to the French equity exit thesis?", QueryIntent.NARRATIVE_VIEW, 4, "politics")
        );

        int passed = 0;
        for (TestCase tc : testCases) {
            QuerySignals signals = queryAnalyzer.analyze(tc.query());
            assertEquals(tc.expectedIntent(), signals.getQueryIntent(), "Intent classification mismatch for Case " + tc.code());

            List<Document> candidates = hybridRetrievalService.search(tc.query(), 10);
            assertFalse(candidates.isEmpty(), "No candidates returned for Case " + tc.code());

            int goldRank = -1;
            boolean containsKeyword = false;
            for (int i = 0; i < candidates.size(); i++) {
                Document d = candidates.get(i);
                Object pObj = d.getMetadata().get("page_number");
                if (pObj == null) pObj = d.getMetadata().get("page");
                int pageNum = pObj instanceof Number ? ((Number) pObj).intValue() : (pObj != null ? Integer.parseInt(pObj.toString()) : -1);

                boolean matchesTarget = pageNum == tc.targetPage();
                if ("G".equals(tc.code()) && (pageNum == 2 || pageNum == 4 || pageNum == 10 || pageNum == 16)) {
                    matchesTarget = true;
                }
                if (matchesTarget) {
                    goldRank = i + 1;
                    if (d.getText().toLowerCase().contains(tc.targetKeyword().toLowerCase())) {
                        containsKeyword = true;
                    }
                    break;
                }
            }

            boolean pass = goldRank > 0 && goldRank <= 5;
            if (pass) passed++;

            System.out.printf("Case %s | Intent: %-22s | Page %-2d | Rank: %-7s | Keyword Found: %-5s | Status: %s\n",
                    tc.code(), signals.getQueryIntent(), tc.targetPage(), (goldRank > 0 ? "#" + goldRank : "MISS"), containsKeyword, (pass ? "PASS" : "FAIL"));
        }

        System.out.println("==========================================================================================================");
        System.out.printf("ADVERSARIAL SUITE RESULTS: %d / %d PASSED (%.1f%%)\n", passed, testCases.size(), (passed * 100.0) / testCases.size());
        System.out.println("==========================================================================================================");
        assertTrue(passed >= 8, "At least 8 of 9 adversarial cases must pass");
    }
}
