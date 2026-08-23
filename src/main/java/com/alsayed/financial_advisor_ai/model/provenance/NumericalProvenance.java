package com.alsayed.financial_advisor_ai.model.provenance;

/**
 * Declares the exact extraction origin and authority of a numerical data point.
 */
public enum NumericalProvenance {
    /** Extracted directly from deterministic PDF text stream */
    DETERMINISTIC_TEXT,

    /** Extracted from deterministic structured PDF table grid */
    DETERMINISTIC_TABLE,

    /** Extracted from explicitly printed label in visual chart/figure */
    VISION_EXPLICIT_LABEL,

    /** Inferred from chart geometry/axis coordinates (continuous line/bar without printed numeric label) */
    VISION_VISUAL_ESTIMATE,

    /** Verified and reconciled by both deterministic text/table and visual extraction */
    RECONCILED,

    /** Conflicting values between deterministic layer and visual extraction */
    CONFLICT,

    /** Unknown or unverified origin */
    UNKNOWN
}
