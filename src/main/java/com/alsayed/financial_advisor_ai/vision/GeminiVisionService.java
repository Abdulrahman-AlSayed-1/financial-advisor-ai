package com.alsayed.financial_advisor_ai.vision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
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
 * Google Gemini multimodal VisionService implementation with local disk caching and 429 quota backoff.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.vision.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiVisionService implements VisionService {

    private final org.springframework.beans.factory.ObjectProvider<ChatModel> chatModelProvider;

    @Value("${app.vision.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${app.vision.cache.directory:.vision-cache}")
    private String cacheDir;

    @Value("${app.vision.model:${GEMINI_MODEL:gemini-2.5-flash-lite}}")
    private String configuredModel;

    private final java.util.concurrent.atomic.AtomicBoolean quotaExhausted = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Autowired
    public GeminiVisionService(@Qualifier("googleGenAiChatModel") org.springframework.beans.factory.ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public VisionResponse interpret(VisionRequest request) {
        if (request.primaryImageData() == null || request.primaryImageData().length == 0) {
            return VisionResponse.failure("Empty image data in VisionRequest", configuredModel, 0);
        }

        // 1. Check local disk cache first before making any external API call
        String cacheKey = computeCacheKey(request);
        Object pageMeta = request.contextMetadata() != null ? request.contextMetadata().get("page") : "?";
        log.info("Vision cache check for task={} [page={}] (hash: {})", request.taskType(), pageMeta, cacheKey);
        if (cacheEnabled) {
            String cached = readFromCache(cacheKey, request);
            if (cached != null && !cached.isBlank()) {
                log.info("Vision cache HIT for task {} [page={}]", request.taskType(), pageMeta);
                return VisionResponse.success(cached, "gemini-cached", 0, true);
            }
        }

        // 2. If quota is known to be exhausted, bypass external call instantly to avoid 30s delays
        if (quotaExhausted.get()) {
            log.debug("Gemini Vision quota is exhausted for this session. Instantly using deterministic PyMuPDF fallback.");
            return VisionResponse.failure("Gemini daily quota exhausted (circuit breaker active)", configuredModel, 0);
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("Gemini ChatModel bean is not available in ApplicationContext. " +
                    "To enable Gemini Vision, set GOOGLE_GENAI_API_KEY in your .env file or environment.");
            return VisionResponse.failure("Gemini ChatModel not available (missing GOOGLE_GENAI_API_KEY)", configuredModel, 0);
        }

        int maxAttempts = 3;
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                log.info("Invoking Gemini Vision API ({}) for task {} [page={}] attempt {}/{} (image size: {} bytes)...",
                        configuredModel, request.taskType(), pageMeta, attempt, maxAttempts, request.primaryImageData().length);

                org.springframework.util.MimeType mimeType = MimeTypeUtils.parseMimeType(request.mimeType());
                Media primaryMedia = Media.builder()
                        .mimeType(mimeType)
                        .data(request.primaryImageData())
                        .build();

                String responseContent = ChatClient.create(chatModel)
                        .prompt()
                        .user(u -> {
                            u.text(request.prompt());
                            u.media(primaryMedia);
                            if (request.contextOverviewImageData() != null && request.contextOverviewImageData().length > 0) {
                                Media contextMedia = Media.builder()
                                        .mimeType(MimeTypeUtils.IMAGE_PNG)
                                        .data(request.contextOverviewImageData())
                                        .build();
                                u.media(contextMedia);
                            }
                        })
                        .call()
                        .content();

                long latency = System.currentTimeMillis() - start;
                log.info("Gemini Vision call completed in {}ms [page={}]", latency, pageMeta);

                if (cacheEnabled && responseContent != null && !responseContent.isBlank()) {
                    writeToCache(cacheKey, responseContent);
                }

                return VisionResponse.success(responseContent, configuredModel, latency, false);
            } catch (Throwable t) {
                lastError = t;
                long latency = System.currentTimeMillis() - start;
                String rootMsg = (t.getCause() != null) ? t.getCause().getMessage() : t.getMessage();
                String errDetail = rootMsg != null ? rootMsg : t.getClass().getSimpleName();

                boolean isDailyQuota = errDetail.contains("FreeTier")
                        || errDetail.contains("Quota exceeded for metric")
                        || errDetail.contains("GenerateRequestsPerDay");

                if (isDailyQuota) {
                    quotaExhausted.set(true);
                    log.warn("Gemini Vision daily free tier quota exhausted. Circuit breaker activated — instantly using deterministic PyMuPDF fallback.");
                    return VisionResponse.failure(errDetail, configuredModel, latency);
                }

                boolean isRateLimit = errDetail.contains("429")
                        || errDetail.contains("RESOURCE_EXHAUSTED")
                        || errDetail.toLowerCase().contains("rate limit")
                        || errDetail.toLowerCase().contains("quota");

                if (isRateLimit && attempt < maxAttempts) {
                    long backoffMs = 2000L * attempt;
                    log.warn("Gemini Vision rate-limited on attempt {}/{} for page {}. Backing off for {}ms...", attempt, maxAttempts, pageMeta, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (attempt == maxAttempts) {
                    if (isRateLimit) {
                        quotaExhausted.set(true);
                    }
                    log.warn("Gemini Vision call failed after {} attempts [page={}]: {}", maxAttempts, pageMeta, errDetail);
                    return VisionResponse.failure(errDetail, configuredModel, latency);
                }
            }
        }

        String fallbackErr = lastError != null ? lastError.getMessage() : "Unknown error";
        return VisionResponse.failure(fallbackErr, configuredModel, 0);
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

    private final java.util.Map<String, String> secondaryCacheIndex = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean indexInitialized = new java.util.concurrent.atomic.AtomicBoolean(false);

    private String readFromCache(String key, VisionRequest request) {
        try {
            Path file = Path.of(cacheDir, key + ".json");
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Cache read error for {}: {}", key, e.getMessage());
        }

        return findInSecondaryCache(request);
    }

    private String findInSecondaryCache(VisionRequest request) {
        ensureIndexInitialized();
        if (request == null) return null;

        String prompt = request.prompt() != null ? request.prompt().toLowerCase() : "";
        String normPrompt = prompt.replaceAll("[^a-z0-9]", "");

        for (var entry : secondaryCacheIndex.entrySet()) {
            String key = entry.getKey();
            if (!key.isBlank() && key.length() >= 6 && (normPrompt.contains(key) || (key.length() > 12 && key.contains(normPrompt)))) {
                log.info("Secondary vision cache matched figure title key '{}'", key);
                return entry.getValue();
            }
        }

        return null;
    }

    private void ensureIndexInitialized() {
        if (indexInitialized.compareAndSet(false, true)) {
            try {
                Path dir = Path.of(cacheDir);
                if (!Files.exists(dir)) return;
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                        try {
                            String raw = Files.readString(p, StandardCharsets.UTF_8).trim();
                            String clean = raw;
                            if (clean.startsWith("```json")) clean = clean.substring(7);
                            else if (clean.startsWith("```")) clean = clean.substring(3);
                            if (clean.endsWith("```")) clean = clean.substring(0, clean.length() - 3);
                            clean = clean.trim();

                            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(clean);
                            String figTitle = node.has("figure_title") ? node.get("figure_title").asText() : "";
                            String tblTitle = node.has("table_title") ? node.get("table_title").asText() : "";
                            String title = !figTitle.isBlank() ? figTitle : tblTitle;
                            if (!title.isBlank()) {
                                String norm = title.toLowerCase().replaceAll("[^a-z0-9]", "");
                                if (!norm.equals("thebeat") && !norm.equals("beat") && !norm.contains("thebeatoctober") && norm.length() >= 8) {
                                    final String finalClean = clean;
                                    secondaryCacheIndex.compute(norm, (k, existing) -> {
                                        if (existing == null) return finalClean;
                                        return finalClean.length() > existing.length() ? finalClean : existing;
                                    });
                                }
                            }
                        } catch (Exception ignored) {}
                    });
                }
                log.info("GeminiVisionService initialized secondary cache index with {} entries", secondaryCacheIndex.size());
            } catch (Exception e) {
                log.warn("Failed to initialize secondary vision cache index: {}", e.getMessage());
            }
        }
    }

    private void writeToCache(String key, String content) {
        try {
            Path dir = Path.of(cacheDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".json"), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Cache write error for {}: {}", key, e.getMessage());
        }
    }
}
