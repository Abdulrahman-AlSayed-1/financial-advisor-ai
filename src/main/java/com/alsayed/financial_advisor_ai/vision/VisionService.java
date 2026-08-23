package com.alsayed.financial_advisor_ai.vision;

/**
 * Provider-agnostic interface for multimodal visual understanding.
 */
public interface VisionService {
    /**
     * Interprets a visual request (image + prompt + context).
     *
     * @param request VisionRequest payload
     * @return VisionResponse containing raw LLM response or failure details
     */
    VisionResponse interpret(VisionRequest request);
}
