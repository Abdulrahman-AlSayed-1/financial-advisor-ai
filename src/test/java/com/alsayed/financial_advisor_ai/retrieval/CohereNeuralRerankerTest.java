package com.alsayed.financial_advisor_ai.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CohereNeuralRerankerTest {

    @Test
    @DisplayName("Verify Cohere Neural Reranker accurately ranks financial sectors")
    void testCohereRerankerLive() {
        String apiKey = System.getenv("COHERE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("COHERE_API_KEY", "");
        }
        if (apiKey.isBlank()) {
            System.out.println("COHERE_API_KEY not set in environment. Skipping live Cohere API test.");
            return;
        }

        CohereNeuralReranker reranker = new CohereNeuralReranker(
                RestClient.builder(),
                apiKey,
                "https://api.cohere.com/v1/rerank",
                "rerank-v3.5",
                true
        );

        assertTrue(reranker.isConfigured(), "Reranker should be configured with key");

        String query = "Which S&P 500 sectors delivered the highest YTD total return?";

        Document doc1 = new Document("The Energy sector lagged behind with a modest 6.2% YTD return.");
        Document doc2 = new Document("S&P 500 Information Technology delivered an impressive 30.31% YTD total return.");
        Document doc3 = new Document("The Utilities sector led all sectors with a 30.63% YTD total return as of September 30, 2024.");
        Document doc4 = new Document("Treasury yield curves flattened following the Federal Reserve 50bps rate cut.");

        List<Document> candidates = List.of(doc1, doc2, doc3, doc4);

        List<com.alsayed.financial_advisor_ai.retrieval.rerank.RerankedDocument> reranked = reranker.rerank(query, candidates);

        assertNotNull(reranked);
        assertEquals(4, reranked.size());

        System.out.println("=== COHERE NEURAL RERANKER RESULTS ===");
        for (int i = 0; i < reranked.size(); i++) {
            com.alsayed.financial_advisor_ai.retrieval.rerank.RerankedDocument rd = reranked.get(i);
            System.out.printf("Rank %d (Score: %.4f): %s%n",
                    i + 1, rd.score(), rd.document().getText());
        }

        // Verify that top 2 are Utilities and Information Technology (the two highest return sectors)
        String top1Text = reranked.get(0).document().getText();
        String top2Text = reranked.get(1).document().getText();

        assertTrue(top1Text.contains("Utilities") || top1Text.contains("Information Technology"));
        assertTrue(top2Text.contains("Utilities") || top2Text.contains("Information Technology"));

        // Verify Treasury bonds is ranked lowest
        String lowestText = reranked.get(3).document().getText();
        assertTrue(lowestText.contains("Treasury"));
    }
}
