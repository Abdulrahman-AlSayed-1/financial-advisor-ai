package com.alsayed.financial_advisor_ai.chunking.figure;

import com.alsayed.financial_advisor_ai.chunking.FigureDataPoint;
import com.alsayed.financial_advisor_ai.model.normalized.FigureInterpretation;
import com.alsayed.financial_advisor_ai.model.normalized.PanelInterpretation;
import com.alsayed.financial_advisor_ai.model.provenance.NumericalProvenance;
import com.alsayed.financial_advisor_ai.model.provenance.ValidationStatus;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTable;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfTextBlock;
import com.alsayed.financial_advisor_ai.vision.MultiSourceValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds multi-tiered, self-contained Document chunks from a structured FigureInterpretation.
 */
@Slf4j
@Component
public class FigureChunkBuilder {

    private final FigureChunkPolicy policy;
    private final MultiSourceValidator validator;

    @org.springframework.beans.factory.annotation.Autowired
    public FigureChunkBuilder(FigureChunkPolicy policy, MultiSourceValidator validator) {
        this.policy = policy;
        this.validator = validator;
    }

    public FigureChunkBuilder(MultiSourceValidator validator, FigureChunkPolicy policy) {
        this.policy = policy;
        this.validator = validator;
    }

    public List<Document> buildChunks(
            FigureInterpretation interpretation,
            int pageNumber,
            String sectionHeading,
            String sourceFile,
            String sourceDocumentId,
            List<RawPdfTextBlock> pageTextBlocks) {
        return buildChunks(interpretation, pageNumber, sectionHeading, sourceFile, sourceDocumentId, pageTextBlocks, List.of(), 0, null);
    }

    public List<Document> buildChunks(
            FigureInterpretation interpretation,
            int pageNumber,
            String sectionHeading,
            String sourceFile,
            String sourceDocumentId,
            List<RawPdfTextBlock> pageTextBlocks,
            int candidateIndex,
            double[] bbox) {
        return buildChunks(interpretation, pageNumber, sectionHeading, sourceFile, sourceDocumentId, pageTextBlocks, List.of(), candidateIndex, bbox);
    }

    public List<Document> buildChunks(
            FigureInterpretation interpretation,
            int pageNumber,
            String sectionHeading,
            String sourceFile,
            String sourceDocumentId,
            List<RawPdfTextBlock> pageTextBlocks,
            List<RawPdfTable> pageTables,
            int candidateIndex,
            double[] bbox) {

        List<Document> chunks = new ArrayList<>();

        // ── Guard: failed / missing / unresolved interpretation ──────────────
        if (interpretation == null || "FAILED".equalsIgnoreCase(interpretation.diagnosticStatus())
                || "UNRESOLVED".equalsIgnoreCase(interpretation.diagnosticStatus())) {
            String status = interpretation != null && "UNRESOLVED".equalsIgnoreCase(interpretation.diagnosticStatus())
                    ? "UNRESOLVED" : "FAILED";
            String reason = (interpretation != null
                    && interpretation.notes() != null
                    && !interpretation.notes().isEmpty())
                    ? interpretation.notes().get(0)
                    : (interpretation != null && "UNRESOLVED".equalsIgnoreCase(interpretation.diagnosticStatus())
                        ? "UNRESOLVED_VISUAL_RELATIONSHIPS" : "UNKNOWN_VISION_FAILURE");
            chunks.add(buildDiagnosticFailureChunk(pageNumber, sectionHeading, sourceFile, sourceDocumentId, status, reason, candidateIndex, bbox));
            return chunks;
        }

        String figureTitle = (interpretation.figureTitle() != null && !interpretation.figureTitle().isBlank())
                ? interpretation.figureTitle()
                : sectionHeading;

        List<PanelInterpretation> panels = interpretation.panels();
        if (panels == null || panels.isEmpty()) {
            log.warn("FigureInterpretation on page {} has no panels — emitting diagnostic chunk", pageNumber);
            chunks.add(buildDiagnosticFailureChunk(pageNumber, sectionHeading, sourceFile, sourceDocumentId, "FAILED", "NO_PANELS", candidateIndex, bbox));
            return chunks;
        }

        // ── Sanitize panels and reconcile with deterministic evidence ────────
        List<PanelInterpretation> sanitizedPanels = new ArrayList<>();
        int verifiedCount = 0;
        int conflictCount = 0;
        int visualOnlyCount = 0;
        int estimateCount = 0;

        for (PanelInterpretation p : panels) {
            List<FigureDataPoint> reconciledPoints = new ArrayList<>();
            if (p.dataPoints() != null) {
                for (FigureDataPoint dp : p.dataPoints()) {
                    if (dp.isValidDataPoint()) {
                        enrichDataPoint(dp, figureTitle, p.panelTitle(), interpretation, pageNumber);
                        MultiSourceValidator.ValidationResult valRes =
                                validator.validateDataPoint(dp, pageTextBlocks, pageTables);

                        ValidationStatus status = valRes.status();
                        String figType = interpretation.figureType() != null ? interpretation.figureType().toLowerCase() : "other";
                        boolean isUnreliableChart = figType.contains("scatter") || figType.contains("line") || figType.contains("donut") || figType.contains("other");
                        
                        if (isUnreliableChart && status == ValidationStatus.VISUAL_ONLY) {
                            status = ValidationStatus.UNVALIDATED_CHART;
                            dp.setConflictNotes("Value extracted from a visual chart (" + figType + ") without a reliable grid structure.");
                        } else {
                            dp.setConflictNotes(valRes.notes());
                        }

                        dp.setValidationStatus(status);
                        dp.setProvenance(valRes.provenance());
                        dp.setDeterministicValue(valRes.deterministicValue());

                        if (status == ValidationStatus.VERIFIED_AGREEMENT) verifiedCount++;
                        else if (status == ValidationStatus.CONFLICT) conflictCount++;
                        else if (status == ValidationStatus.VISUAL_ONLY) visualOnlyCount++;
                        else if (status == ValidationStatus.VISION_VISUAL_ESTIMATE) estimateCount++;

                        reconciledPoints.add(dp);
                    }
                }
            }
            if (!reconciledPoints.isEmpty()) {
                sanitizedPanels.add(new PanelInterpretation(p.panelTitle(), reconciledPoints));
            }
        }

        if (sanitizedPanels.isEmpty()) {
            log.warn("FigureInterpretation on page {} has no valid datapoints after sanitization — emitting diagnostic chunk", pageNumber);
            chunks.add(buildDiagnosticFailureChunk(pageNumber, sectionHeading, sourceFile, sourceDocumentId, "FAILED", "NO_VALID_DATAPOINTS", candidateIndex, bbox));
            return chunks;
        }

        // ── Count total datapoints ────────────────────────────────────────────
        int totalDatapoints = sanitizedPanels.stream()
                .mapToInt(p -> p.dataPoints() == null ? 0 : p.dataPoints().size())
                .sum();

        boolean createDatapointChunks = policy.shouldCreateDatapointChunks(totalDatapoints);

        // ── Tier 1: Figure Summary Chunk ──────────────────────────────────────
        if (policy.isAlwaysCreateSummary()) {
            chunks.add(buildFigureSummaryChunk(
                    interpretation, figureTitle, sanitizedPanels,
                    pageNumber, sectionHeading, sourceFile, sourceDocumentId, candidateIndex, bbox,
                    verifiedCount, conflictCount, visualOnlyCount, estimateCount));
        }

        // ── Tier 2: Panel Summary Chunks ──────────────────────────────────────
        if (policy.isAlwaysCreatePanelSummaries()) {
            for (int panelIdx = 0; panelIdx < sanitizedPanels.size(); panelIdx++) {
                PanelInterpretation panel = sanitizedPanels.get(panelIdx);
                if (panel.dataPoints() == null || panel.dataPoints().isEmpty()) continue;

                chunks.add(buildPanelSummaryChunk(
                        panel, interpretation, figureTitle,
                        pageNumber, sectionHeading, sourceFile, sourceDocumentId, panelIdx, candidateIndex, bbox));
            }
        }

        // ── Tier 3: Datapoint Chunks (conditional) ────────────────────────────
        if (createDatapointChunks) {
            int pointIndex = 0;
            for (PanelInterpretation panel : sanitizedPanels) {
                String panelTitle = panel.panelTitle();
                if (panel.dataPoints() == null) continue;

                for (FigureDataPoint dp : panel.dataPoints()) {
                    chunks.add(buildDatapointChunk(
                            dp, figureTitle, panelTitle, sectionHeading,
                            pageNumber, sourceFile, sourceDocumentId, pointIndex++, candidateIndex, bbox));
                }
            }
        }

        return chunks;
    }

    // ── Tier 1: Figure Summary ────────────────────────────────────────────────

    private Document buildFigureSummaryChunk(
            FigureInterpretation interp,
            String figureTitle,
            List<PanelInterpretation> panels,
            int pageNumber,
            String sectionHeading,
            String sourceFile,
            String sourceDocumentId,
            int candidateIndex,
            double[] bbox,
            int verifiedCount,
            int conflictCount,
            int visualOnlyCount,
            int estimateCount) {

        StringBuilder sb = new StringBuilder();
        sb.append("Figure: ").append(figureTitle).append("\n");

        if (interp.figureType() != null && !interp.figureType().isBlank()
                && !"unknown".equalsIgnoreCase(interp.figureType())) {
            sb.append("Chart Type: ").append(interp.figureType()).append("\n");
        }
        if (interp.metric() != null && !interp.metric().isBlank()) {
            sb.append("Metric: ").append(interp.metric()).append("\n");
        }
        if (interp.unit() != null && !interp.unit().isBlank()) {
            sb.append("Unit: ").append(interp.unit()).append("\n");
        }
        sb.append("Page: ").append(pageNumber).append("\n");
        sb.append("Section: ").append(sectionHeading).append("\n");

        // Panel names
        sb.append("\nPanels:\n");
        for (PanelInterpretation p : panels) {
            sb.append("- ").append(p.panelTitle() != null ? p.panelTitle() : "(unnamed)").append("\n");
        }

        // All unique entities across all panels
        Set<String> allEntities = new LinkedHashSet<>();
        Set<String> allSeries = new LinkedHashSet<>();
        for (PanelInterpretation p : panels) {
            if (p.dataPoints() == null) continue;
            for (FigureDataPoint dp : p.dataPoints()) {
                if (dp.getEntity() != null && !dp.getEntity().isBlank()) allEntities.add(dp.getEntity());
                if (dp.getSeries() != null && !dp.getSeries().isBlank()) allSeries.add(dp.getSeries());
            }
        }

        if (!allEntities.isEmpty()) {
            sb.append("\nEntities (").append(allEntities.size()).append("):\n");
            allEntities.forEach(e -> sb.append("- ").append(e).append("\n"));
        }
        if (!allSeries.isEmpty()) {
            sb.append("\nSeries:\n");
            allSeries.forEach(s -> sb.append("- ").append(s).append("\n"));
        }

        Map<String, Object> metadata = baseMetadata("figure_summary", pageNumber, sectionHeading,
                sourceFile, sourceDocumentId);
        metadata.put("figure_id", "page_" + pageNumber + "_fig_" + candidateIndex);
        metadata.put("figure_title", figureTitle);
        metadata.put("title", figureTitle);
        metadata.put("candidate_index", candidateIndex);
        metadata.put("verified_datapoint_count", verifiedCount);
        metadata.put("conflict_count", conflictCount);
        metadata.put("visual_only_count", visualOnlyCount);
        metadata.put("estimate_count", estimateCount);

        Set<String> sumProvenances = new LinkedHashSet<>();
        Set<String> sumStatuses = new LinkedHashSet<>();
        if (verifiedCount > 0) { sumProvenances.add(NumericalProvenance.RECONCILED.name()); sumStatuses.add(ValidationStatus.VERIFIED_AGREEMENT.name()); }
        if (visualOnlyCount > 0) { sumProvenances.add(NumericalProvenance.VISION_EXPLICIT_LABEL.name()); sumStatuses.add(ValidationStatus.VISUAL_ONLY.name()); }
        if (estimateCount > 0) { sumProvenances.add(NumericalProvenance.VISION_VISUAL_ESTIMATE.name()); sumStatuses.add(ValidationStatus.VISION_VISUAL_ESTIMATE.name()); }
        if (conflictCount > 0) { sumProvenances.add(NumericalProvenance.CONFLICT.name()); sumStatuses.add(ValidationStatus.CONFLICT.name()); }
        if (sumProvenances.isEmpty()) { sumProvenances.add("VISUAL_SUMMARY"); sumStatuses.add("VISUAL_SUMMARY"); }

        metadata.put("provenances", String.join(", ", sumProvenances));
        metadata.put("validation_statuses", String.join(", ", sumStatuses));

        if (interp.figureType() != null) metadata.put("figure_type", interp.figureType());
        if (interp.metric() != null) metadata.put("metric", interp.metric());
        if (interp.unit() != null) metadata.put("unit", interp.unit());
        if (!allEntities.isEmpty()) {
            metadata.put("entities_in_group", String.join(", ", allEntities));
        }

        String bboxStr = (bbox != null && bbox.length == 4)
                ? String.format("_b%.0f_%.0f_%.0f_%.0f", bbox[0], bbox[1], bbox[2], bbox[3])
                : "";
        String docPrefix = (sourceDocumentId != null && !sourceDocumentId.isBlank()) ? sourceDocumentId : (sourceFile != null ? sourceFile : "doc");
        String chunkId = deterministicId(docPrefix + "_fig_summary_p" + pageNumber + "_c" + candidateIndex + bboxStr + "_" + figureTitle);
        return new Document(chunkId, sb.toString().trim(), metadata);
    }

    // ── Tier 2: Panel Summary ─────────────────────────────────────────────────

    private Document buildPanelSummaryChunk(
            PanelInterpretation panel,
            FigureInterpretation interp,
            String figureTitle,
            int pageNumber,
            String sectionHeading,
            String sourceFile,
            String sourceDocumentId,
            int panelIdx,
            int candidateIndex,
            double[] bbox) {

        String panelTitle = panel.panelTitle() != null ? panel.panelTitle() : "(unnamed panel)";
        List<FigureDataPoint> dps = panel.dataPoints();

        StringBuilder sb = new StringBuilder();
        sb.append("Figure: ").append(figureTitle).append("\n");
        sb.append("Panel: ").append(panelTitle).append("\n");
        if (interp.metric() != null && !interp.metric().isBlank())
            sb.append("Metric: ").append(interp.metric()).append("\n");
        if (interp.unit() != null && !interp.unit().isBlank())
            sb.append("Unit: ").append(interp.unit()).append("\n");
        sb.append("Page: ").append(pageNumber).append("\n");
        sb.append("\n");

        List<String> orderedSeries = new ArrayList<>();
        for (FigureDataPoint dp : dps) {
            String s = dp.getSeries();
            if (s != null && !s.isBlank() && !orderedSeries.contains(s)) {
                orderedSeries.add(s);
            }
        }

        Map<String, Map<String, String>> entitySeriesMap = new LinkedHashMap<>();
        List<String> conflictNotes = new ArrayList<>();
        Set<ValidationStatus> panelStatuses = new LinkedHashSet<>();
        Set<NumericalProvenance> panelProvenances = new LinkedHashSet<>();

        for (FigureDataPoint dp : dps) {
            String entity = dp.getEntity() != null ? dp.getEntity() : "(unknown)";
            String series = dp.getSeries() != null ? dp.getSeries() : "Value";
            String displayVal = dp.getValue() != null ? dp.getValue() : "";

            if (dp.getValidationStatus() != null) panelStatuses.add(dp.getValidationStatus());
            if (dp.getProvenance() != null) panelProvenances.add(dp.getProvenance());

            if (dp.getValidationStatus() == ValidationStatus.CONFLICT && dp.getConflictNotes() != null) {
                conflictNotes.add(String.format("[%s - %s]: %s", entity, series, dp.getConflictNotes()));
            }

            entitySeriesMap.computeIfAbsent(entity, k -> new LinkedHashMap<>()).put(series, displayVal);
        }

        if (!orderedSeries.isEmpty()) {
            sb.append("Entity");
            for (String s : orderedSeries) sb.append(" | ").append(s);
            sb.append("\n");
            sb.append("-".repeat(60)).append("\n");
            for (Map.Entry<String, Map<String, String>> entry : entitySeriesMap.entrySet()) {
                sb.append(entry.getKey());
                for (String s : orderedSeries) {
                    sb.append(" | ").append(entry.getValue().getOrDefault(s, "—"));
                }
                sb.append("\n");
            }
        } else {
            for (FigureDataPoint dp : dps) {
                String entity = dp.getEntity() != null ? dp.getEntity() : "(unknown)";
                String period = dp.getPeriod() != null ? " (" + dp.getPeriod() + ")" : "";
                String value  = dp.getValue()  != null ? dp.getValue() : "";
                sb.append(entity).append(period).append(" | ").append(value).append("\n");
            }
        }

        Set<String> periods = dps.stream()
                .map(FigureDataPoint::getPeriod)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!periods.isEmpty()) {
            sb.append("\nPeriod(s): ").append(String.join(", ", periods)).append("\n");
        }

        if (!conflictNotes.isEmpty()) {
            sb.append("\nIntegrity Notes:\n");
            conflictNotes.forEach(n -> sb.append("- ").append(n).append("\n"));
        }

        Map<String, Object> metadata = baseMetadata("figure_panel", pageNumber, sectionHeading,
                sourceFile, sourceDocumentId);
        metadata.put("figure_id", "page_" + pageNumber + "_fig_" + candidateIndex);
        metadata.put("figure_title", figureTitle);
        metadata.put("title", figureTitle);
        metadata.put("panel", panelTitle);
        metadata.put("panel_title", panelTitle);
        metadata.put("candidate_index", candidateIndex);
        metadata.put("datapoint_count", dps.size());
        metadata.put("validation_statuses", panelStatuses.stream().map(Enum::name).collect(Collectors.joining(", ")));
        metadata.put("provenances", panelProvenances.stream().map(Enum::name).collect(Collectors.joining(", ")));

        if (!entitySeriesMap.isEmpty()) {
            metadata.put("entities_in_group", String.join(", ", entitySeriesMap.keySet()));
        }
        if (interp.metric() != null) metadata.put("metric", interp.metric());
        if (interp.unit() != null) metadata.put("unit", interp.unit());
        if (bbox != null && bbox.length == 4) {
            metadata.put("bbox", Arrays.toString(bbox));
        }

        String bboxStr = (bbox != null && bbox.length == 4)
                ? String.format("_b%.0f_%.0f_%.0f_%.0f", bbox[0], bbox[1], bbox[2], bbox[3])
                : "";
        String docPrefix = (sourceDocumentId != null && !sourceDocumentId.isBlank()) ? sourceDocumentId : (sourceFile != null ? sourceFile : "doc");
        String chunkId = deterministicId(
                docPrefix + "_fig_panel_p" + pageNumber + "_c" + candidateIndex + "_p" + panelIdx + bboxStr + "_" + figureTitle);
        return new Document(chunkId, sb.toString().trim(), metadata);
    }

    // ── Tier 3: Individual Datapoint ──────────────────────────────────────────

    private Document buildDatapointChunk(
            FigureDataPoint dp,
            String figureTitle,
            String panelTitle,
            String sectionHeading,
            int pageNumber,
            String sourceFile,
            String sourceDocumentId,
            int index,
            int candidateIndex,
            double[] bbox) {

        Map<String, Object> metadata = baseMetadata("figure", pageNumber, sectionHeading,
                sourceFile, sourceDocumentId);
        metadata.put("figure_id", "page_" + pageNumber + "_fig_" + candidateIndex);
        metadata.put("figure_title", figureTitle);
        metadata.put("title", figureTitle);
        metadata.put("extraction_status", "COMPLETE");
        metadata.put("visual_interpretation", "SUCCESS");
        metadata.put("validation_status", dp.getValidationStatus() != null ? dp.getValidationStatus().name() : "UNRESOLVED");
        metadata.put("provenance", dp.getProvenance() != null ? dp.getProvenance().name() : "UNKNOWN");
        metadata.put("candidate_index", candidateIndex);
        if (bbox != null && bbox.length == 4) {
            metadata.put("bbox", Arrays.toString(bbox));
        }

        if (dp.getFigureTitle() != null) metadata.put("figure_title", dp.getFigureTitle());
        if (dp.getPanel()       != null) metadata.put("panel", dp.getPanel());
        if (dp.getEntity()      != null) {
            metadata.put("entity", dp.getEntity());
            metadata.put("row_entity", dp.getEntity());
        }
        if (dp.getSeries()      != null) {
            metadata.put("series", dp.getSeries());
            metadata.put("column_entity", dp.getSeries());
        }
        if (dp.getMetric()      != null) metadata.put("metric", dp.getMetric());
        if (dp.getPeriod()      != null) metadata.put("period", dp.getPeriod());
        if (dp.getUnit()        != null) metadata.put("unit", dp.getUnit());
        if (dp.getValue()       != null) metadata.put("value", dp.getValue());
        if (dp.getDeterministicValue() != null) metadata.put("deterministic_value", dp.getDeterministicValue());

        String content = dp.toSemanticChunk();
        String bboxStr = (bbox != null && bbox.length == 4)
                ? String.format("_b%.0f_%.0f_%.0f_%.0f", bbox[0], bbox[1], bbox[2], bbox[3])
                : "";
        String docPrefix = (sourceDocumentId != null && !sourceDocumentId.isBlank()) ? sourceDocumentId : (sourceFile != null ? sourceFile : "doc");
        String chunkId = deterministicId(
                docPrefix + "_fig_p" + pageNumber + "_c" + candidateIndex + "_dp" + index + bboxStr + "_" + content.hashCode());
        return new Document(chunkId, content, metadata);
    }

    private Document buildDiagnosticFailureChunk(
            int pageNumber, String sectionHeading,
            String sourceFile, String sourceDocumentId, String status, String reason,
            int candidateIndex, double[] bbox) {

        Map<String, Object> metadata = baseMetadata("figure", pageNumber, sectionHeading,
                sourceFile, sourceDocumentId);
        boolean isUnresolved = "UNRESOLVED".equalsIgnoreCase(status) || (reason != null && reason.toUpperCase().contains("UNRESOLVED"));
        metadata.put("extraction_status", isUnresolved ? "UNRESOLVED" : "FAILED");
        metadata.put("visual_interpretation", isUnresolved ? "UNRESOLVED" : "FAILED");
        metadata.put("validation_status", isUnresolved ? "UNRESOLVED" : "CONFLICT");
        metadata.put("confidence", isUnresolved ? "UNRESOLVED" : "FAILED");
        metadata.put("provenance", NumericalProvenance.UNKNOWN.name());
        metadata.put("provenances", "UNRESOLVED");
        metadata.put("validation_statuses", "UNRESOLVED");
        metadata.put("candidate_index", candidateIndex);
        if (bbox != null && bbox.length == 4) {
            metadata.put("bbox", Arrays.toString(bbox));
        }

        String bboxStr = (bbox != null && bbox.length == 4)
                ? String.format("_b%.0f_%.0f_%.0f_%.0f", bbox[0], bbox[1], bbox[2], bbox[3])
                : "";
        String content = "Figure on page " + pageNumber + " (Section: " + sectionHeading
                + "): Visual relationships could not be established with confidence: " + reason;
        String docPrefix = (sourceDocumentId != null && !sourceDocumentId.isBlank()) ? sourceDocumentId : (sourceFile != null ? sourceFile : "doc");
        String chunkId = deterministicId(
                docPrefix + "_fig_fail_p" + pageNumber + "_c" + candidateIndex + bboxStr + "_" + status);
        return new Document(chunkId, content, metadata);
    }

    private void enrichDataPoint(
            FigureDataPoint dp,
            String figureTitle,
            String panelTitle,
            FigureInterpretation interp,
            int pageNumber) {
        if (dp.getFigureTitle() == null || dp.getFigureTitle().isBlank()) {
            dp.setFigureTitle(figureTitle);
        }
        if (dp.getPanel() == null || dp.getPanel().isBlank()) {
            dp.setPanel(panelTitle);
        }
        if ((dp.getMetric() == null || dp.getMetric().isBlank()) && interp.metric() != null) {
            dp.setMetric(interp.metric());
        }
        if ((dp.getUnit() == null || dp.getUnit().isBlank()) && interp.unit() != null) {
            dp.setUnit(interp.unit());
        }
        dp.setPageNumber(pageNumber);
    }

    private Map<String, Object> baseMetadata(
            String chunkType,
            int pageNumber,
            String sectionHeading,
            String sourceFile,
            String sourceDocumentId) {
        Map<String, Object> m = new HashMap<>();
        m.put("chunk_type", chunkType);
        m.put("page_number", pageNumber);
        m.put("page", pageNumber);
        m.put("section", sectionHeading != null ? sectionHeading : "");
        m.put("source", "vision");
        m.put("source_file", sourceFile != null ? sourceFile : "");
        m.put("source_document_id", sourceDocumentId != null ? sourceDocumentId : "");
        return m;
    }

    private String deterministicId(String rawKey) {
        return UUID.nameUUIDFromBytes(rawKey.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
