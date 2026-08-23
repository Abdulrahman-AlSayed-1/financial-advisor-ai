package com.alsayed.financial_advisor_ai.vision;

import java.util.Map;

/**
 * Immutable request payload for visual interpretation.
 */
public record VisionRequest(
    byte[] primaryImageData,
    String mimeType,
    VisionTaskType taskType,
    String prompt,
    Map<String, Object> contextMetadata,
    byte[] contextOverviewImageData,
    String responseSchemaJson,
    String modelOverride
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private byte[] primaryImageData;
        private String mimeType = "image/png";
        private VisionTaskType taskType = VisionTaskType.FIGURE_INTERPRETATION;
        private String prompt = "";
        private Map<String, Object> contextMetadata = Map.of();
        private byte[] contextOverviewImageData;
        private String responseSchemaJson;
        private String modelOverride;

        public Builder primaryImageData(byte[] data) { this.primaryImageData = data; return this; }
        public Builder mimeType(String mimeType) { this.mimeType = mimeType; return this; }
        public Builder taskType(VisionTaskType taskType) { this.taskType = taskType; return this; }
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder contextMetadata(Map<String, Object> meta) { this.contextMetadata = meta; return this; }
        public Builder contextOverviewImageData(byte[] overview) { this.contextOverviewImageData = overview; return this; }
        public Builder responseSchemaJson(String schema) { this.responseSchemaJson = schema; return this; }
        public Builder modelOverride(String model) { this.modelOverride = model; return this; }

        public VisionRequest build() {
            return new VisionRequest(
                primaryImageData, mimeType, taskType, prompt,
                contextMetadata, contextOverviewImageData, responseSchemaJson, modelOverride
            );
        }
    }
}
