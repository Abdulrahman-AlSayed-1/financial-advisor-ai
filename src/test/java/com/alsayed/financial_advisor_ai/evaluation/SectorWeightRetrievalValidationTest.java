package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.CanonicalMetric;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import com.alsayed.financial_advisor_ai.retrieval.QueryAnalyzer;
import com.alsayed.financial_advisor_ai.retrieval.QuerySignals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class SectorWeightRetrievalValidationTest {

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("googleGenAiChatModel")
    private ChatModel chatModel;

    @Test
    @DisplayName("1. Sector Weight Query Parsing & Retrieval Trace")
    void testSectorWeightRetrieval() {
        String query = "What was the sector weight of Information Technology in the S&P 500 as of September 30, 2024?";
        
        QuerySignals signals = queryAnalyzer.analyze(query);
        System.out.println("================================================================================");
        System.out.println("SECTOR WEIGHT QUERY DIAGNOSTIC TRACE");
        System.out.println("================================================================================");
        System.out.println("Raw Query: " + query);
        System.out.println("Entities: " + signals.getEntities());
        System.out.println("Canonical Metric: " + signals.getCanonicalMetric());
        System.out.println("Table Types: " + signals.getTableTypes());

        assertEquals(CanonicalMetric.SECTOR_WEIGHT, signals.getCanonicalMetric(), "Query must be normalized to SECTOR_WEIGHT");

        List<Document> results = hybridRetrievalService.search(query, 5);
        assertNotNull(results);
        assertFalse(results.isEmpty());

        System.out.println("\nRetrieved Chunks:");
        int targetRank = -1;
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            int page = (Integer) doc.getMetadata().getOrDefault("page_number", 0);
            String title = (String) doc.getMetadata().getOrDefault("title", "");
            String panel = (String) doc.getMetadata().getOrDefault("panel", "");
            String entity = (String) doc.getMetadata().getOrDefault("entity", "");
            String val = (String) doc.getMetadata().getOrDefault("value", "");

            System.out.printf("  Rank #%d: [Page %02d] Title: '%s' | Panel: '%s' | Entity: '%s' | Value: '%s'\n",
                    (i + 1), page, title, panel, entity, val);

            if (page == 39 && targetRank == -1) {
                targetRank = i + 1;
            }
        }

        assertTrue(targetRank > 0 && targetRank <= 5, "Target Page 39 must be retrieved in Top 5 (Rank: " + targetRank + ")");
    }

    @Test
    @DisplayName("2. Paraphrase Formulations Retrieval Verification")
    void testParaphraseRetrieval() {
        List<String> queries = List.of(
                "What was the sector weight of Information Technology in the S&P 500 as of September 30, 2024?",
                "S&P 500 Information Technology sector weight",
                "S&P 500 Information Technology weight",
                "S&P 500 Information Technology sector allocation",
                "S&P 500 Information Technology index weight"
        );

        System.out.println("================================================================================");
        System.out.println("PARAPHRASE RETRIEVAL RANKS");
        System.out.println("================================================================================");

        for (String q : queries) {
            QuerySignals signals = queryAnalyzer.analyze(q);
            List<Document> results = hybridRetrievalService.search(q, 5);
            int rank = -1;
            for (int i = 0; i < results.size(); i++) {
                int page = (Integer) results.get(i).getMetadata().getOrDefault("page_number", 0);
                if (page == 39) {
                    rank = i + 1;
                    break;
                }
            }
            System.out.printf("Query: '%-80s' | Metric: %-15s | Rank: %d\n", q, signals.getCanonicalMetric(), rank);
            assertTrue(rank > 0 && rank <= 5, "Query '" + q + "' must retrieve Page 39 in Top 5 (Rank: " + rank + ")");
        }
    }

    @Test
    @DisplayName("3. End-to-End LLM Answer Verification")
    void testEndToEndAnswerGeneration() {
        String query = "What was the sector weight of Information Technology in the S&P 500 as of September 30, 2024?";
        List<Document> results = hybridRetrievalService.search(query, 5);

        StringBuilder contextBuilder = new StringBuilder();
        for (Document d : results) {
            contextBuilder.append(d.getText()).append("\n\n");
        }

        String promptText = """
                Context:
                %s
                
                Question: %s
                
                Answer the question directly based only on the provided context. State the sector weight value clearly.
                """.formatted(contextBuilder.toString(), query);

        String answer = chatModel.call(new Prompt(promptText)).getResult().getOutput().getText();
        System.out.println("================================================================================");
        System.out.println("LLM GENERATED ANSWER:");
        System.out.println("================================================================================");
        System.out.println(answer);

        assertTrue(answer.contains("32") || answer.contains("31.7"), "Answer must state the sector weight as 32% (or 31.76%)");
    }
}
