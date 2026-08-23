package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.CanonicalMetric;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import com.alsayed.financial_advisor_ai.retrieval.QueryAnalyzer;
import com.alsayed.financial_advisor_ai.retrieval.QuerySignals;
import com.alsayed.financial_advisor_ai.service.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class QueryIntentAndRetrievalGeneralizationTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Autowired
    private IngestionService ingestionService;

    @BeforeEach
    void setup() throws Exception {
        ingestionService.awaitIngestionComplete(60, TimeUnit.SECONDS);
    }

    private record RetrievalTrace(
            String query,
            QuerySignals signals,
            CanonicalMetric normalizedMetric,
            Set<Integer> targetPages,
            int retrievedRank,
            boolean isHit
    ) {}

    private RetrievalTrace evaluateQuery(String query, Set<Integer> targetPages) {
        QuerySignals signals = queryAnalyzer.analyze(query);
        List<Document> docs = hybridRetrievalService.search(query, 10);

        int rank = -1;
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            int p = (Integer) d.getMetadata().getOrDefault("page_number", 0);
            if (targetPages.contains(p) && rank == -1) {
                rank = i + 1;
            }
        }

        boolean hit = rank > 0 && rank <= 5;
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("QUESTION: %s\n", query);
        System.out.printf("PARSED INTENT: entities=%s, metric=%s, period=%s, op=%s\n",
                signals.getEntities(), signals.getMetric(), signals.getPeriod(), signals.getOperation());
        System.out.printf("NORMALIZED INTENT: canonicalMetric=%s, tableTypes=%s\n",
                signals.getCanonicalMetric(), signals.getTableTypes());
        System.out.printf("TARGET PAGES: %s | RELEVANT RESULT RANK: %s | RESULT: %s\n",
                targetPages, (rank > 0 ? String.valueOf(rank) : "NOT IN TOP 10"), (hit ? "PASS" : "FAIL"));

        return new RetrievalTrace(query, signals, signals.getCanonicalMetric(), targetPages, rank, hit);
    }

    @Test
    @DisplayName("1. Existing Successful Queries Regression Test")
    void testExistingSuccessfulQueries() {
        System.out.println("\n=== 1. EXISTING SUCCESSFUL QUERIES REGRESSION TEST ===");

        RetrievalTrace t1 = evaluateQuery("What was the 10-Year Treasury Yield as of September 30, 2024?", Set.of(22, 26));
        assertTrue(t1.isHit(), "Treasury yield must be retrieved in Top 5 (Rank: " + t1.retrievedRank() + ")");

        RetrievalTrace t2 = evaluateQuery("Which S&P 500 sector had the highest YTD return as of September 30, 2024?", Set.of(36, 38));
        assertTrue(t2.isHit(), "Sector return must be retrieved in Top 5 (Rank: " + t2.retrievedRank() + ")");

        RetrievalTrace t3 = evaluateQuery("What was the return of the Japanese Yen YTD as of September 30, 2024?", Set.of(42));
        assertTrue(t3.isHit(), "Japanese Yen return must be retrieved in Top 5 (Rank: " + t3.retrievedRank() + ")");

        RetrievalTrace t4 = evaluateQuery("What was the Emerging Markets bond spread as of September 30, 2024?", Set.of(24, 29));
        assertTrue(t4.isHit(), "Emerging Markets spread must be retrieved in Top 5 (Rank: " + t4.retrievedRank() + ")");
    }

    @Test
    @DisplayName("2. Previously Failing Queries Resolution Test")
    void testPreviouslyFailingQueries() {
        System.out.println("\n=== 2. PREVIOUSLY FAILING QUERIES RESOLUTION TEST ===");

        // Policy Rate query -> Page 21 (Market Expectations for Future Central Bank Rates)
        RetrievalTrace t1 = evaluateQuery("Which country had the highest policy rate as of September 2024?", Set.of(21));
        assertEquals(CanonicalMetric.POLICY_RATE, t1.normalizedMetric());
        assertTrue(t1.isHit(), "Policy rate target (Page 21) must be retrieved in Top 5 (Rank: " + t1.retrievedRank() + ")");

        // ETF Inflows query -> Page 49 (Top 10 ETF Categories by Monthly Flows)
        RetrievalTrace t2 = evaluateQuery("Which ETF category had the highest 12-month inflows?", Set.of(49));
        assertEquals(CanonicalMetric.INFLOW, t2.normalizedMetric());
        assertFalse(t2.signals().getEntities().contains("which etf"), "Interrogative 'which' must NOT contaminate entity extraction");
        assertTrue(t2.isHit(), "ETF inflow target (Page 49) must be retrieved in Top 5 (Rank: " + t2.retrievedRank() + ")");

        // Default Rate query -> Page 26 (Annual Default Rates)
        RetrievalTrace t3 = evaluateQuery("Which market had the highest default rate?", Set.of(26, 28));
        assertEquals(CanonicalMetric.DEFAULT_RATE, t3.normalizedMetric());
        assertTrue(t3.isHit(), "Default rate target (Page 26) must be retrieved in Top 5 (Rank: " + t3.retrievedRank() + ")");
    }

    @Test
    @DisplayName("3. Paraphrase Generalization Suite across Formulations")
    void testParaphraseGeneralization() {
        System.out.println("\n=== 3. PARAPHRASE GENERALIZATION SUITE ===");

        // Policy rate paraphrases
        List<String> policyQueries = List.of(
                "Which country had the highest policy rate?",
                "Which country had the highest central bank rate?",
                "Which country had the highest interest rate?",
                "What country had the highest policy rate?"
        );
        for (String q : policyQueries) {
            RetrievalTrace t = evaluateQuery(q, Set.of(21));
            assertEquals(CanonicalMetric.POLICY_RATE, t.normalizedMetric());
            assertTrue(t.isHit(), "Paraphrase '" + q + "' must retrieve Page 21 in Top 5 (Rank: " + t.retrievedRank() + ")");
        }

        // ETF Inflows paraphrases
        List<String> etfQueries = List.of(
                "Which ETF category had the highest 12-month inflows?",
                "Which ETF category received the most inflows over 12 months?",
                "What ETF category had the largest 12-month fund inflows?"
        );
        for (String q : etfQueries) {
            RetrievalTrace t = evaluateQuery(q, Set.of(49));
            assertTrue(t.normalizedMetric() == CanonicalMetric.INFLOW || t.normalizedMetric() == CanonicalMetric.FLOW);
            assertTrue(t.isHit(), "Paraphrase '" + q + "' must retrieve Page 49 in Top 5 (Rank: " + t.retrievedRank() + ")");
        }

        // Default Rate paraphrases
        List<String> defaultQueries = List.of(
                "Which market had the highest default rate?",
                "What market recorded the highest default rate?",
                "Which market had the greatest default rate?"
        );
        for (String q : defaultQueries) {
            RetrievalTrace t = evaluateQuery(q, Set.of(26, 28));
            assertEquals(CanonicalMetric.DEFAULT_RATE, t.normalizedMetric());
            assertTrue(t.isHit(), "Paraphrase '" + q + "' must retrieve Page 26/28 in Top 5 (Rank: " + t.retrievedRank() + ")");
        }
    }

    @Test
    @DisplayName("4. Absent Evidence Test: Verifies Proper Context When Evidence is Genuinely Absent")
    void testAbsentEvidenceContext() {
        System.out.println("\n=== 4. ABSENT EVIDENCE CONTEXT TEST ===");

        String absentQuery = "What was the cryptocurrency portfolio allocation as of September 30, 2024?";
        List<Document> docs = hybridRetrievalService.search(absentQuery, 5);

        assertNotNull(docs);
        // Verify that no chunk falsely claims to contain cryptocurrency allocation
        for (Document d : docs) {
            String text = d.getText().toLowerCase();
            assertFalse(text.contains("cryptocurrency") || text.contains("bitcoin"),
                    "Retrieved documents must not contain fabricated cryptocurrency data");
        }
    }
}
