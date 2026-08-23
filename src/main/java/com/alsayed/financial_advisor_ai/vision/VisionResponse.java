package com.alsayed.financial_advisor_ai.vision;

/**
 * Result returned by VisionService.
 */
public record VisionResponse(
    String rawContent,
    String modelName,
    long latencyMs,
    int inputTokens,
    int outputTokens,
    boolean isCached,
    boolean isSuccess,
    String errorMessage
) {
    public static VisionResponse success(String rawContent, String modelName, long latencyMs, boolean isCached) {
        return new VisionResponse(rawContent, modelName, latencyMs, 0, 0, isCached, true, null);
    }

    public static VisionResponse failure(String errorMessage, String modelName, long latencyMs) {
        return new VisionResponse("", modelName, latencyMs, 0, 0, false, false, errorMessage);
    }
}
