package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.controller.ChatController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class LiveChatControllerTest {

    @Autowired
    private ChatController chatController;

    @Test
    void testTreasuryYieldResponse() {
        String prompt = "What was the current yield of the 10-Year Treasury as of September 30, 2024?";
        System.out.println("Prompt: " + prompt);
        String response = chatController.chat(prompt);
        System.out.println("\n=== LLM RESPONSE ===");
        System.out.println(response);
        System.out.println("====================\n");
    }
}
