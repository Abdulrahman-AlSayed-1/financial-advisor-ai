package com.alsayed.financial_advisor_ai.vision;

import com.alsayed.financial_advisor_ai.config.VisionConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiveGeminiVisionTest {

    @Test
    @org.junit.jupiter.api.Tag("live-api")
    @org.junit.jupiter.api.Disabled("Quota exhausted")
    void testLiveGeminiWithRealPageImage() throws Exception {
        String apiKey = System.getenv("GOOGLE_GENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            File envFile = new File(".env");
            if (envFile.exists()) {
                for (String line : Files.readAllLines(envFile.toPath())) {
                    if (line.startsWith("GOOGLE_GENAI_API_KEY=") || line.startsWith("GEMINI_API_KEY=") || line.startsWith("GOOGLE_GEMINI_API_KEY=")) {
                        apiKey = line.split("=", 2)[1].trim();
                        break;
                    }
                }
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping live Gemini test: no API key in environment or .env");
            return;
        }

        VisionConfiguration config = new VisionConfiguration();
        GoogleGenAiChatModel model = config.googleGenAiChatModel(apiKey, "gemini-2.5-flash");
        assertNotNull(model, "Model should be created");

        ObjectProvider<ChatModel> provider = new ObjectProvider<>() {
            @Override
            public ChatModel getObject(Object... args) { return model; }
            @Override
            public ChatModel getIfAvailable() { return model; }
            @Override
            public ChatModel getIfUnique() { return model; }
            @Override
            public ChatModel getObject() { return model; }
        };

        GeminiVisionService service = new GeminiVisionService(provider);

        // Load real Page 45 image
        Path page45Img = Path.of("src/main/resources/docs/extraction_output/pages/page_45.png");
        assertTrue(Files.exists(page45Img), "Page 45 image should exist");
        byte[] imgBytes = Files.readAllBytes(page45Img);

        VisionRequest req = VisionRequest.builder()
                .primaryImageData(imgBytes)
                .mimeType("image/png")
                .taskType(VisionTaskType.FIGURE_INTERPRETATION)
                .prompt("Extract the title and main chart topic on this page in JSON with key 'title'")
                .build();

        VisionResponse resp = service.interpret(req);
        System.out.println("=== Live Gemini Real Image Response ===");
        System.out.println("Success: " + resp.isSuccess());
        System.out.println("Content: " + resp.rawContent());
        assertTrue(resp.isSuccess(), "Live Gemini call with real image should succeed");
    }
}
