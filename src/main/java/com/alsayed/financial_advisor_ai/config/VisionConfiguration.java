package com.alsayed.financial_advisor_ai.config;

import com.alsayed.financial_advisor_ai.vision.VisionService;
import com.google.genai.Client;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for VisionService and explicit Google GenAI ChatModel bean creation.
 */
@Slf4j
@Configuration
public class VisionConfiguration {

    @Bean(name = "googleGenAiChatModel")
    public GoogleGenAiChatModel googleGenAiChatModel(
            @Value("${spring.ai.google.genai.api-key:${spring.ai.google.gemini.api-key:${GOOGLE_GENAI_API_KEY:${GOOGLE_GEMINI_API_KEY:${GEMINI_API_KEY:}}}}}") String apiKey,
            @Value("${app.vision.model:${GEMINI_MODEL:gemini-2.5-flash-lite}}") String model) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.info("Google GenAI API key is not configured. Set GOOGLE_GENAI_API_KEY in .env to enable Gemini Vision.");
            return null;
        }
        log.info("Initializing GoogleGenAiChatModel for Vision with model '{}'", model);
        Client client = Client.builder()
                .apiKey(apiKey.trim())
                .vertexAI(false)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(model).build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(VisionService.class)
    public VisionService noOpVisionService() {
        log.warn("No active VisionService provider configured. Multimodal visual parsing is disabled.");
        return request -> com.alsayed.financial_advisor_ai.vision.VisionResponse.failure(
                "No VisionService provider configured", "none", 0);
    }
}
