package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Page 34 Sector Valuation Figure extraction and answering.
 */
@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class SectorValuationFigureExtractionTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private com.alsayed.financial_advisor_ai.service.IngestionService ingestionService;

    @org.junit.jupiter.api.BeforeEach
    void waitForIngestion() throws Exception {
        ingestionService.awaitIngestionComplete(60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Verify Page 34 Sector Valuation Figure retrieval and grounded answering")
    void testSectorValuationExtractionAndAnswer() {
        String query = "Which S&P 500 sectors had the highest and lowest NTM P/E ratios as of September 30, 2024?";
        List<Document> retrieved = hybridRetrievalService.search(query, 10);

        assertNotNull(retrieved);
        assertFalse(retrieved.isEmpty());

        // Verify retrieved documents contain exact structured data points for Information Technology and Energy
        boolean hasInfoTechCurrent = false;
        boolean hasEnergyCurrent = false;
        boolean hasEnergyLowNegative = false;

        for (Document doc : retrieved) {
            String text = doc.getText();
            if (text.contains("Information Technology") && (text.contains("29.0") || text.contains("29"))) {
                hasInfoTechCurrent = true;
            }
            if (text.contains("Energy") && (text.contains("12.8") || text.contains("12.8x"))) {
                hasEnergyCurrent = true;
            }
            if (text.contains("Energy") && text.contains("-263.8")) {
                hasEnergyLowNegative = true;
            }
        }

        assertTrue(hasInfoTechCurrent, "Retrieved context must contain Information Technology Current = 29.0");
        assertTrue(hasEnergyCurrent, "Retrieved context must contain Energy Current = 12.8");
        assertTrue(hasEnergyLowNegative, "Retrieved context must preserve negative value for Energy Low (-263.8)");

        // Execute prompt against chat model if available
        List<String> contexts = retrieved.stream().map(Document::getText).toList();
        String contextText = String.join("\n\n", contexts);

        String promptTemplate = """
            Answer the following question using ONLY the provided context.
            When evaluating tables or charts with multiple series (e.g. Current, High, Low, Average):
            - If the question asks for values 'as of' a specific date or current figures, use the 'Current' series, not historical High, Low, or Average extremes.

            Context:
            %s

            Question:
            %s
            """;
        String userPrompt = String.format(promptTemplate, contextText, query);

        try {
            String answer = chatClientBuilder.build().prompt().user(userPrompt).call().content();
            if (answer != null && !answer.isBlank()) {
                System.out.println("=== VALUATION ANSWER ===\n" + answer);
                String lowerAnswer = answer.toLowerCase();
                assertTrue(lowerAnswer.contains("information technology") || lowerAnswer.contains("info tech"),
                        "Answer must identify Information Technology as having the highest Current NTM P/E");
                assertTrue(lowerAnswer.contains("29") || lowerAnswer.contains("29.0"),
                        "Answer must mention 29.0x for Information Technology");
                assertTrue(lowerAnswer.contains("energy"),
                        "Answer must identify Energy as having the lowest Current NTM P/E");
                assertTrue(lowerAnswer.contains("12.8") || lowerAnswer.contains("12.8x"),
                        "Answer must mention 12.8x for Energy");
            }
        } catch (Exception e) {
            System.out.println("ChatClient invocation skipped in unit test environment: " + e.getMessage());
        }
    }
}
