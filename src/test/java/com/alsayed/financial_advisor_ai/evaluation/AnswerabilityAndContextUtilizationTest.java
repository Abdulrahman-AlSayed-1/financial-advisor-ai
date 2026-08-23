package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.controller.ChatController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class AnswerabilityAndContextUtilizationTest {

    @Autowired
    private ChatController chatController;

    @Test
    void testDirectQuotationFrance() {
        String prompt = "France did bounce from the early summer malaise, but sectors were affected less by domestic politics, more by Chinese demand e.g., consumer, luxury have lagged. We think France will muddle through as we are looking to exit our exposure.";
        String response = chatController.chat(prompt);

        System.out.println("=========================================================================");
        System.out.println("TEST 1: DIRECT QUOTATION / STATEMENT HANDLING (France Query)");
        System.out.println("PROMPT: " + prompt);
        System.out.println("RESPONSE:\n" + response);
        System.out.println("=========================================================================");

        assertNotNull(response);
        // The model MUST NOT issue a blanket refusal ("cannot provide a comprehensive answer" / "the context does not provide")
        assertFalse(response.contains("cannot provide a comprehensive answer"),
                "Model must not refuse due to scope expansion");
        // The model should mention the key concepts from the excerpt
        assertTrue(response.toLowerCase().contains("france") || response.toLowerCase().contains("french"),
                "Response must discuss France");
        assertTrue(response.toLowerCase().contains("chinese demand") || response.toLowerCase().contains("china") || response.toLowerCase().contains("exit"),
                "Response must reflect the content of the excerpt");
    }

    @Test
    void testDirectFactInContext() {
        String prompt = "What was the 10-Year Treasury Yield as of September 30, 2024?";
        String response = chatController.chat(prompt);

        System.out.println("=========================================================================");
        System.out.println("TEST 2: DIRECT FACT IN CONTEXT (10Y Yield)");
        System.out.println("PROMPT: " + prompt);
        System.out.println("RESPONSE:\n" + response);
        System.out.println("=========================================================================");

        assertNotNull(response);
        assertTrue(response.contains("3.8") || response.contains("3.79") || response.contains("3.81"),
                "Response must provide the yield value");
        assertFalse(response.contains("not provided in the given context"),
                "Response must not abstain when direct fact exists");
    }

    @Test
    void testZeroEvidenceAbstention() {
        String prompt = "What was the quarterly revenue of Tesla in Q3 2024 according to the document?";
        String response = chatController.chat(prompt);

        System.out.println("=========================================================================");
        System.out.println("TEST 3: ZERO EVIDENCE ABSTENTION (Tesla Revenue)");
        System.out.println("PROMPT: " + prompt);
        System.out.println("RESPONSE:\n" + response);
        System.out.println("=========================================================================");

        assertNotNull(response);
        assertTrue(response.toLowerCase().contains("not provided") || response.toLowerCase().contains("not mentioned") || response.toLowerCase().contains("no information"),
                "Model must cleanly abstain when no evidence is in the document");
    }
}
