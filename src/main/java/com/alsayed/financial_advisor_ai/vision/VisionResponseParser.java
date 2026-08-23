package com.alsayed.financial_advisor_ai.vision;

import com.alsayed.financial_advisor_ai.chunking.CanonicalTable;
import com.alsayed.financial_advisor_ai.chunking.CanonicalTableParser;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Centralized parser that decodes raw Vision LLM response strings into typed domain objects.
 */
@Slf4j
@Component
public class VisionResponseParser {

    private final ObjectMapper objectMapper;
    private final CanonicalTableParser tableParser;

    public VisionResponseParser(CanonicalTableParser tableParser) {
        this.tableParser = tableParser;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Parses a VisionResponse into a typed FigureInterpretation.
     */
    public FigureInterpretation parseFigure(VisionResponse response) {
        if (response == null || !response.isSuccess() || response.rawContent() == null || response.rawContent().isBlank()) {
            String err = response != null && response.errorMessage() != null ? response.errorMessage() : "Empty Vision response";
            return FigureInterpretation.failed(err);
        }

        String cleanJson = extractJsonFromMarkdownFences(response.rawContent());
        try {
            FigureInterpretation interpretation = objectMapper.readValue(cleanJson, FigureInterpretation.class);
            log.info("Successfully parsed FigureInterpretation '{}' with {} panels",
                    interpretation.figureTitle(), interpretation.panels() != null ? interpretation.panels().size() : 0);
            return interpretation;
        } catch (Exception e) {
            log.error("Failed to deserialize FigureInterpretation from JSON: {}. Error: {}", cleanJson, e.getMessage(), e);
            return FigureInterpretation.failed("JSON_PARSE_ERROR: " + e.getMessage());
        }
    }

    /**
     * Parses a VisionResponse into a CanonicalTable for complex tables.
     */
    public CanonicalTable parseComplexTable(VisionResponse response, String tableId, int pageNumber, String section) {
        if (response == null || !response.isSuccess() || response.rawContent() == null || response.rawContent().isBlank()) {
            CanonicalTable empty = new CanonicalTable();
            empty.setTableId(tableId);
            empty.setPageNumber(pageNumber);
            empty.setSection(section);
            return empty;
        }

        String cleanJson = extractJsonFromMarkdownFences(response.rawContent());
        try {
            JsonNode root = objectMapper.readTree(cleanJson);
            String markdown = root.has("markdown") ? root.get("markdown").asText() : "";
            if (!markdown.isBlank()) {
                CanonicalTable table = tableParser.parseMarkdownTable(markdown, tableId, section, pageNumber);
                if (root.has("table_title") && !root.get("table_title").asText().isBlank()) {
                    table.setTitle(root.get("table_title").asText());
                }
                return table;
            }
        } catch (Exception e) {
            log.error("Failed to parse ComplexTable JSON: {}", e.getMessage(), e);
        }

        CanonicalTable fallback = new CanonicalTable();
        fallback.setTableId(tableId);
        fallback.setPageNumber(pageNumber);
        fallback.setSection(section);
        return fallback;
    }

    private String extractJsonFromMarkdownFences(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
