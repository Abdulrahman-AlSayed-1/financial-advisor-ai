package com.alsayed.financial_advisor_ai.chunking.figure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config-driven policy for the figure chunking hierarchy.
 *
 * Governs the three-tier model:
 *   Tier 1 — Figure Summary Chunk  (always created)
 *   Tier 2 — Panel Summary Chunks  (always created when panels exist)
 *   Tier 3 — Datapoint Chunks      (only when total datapoints <= maxDatapointChunks)
 *
 * For large figures (> maxDatapointChunks), all numerical data is preserved
 * in the panel summary chunks so that exact-value queries remain answerable
 * without flooding the vector store with hundreds of nearly-identical vectors.
 */

@ConfigurationProperties(prefix = "app.chunking.figure")
public class FigureChunkPolicy {

    /**
     * Maximum total number of datapoints across all panels in a figure
     * before switching from 3-tier (summary + panels + datapoints)
     * to 2-tier (summary + panels only).
     *
     * Default: 30 — small figures get datapoint precision;
     * large figures rely on panel summary tables for exact-value retrieval.
     */
    private int maxDatapointChunks = 30;

    /** Always emit a figure-level summary chunk. Should always be true. */
    private boolean alwaysCreateSummary = true;

    /** Always emit panel-level summary chunks (one per panel). Should always be true. */
    private boolean alwaysCreatePanelSummaries = true;

    // ── Getters / Setters (Spring Boot @ConfigurationProperties requires them) ──

    public int getMaxDatapointChunks() {
        return maxDatapointChunks;
    }

    public void setMaxDatapointChunks(int maxDatapointChunks) {
        this.maxDatapointChunks = maxDatapointChunks;
    }

    public boolean isAlwaysCreateSummary() {
        return alwaysCreateSummary;
    }

    public void setAlwaysCreateSummary(boolean alwaysCreateSummary) {
        this.alwaysCreateSummary = alwaysCreateSummary;
    }

    public boolean isAlwaysCreatePanelSummaries() {
        return alwaysCreatePanelSummaries;
    }

    public void setAlwaysCreatePanelSummaries(boolean alwaysCreatePanelSummaries) {
        this.alwaysCreatePanelSummaries = alwaysCreatePanelSummaries;
    }

    /**
     * Returns true when the given total datapoint count is small enough to
     * justify creating individual datapoint chunks in addition to summaries.
     */
    public boolean shouldCreateDatapointChunks(int totalDatapoints) {
        return totalDatapoints <= maxDatapointChunks;
    }
}
