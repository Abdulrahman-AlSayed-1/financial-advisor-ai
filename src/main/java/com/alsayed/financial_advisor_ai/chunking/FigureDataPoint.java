package com.alsayed.financial_advisor_ai.chunking;

import com.alsayed.financial_advisor_ai.model.provenance.NumericalProvenance;
import com.alsayed.financial_advisor_ai.model.provenance.ValidationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured representation of a single atomic data point extracted from a figure/chart.
 * 
 * Preserves the explicit semantic relationship:
 * Entity -> Series/Metric -> Period -> Value (with unit and extraction provenance).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FigureDataPoint {
    
    /**
     * The title of the parent figure (e.g., "Local Sovereign Currency Yields")
     */
    private String figureTitle;
    
    /**
     * The panel/subchart within the figure (e.g., "Emerging Market Local Interest Rates % (1 Year)")
     */
    private String panel;
    
    /**
     * The entity name (e.g., "South African Rand (ZAR)", "Hungarian Forint (HUF)")
     */
    private String entity;

    /**
     * Entity symbol if present (e.g., "ZAR", "HUF", "NGN")
     */
    private String entitySymbol;
    
    /**
     * The series or legend label (e.g., "Current", "1 Yr Prior", "YTD")
     */
    private String series;
    
    /**
     * The metric being measured (e.g., "Local Sovereign Currency Yield")
     */
    private String metric;
    
    /**
     * The time period (e.g., "1 Year", "YTD", "Current")
     */
    private String period;
    
    /**
     * The unit of measurement (e.g., "%", "bps", "$")
     */
    private String unit;
    
    /**
     * The raw text value (e.g., "9.22", "8.05%")
     */
    private String value;

    /**
     * Parsed double value if applicable
     */
    private Double numericValue;
    
    /**
     * The page number where the figure appears
     */
    private int pageNumber;

    /**
     * Extraction method used (e.g. VISION_LLM, FALLBACK_SPATIAL, UNRESOLVED)
     */
    @Builder.Default
    private String extractionMethod = "VISION_LLM";

    /**
     * Confidence level (HIGH, MEDIUM, LOW, UNRESOLVED)
     */
    @Builder.Default
    private String confidence = "HIGH";

    /**
     * Exact numerical provenance
     */
    @Builder.Default
    private NumericalProvenance provenance = NumericalProvenance.UNKNOWN;

    /**
     * Validation status against deterministic ground truth
     */
    @Builder.Default
    private ValidationStatus validationStatus = ValidationStatus.UNRESOLVED;

    /**
     * Deterministic value from text/table if available
     */
    private String deterministicValue;

    /**
     * Diagnostic conflict or estimation notes
     */
    private String conflictNotes;
    
    /**
     * Generate a self-describing semantic chunk content.
     */
    public String toSemanticChunk() {
        StringBuilder sb = new StringBuilder();
        
        if (figureTitle != null && !figureTitle.isEmpty()) {
            sb.append("Figure: ").append(figureTitle).append("\n");
        }
        
        if (panel != null && !panel.isEmpty()) {
            sb.append("Panel: ").append(panel).append("\n");
        }
        
        if (entity != null && !entity.isEmpty()) {
            sb.append("Entity: ").append(entity).append("\n");
        }
        
        if (series != null && !series.isEmpty()) {
            sb.append("Series: ").append(series).append("\n");
        }
        
        if (metric != null && !metric.isEmpty()) {
            sb.append("Metric: ").append(metric).append("\n");
        }
        
        if (period != null && !period.isEmpty()) {
            sb.append("Period: ").append(period).append("\n");
        }
        
        if (value != null && !value.isEmpty()) {
            sb.append("Value: ").append(value);
            if (unit != null && !unit.isEmpty() && !value.contains(unit)) {
                sb.append(unit);
            }
            sb.append("\n");
        }

        if (pageNumber > 0) {
            sb.append("Page: ").append(pageNumber).append("\n");
        }

        if (provenance != null && provenance != NumericalProvenance.UNKNOWN) {
            sb.append("Provenance: ").append(provenance.name()).append("\n");
        }

        if (validationStatus != null && validationStatus != ValidationStatus.UNRESOLVED) {
            sb.append("Validation Status: ").append(validationStatus.name()).append("\n");
        }

        if (conflictNotes != null && !conflictNotes.isBlank()) {
            sb.append("Note: ").append(conflictNotes).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Structural validity checks:
     * - Entity must be present and not a section heading
     * - Value must be numeric and not an axis tick / dash
     */
    public boolean isValidDataPoint() {
        if (entity == null || entity.isBlank()) return false;
        if (value == null || value.isBlank()) return false;

        String cleanVal = value.trim();
        if (cleanVal.equals("-") || cleanVal.equals("—") || cleanVal.equals("–")
                || cleanVal.equalsIgnoreCase("null") || cleanVal.equalsIgnoreCase("n/a")
                || cleanVal.equalsIgnoreCase("--")) {
            return false;
        }

        // Entity must not be a top-level section/category header or axis legend
        String cleanEntity = entity.trim().toUpperCase();
        if (cleanEntity.equals("ALTERNATIVES") || cleanEntity.equals("EQUITIES") || cleanEntity.equals("BONDS")
                || cleanEntity.equals("COMMODITIES") || cleanEntity.equals("REAL ESTATE") || cleanEntity.equals("TOP IDEAS")
                || cleanEntity.equals("DEVELOPED MARKET") || cleanEntity.equals("EMERGING MARKET")
                || cleanEntity.equals("SOURCE: BLOOMBERG") || cleanEntity.startsWith("SOURCE:")) {
            return false;
        }

        // Value must contain at least one numeric digit
        return cleanVal.matches(".*\\d+.*");
    }
}
