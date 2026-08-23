package com.alsayed.financial_advisor_ai.vision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Local Ollama multimodal VisionService implementation.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.vision.provider", havingValue = "ollama")
public class OllamaVisionService implements VisionService {

    private final ChatModel chatModel;

    @Value("${app.vision.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${app.vision.cache.directory:.vision-cache}")
    private String cacheDir;

    @Value("${app.vision.model:llama3.2-vision}")
    private String configuredModel;

    @Autowired
    public OllamaVisionService(@Qualifier("ollamaChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public VisionResponse interpret(VisionRequest request) {
        if (request.primaryImageData() == null || request.primaryImageData().length == 0) {
            return VisionResponse.failure("Empty image data in VisionRequest", "ollama", 0);
        }

        String cacheKey = computeCacheKey(request);
        if (cacheEnabled) {
            String cached = readFromCache(cacheKey);
            if (cached != null) {
                log.info("Ollama Vision cache HIT for task {}", request.taskType());
                return VisionResponse.success(cached, "ollama-cached", 0, true);
            }
        }

        long start = System.currentTimeMillis();
        try {
            log.info("Invoking Ollama Vision API for task {}...", request.taskType());
            String responseContent = ChatClient.create(chatModel)
                    .prompt()
                    .user(u -> {
                        u.text(request.prompt());
                        u.media(MimeTypeUtils.parseMimeType(request.mimeType()), new org.springframework.core.io.ByteArrayResource(request.primaryImageData()));
                    })
                    .call()
                    .content();

            long latency = System.currentTimeMillis() - start;
            log.info("Ollama Vision call completed in {}ms", latency);

            if (cacheEnabled && responseContent != null && !responseContent.isBlank()) {
                writeToCache(cacheKey, responseContent);
            }

            return VisionResponse.success(responseContent, configuredModel, latency, false);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Ollama Vision call failed after {}ms: {}", latency, e.getMessage(), e);
            return VisionResponse.failure(e.getMessage(), configuredModel, latency);
        }
    }

    private String computeCacheKey(VisionRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.primaryImageData());
            digest.update(request.prompt().getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(request.prompt().hashCode() ^ request.primaryImageData().length);
        }
    }

    private String readFromCache(String key) {
        try {
            Path file = Path.of(cacheDir, key + ".json");
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Cache read error: {}", e.getMessage());
        }
        return null;
    }

    private void writeToCache(String key, String content) {
        try {
            Path dir = Path.of(cacheDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".json"), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Cache write error: {}", e.getMessage());
        }
    }
}
