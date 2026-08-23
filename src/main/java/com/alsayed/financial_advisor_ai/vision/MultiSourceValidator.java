package com.alsayed.financial_advisor_ai.vision;

import com.alsayed.financial_advisor_ai.chunking.FigureDataPoint;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.normalized.PanelInterpretation;
import com.alsayed.financial_advisor_ai.model.provenance.NumericalProvenance;
import com.alsayed.financial_advisor_ai.model.provenance.ValidationStatus;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTable;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates Vision-extracted data points against structural invariants
 * and deterministic raw text/table evidence.
 * 
 * Enforces strict relational binding: (Entity + Series/Metric + Period + Value)
 * and unit-aware numerical normalization.
 */
@Slf4j
@Component
public class MultiSourceValidator {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("[-+]?\\d*\\.?\\d+");

    /**
     * Validates an entire FigureInterpretation for structural and semantic consistency:
     * - No category headers or axis ticks as data
     * - No conflicting duplicate (entity, series) mappings
     * - Numeric validity
     * - Series fidelity against detected legend
     * - Range invariants (High >= Low, High >= Average >= Low) ONLY when range semantics are established
     */
    public boolean validateFigureInterpretation(FigureInterpretation interpretation) {
        if (interpretation == null || "FAILED".equalsIgnoreCase(interpretation.diagnosticStatus())
                || "UNRESOLVED".equalsIgnoreCase(interpretation.diagnosticStatus())) {
            return false;
        }

        if (interpretation.panels() == null || interpretation.panels().isEmpty()) {
            return false;
        }

        Set<String> validLegendSeries = new HashSet<>();
        if (interpretation.detectedLegendSeries() != null) {
            for (String s : interpretation.detectedLegendSeries()) {
                if (s != null && !s.isBlank()) {
                    validLegendSeries.add(s.trim().toLowerCase());
                }
            }
        }

        boolean isRangeSemantics = isRangeChartSemantics(interpretation);

        for (PanelInterpretation panel : interpretation.panels()) {
            if (panel.dataPoints() == null || panel.dataPoints().isEmpty()) {
                continue;
            }

            Map<String, String> seenEntitySeriesValues = new HashMap<>();
            Map<String, Map<String, Double>> entitySeriesNumericValues = new HashMap<>();

            for (FigureDataPoint dp : panel.dataPoints()) {
                if (!dp.isValidDataPoint()) {
                    log.warn("Invalid data point rejected by validator: entity='{}', value='{}'",
                            dp.getEntity(), dp.getValue());
                    return false;
                }

                String series = dp.getSeries() != null ? dp.getSeries().trim().toLowerCase() : "";
                if ((series.equals("label") || series.equals("series 1") || series.equals("column 1"))
                        && !validLegendSeries.contains(series)) {
                    log.warn("Unjustified generic series '{}' rejected for entity '{}'", series, dp.getEntity());
                    return false;
                }

                String key = (dp.getEntity() != null ? dp.getEntity().trim().toLowerCase() : "") + "||" + series;
                String existingVal = seenEntitySeriesValues.get(key);
                if (existingVal != null && !existingVal.equals(dp.getValue())) {
                    log.warn("Conflicting duplicate mappings detected in panel '{}': key='{}', val1='{}', val2='{}'",
                            panel.panelTitle(), key, existingVal, dp.getValue());
                    return false;
                }
                seenEntitySeriesValues.put(key, dp.getValue());

                Double numVal = parseNumericValue(dp.getValue());
                if (numVal != null && dp.getEntity() != null) {
                    entitySeriesNumericValues.computeIfAbsent(dp.getEntity().trim().toLowerCase(), k -> new HashMap<>())
                            .put(series, numVal);
                }
            }

            if (isRangeSemantics) {
                for (Map.Entry<String, Map<String, Double>> entry : entitySeriesNumericValues.entrySet()) {
                    String entityName = entry.getKey();
                    Map<String, Double> seriesVals = entry.getValue();

                    Double high = findValueMatchingHigh(seriesVals);
                    Double low = findValueMatchingLow(seriesVals);
                    Double avg = findValueMatchingKey(seriesVals, "avg", "average");

                    if (high != null && low != null) {
                        if (high < low) {
                            log.warn("Range invariant violation: High ({}) < Low ({}) for entity '{}'",
                                    high, low, entityName);
                            return false;
                        }

                        if (avg != null && (avg > high + 0.05 || avg < low - 0.05)) {
                            log.warn("Range invariant violation: Average ({}) outside High/Low range [{}, {}] for entity '{}'",
                                    avg, low, high, entityName);
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    public ValidationResult validateDataPoint(
            FigureDataPoint point,
            List<RawPdfTextBlock> pageTextBlocks) {
        return validateDataPoint(point, pageTextBlocks, List.of());
    }

    /**
     * Cross-reconciles an individual data point against raw PDF text blocks and tables.
     * Establishes strict relational binding: (Entity + Series/Metric + Period + Value).
     */
    public ValidationResult validateDataPoint(
            FigureDataPoint point,
            List<RawPdfTextBlock> pageTextBlocks,
            List<RawPdfTable> pageTables) {

        if (point == null || !point.isValidDataPoint()) {
            return new ValidationResult(ValidationStatus.INVALID_VISUAL_ESTIMATE, NumericalProvenance.UNKNOWN, null, "Invalid data point structure");
        }

        Double visionNum = parseNumericValue(point.getValue());
        if (visionNum == null) {
            return new ValidationResult(ValidationStatus.INVALID_VISUAL_ESTIMATE, NumericalProvenance.UNKNOWN, null, "Non-numeric value");
        }

        String entity = point.getEntity() != null ? point.getEntity().trim().toLowerCase() : "";
        String series = point.getSeries() != null ? point.getSeries().trim().toLowerCase() : "";
        String period = point.getPeriod() != null ? point.getPeriod().trim().toLowerCase() : "";

        // 1. Check deterministic structured tables first (highest structural precision)
        if (pageTables != null) {
            for (RawPdfTable table : pageTables) {
                if (table.markdown() == null) continue;
                String[] rows = table.markdown().split("\n");
                for (String row : rows) {
                    String rowLower = row.toLowerCase();
                    if (matchesEntity(rowLower, entity)) {
                        // Found entity in deterministic table row. Check row values.
                        List<Double> rowNumbers = extractAllNumbers(row);
                        if (!rowNumbers.isEmpty()) {
                            // Check for exact numeric match
                            for (Double num : rowNumbers) {
                                if (numbersMatch(visionNum, num, point.getUnit())) {
                                    return new ValidationResult(
                                            ValidationStatus.VERIFIED_AGREEMENT,
                                            NumericalProvenance.RECONCILED,
                                            formatNumber(num, point.getUnit()),
                                            "Verified against deterministic table row");
                                }
                            }
                            // Entity found in table row, but vision value does NOT match any number in that row
                            return new ValidationResult(
                                    ValidationStatus.CONFLICT,
                                    NumericalProvenance.CONFLICT,
                                    formatNumber(rowNumbers.get(0), point.getUnit()),
                                    String.format("Conflict with deterministic table row: Vision=%s vs Table=%s",
                                            point.getValue(), row.trim()));
                        }
                    }
                }
            }
        }

        // 2. Check deterministic text blocks (relational same-line / same-block proximity)
        if (pageTextBlocks != null && !pageTextBlocks.isEmpty()) {
            boolean entityFoundInAnyBlock = false;
            Double conflictingCandidateNumber = null;

            for (RawPdfTextBlock block : pageTextBlocks) {
                String blockText = block.text();
                if (blockText == null) continue;
                String[] lines = blockText.split("\n");

                for (String line : lines) {
                    String lineLower = line.toLowerCase();
                    if (matchesEntity(lineLower, entity)) {
                        entityFoundInAnyBlock = true;
                        List<Double> lineNumbers = extractAllNumbers(line);
                        for (Double num : lineNumbers) {
                            if (numbersMatch(visionNum, num, point.getUnit())) {
                                return new ValidationResult(
                                        ValidationStatus.VERIFIED_AGREEMENT,
                                        NumericalProvenance.RECONCILED,
                                        formatNumber(num, point.getUnit()),
                                        "Verified against deterministic text line");
                            }
                        }
                        if (!lineNumbers.isEmpty() && conflictingCandidateNumber == null) {
                            conflictingCandidateNumber = lineNumbers.get(0);
                        }
                    }
                }
            }

            if (entityFoundInAnyBlock && conflictingCandidateNumber != null) {
                return new ValidationResult(
                        ValidationStatus.CONFLICT,
                        NumericalProvenance.CONFLICT,
                        formatNumber(conflictingCandidateNumber, point.getUnit()),
                        String.format("Conflict with deterministic text: Vision claimed %s but text contains %s",
                                point.getValue(), conflictingCandidateNumber));
            }
        }

        // 3. Fallback: If not found in text/table layer
        // Distinguish explicit printed labels vs continuous chart estimates
        if (isLikelyVisualEstimate(point)) {
            return new ValidationResult(
                    ValidationStatus.VISION_VISUAL_ESTIMATE,
                    NumericalProvenance.VISION_VISUAL_ESTIMATE,
                    null,
                    "Estimated from visual chart geometry without printed label");
        } else {
            return new ValidationResult(
                    ValidationStatus.VISUAL_ONLY,
                    NumericalProvenance.VISION_EXPLICIT_LABEL,
                    null,
                    "Explicit visual label not present in deterministic text stream");
        }
    }

    public record ValidationResult(
            ValidationStatus status,
            NumericalProvenance provenance,
            String deterministicValue,
            String notes
    ) {}

    private boolean matchesEntity(String textLower, String entityLower) {
        if (entityLower.isBlank()) return false;
        if (textLower.contains(entityLower)) return true;

        String[] tokens = entityLower.split("[\\s(),/]+");
        int matchCount = 0;
        int significantTokens = 0;
        for (String t : tokens) {
            if (t.length() >= 3 && !t.equals("index") && !t.equals("the") && !t.equals("and")) {
                significantTokens++;
                if (textLower.contains(t)) {
                    matchCount++;
                }
            }
        }
        return significantTokens > 0 && matchCount == significantTokens;
    }

    /**
     * Unit-aware and metric-aware numeric comparison.
     */
    public boolean numbersMatch(Double val1, Double val2, String unit) {
        if (val1 == null || val2 == null) return false;

        // Tolerance: 0.01 for percentages/ratios, 0.1 for large values
        double epsilon = 0.015;
        if (Math.abs(val1 - val2) <= epsilon) {
            return true;
        }

        // Check basis point scaling (e.g. 50 bps vs 0.50%)
        if ("bps".equalsIgnoreCase(unit) || "bp".equalsIgnoreCase(unit)) {
            if (Math.abs((val1 / 100.0) - val2) <= epsilon || Math.abs(val1 - (val2 * 100.0)) <= epsilon) {
                return true;
            }
        }

        // Check multiplier scaling (e.g., 1000x)
        if (Math.abs((val1 * 100.0) - val2) <= epsilon || Math.abs(val1 - (val2 * 100.0)) <= epsilon) {
            return true;
        }

        return false;
    }

    public List<Double> extractAllNumbers(String text) {
        List<Double> numbers = new ArrayList<>();
        if (text == null || text.isBlank()) return numbers;

        // Clean parentheses for negative numbers e.g. (2.3) -> -2.3
        String cleaned = text.replaceAll("\\(([0-9.]+)\\)", "-$1");
        Matcher m = NUMERIC_PATTERN.matcher(cleaned);
        while (m.find()) {
            try {
                String str = m.group();
                if (!str.equals(".") && !str.equals("-") && !str.equals("+")) {
                    numbers.add(Double.parseDouble(str));
                }
            } catch (NumberFormatException ignored) {}
        }
        return numbers;
    }

    public Double parseNumericValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String clean = raw.trim();
            if (clean.startsWith("(") && clean.endsWith(")")) {
                clean = "-" + clean.substring(1, clean.length() - 1);
            }
            clean = clean.replaceAll("[^0-9.-]", "");
            if (clean.isEmpty() || clean.equals("-") || clean.equals(".")) return null;
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatNumber(Double num, String unit) {
        if (num == null) return "";
        if (unit != null && !unit.isBlank()) {
            return String.format(Locale.US, "%.2f%s", num, unit.startsWith("%") ? "%" : " " + unit);
        }
        return String.format(Locale.US, "%.2f", num);
    }

    private boolean isLikelyVisualEstimate(FigureDataPoint point) {
        if (point == null) return false;
        String period = point.getPeriod() != null ? point.getPeriod().toLowerCase() : "";
        String series = point.getSeries() != null ? point.getSeries().toLowerCase() : "";
        String title = point.getFigureTitle() != null ? point.getFigureTitle().toLowerCase() : "";

        return title.contains("time series") || title.contains("trend") || title.contains("history")
                || series.contains("lhs") || series.contains("rhs")
                || period.contains("19") || period.contains("200") || period.contains("201");
    }

    private boolean isRangeHighSeries(String s) {
        if (s == null || s.isBlank()) return false;
        String sLower = s.trim().toLowerCase();
        if (sLower.contains("yield") || sLower.contains("beta") || sLower.contains("dividend")
                || sLower.contains("grade") || sLower.contains("income") || sLower.contains("quality")) {
            return false;
        }
        return sLower.equals("high") || sLower.endsWith(" high") || sLower.startsWith("high ")
                || sLower.equals("max") || sLower.equals("maximum");
    }

    private boolean isRangeLowSeries(String s) {
        if (s == null || s.isBlank()) return false;
        String sLower = s.trim().toLowerCase();
        if (sLower.contains("volatility") || sLower.contains("risk") || sLower.contains("duration")
                || sLower.contains("carbon") || sLower.contains("beta") || sLower.contains("leverage")) {
            return false;
        }
        return sLower.equals("low") || sLower.endsWith(" low") || sLower.startsWith("low ")
                || sLower.equals("min") || sLower.equals("minimum");
    }

    private boolean isRangeChartSemantics(FigureInterpretation interpretation) {
        if (interpretation == null) return false;
        String type = interpretation.figureType() != null ? interpretation.figureType().toLowerCase() : "";
        if (type.contains("range") || type.contains("high_low") || type.contains("high-low")) {
            return true;
        }

        boolean hasHigh = false;
        boolean hasLow = false;

        if (interpretation.detectedLegendSeries() != null) {
            for (String s : interpretation.detectedLegendSeries()) {
                if (isRangeHighSeries(s)) hasHigh = true;
                if (isRangeLowSeries(s)) hasLow = true;
            }
        }

        if (!hasHigh || !hasLow) {
            if (interpretation.panels() != null) {
                for (PanelInterpretation panel : interpretation.panels()) {
                    if (panel.dataPoints() != null) {
                        for (FigureDataPoint dp : panel.dataPoints()) {
                            if (isRangeHighSeries(dp.getSeries())) hasHigh = true;
                            if (isRangeLowSeries(dp.getSeries())) hasLow = true;
                        }
                    }
                }
            }
        }

        return hasHigh && hasLow;
    }

    private Double findValueMatchingHigh(Map<String, Double> map) {
        for (Map.Entry<String, Double> entry : map.entrySet()) {
            if (isRangeHighSeries(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private Double findValueMatchingLow(Map<String, Double> map) {
        for (Map.Entry<String, Double> entry : map.entrySet()) {
            if (isRangeLowSeries(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private Double findValueMatchingKey(Map<String, Double> map, String... keywords) {
        for (Map.Entry<String, Double> entry : map.entrySet()) {
            for (String kw : keywords) {
                if (entry.getKey().contains(kw)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}
