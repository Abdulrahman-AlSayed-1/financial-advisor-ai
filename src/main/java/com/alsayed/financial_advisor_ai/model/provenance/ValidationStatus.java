package com.alsayed.financial_advisor_ai.model.provenance;

/**
 * Declares the cross-source validation status between Vision and Deterministic layers.
 */
public enum ValidationStatus {
    /** Deterministic evidence supports the same entity/metric/period/value */
    VERIFIED_AGREEMENT,

    /** Value is genuinely visible in a figure as an explicit printed label but not in the text stream */
    VISUAL_ONLY,

    /** Deterministic extraction contains the data point but Vision did not extract it */
    DETERMINISTIC_ONLY,

    /** Both sources claim the same entity/series/period but disagree on numerical value */
    CONFLICT,

    /** Vision extracted an estimate from an unlabeled visual coordinate or continuous axis */
    VISION_VISUAL_ESTIMATE,

    /** Vision produced unsupported numerical precision or unresolvable artifact */
    INVALID_VISUAL_ESTIMATE,

    /** Unable to validate due to missing contextual metadata or failed parsing */
    UNRESOLVED,

    /** Extracted from a chart type that lacks reliable tabular semantics (e.g. scatter plot) */
    UNVALIDATED_CHART
}
