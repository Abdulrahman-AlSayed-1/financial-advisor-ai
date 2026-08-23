package com.alsayed.financial_advisor_ai.chunking;

import com.alsayed.financial_advisor_ai.chunking.table.TableChunkPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts {@link CanonicalTable} representations into semantically rich chunks.
 *
 * <h3>Two-Tier Hierarchy</h3>
 * <pre>
 * CanonicalTable
 * ├── Tier 1: Table Summary Chunk  [ALWAYS]
 * │            title | section | page | headers | entities | row-count
 * └── Tier 2: Row Data Chunks
 *              Small table (rows <= policy.smallTableThreshold): one chunk per row
 *              Large table: rows grouped into bounded chunks of policy.rowGroupSize
 *              Every value is preserved — no silent discarding.
 * </pre>
 *
 * <p>Correlation matrices continue to receive cell-pair chunks as before,
 * but now also get a summary chunk.
 *
 * <p>Previous behaviour (1 chunk per cell) caused page 28 to produce 4,766 chunks
 * for a single historical yield table.  This implementation reduces that to ~26.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanonicalTableChunker {

    private final TableChunkPolicy policy;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Convert a CanonicalTable into a list of Document chunks.
     *
     * @param table                The CanonicalTable to chunk
     * @param sourceDocumentId     Stable document identifier for idempotent ingestion
     * @param sourceElementIndices Provenance tracking (may be empty)
     * @param sourceDocumentIds    Provenance tracking (may be empty)
     * @param sourcePages          Provenance tracking (may be empty)
     * @param sectionElementIndices Section heading provenance (may be empty)
     * @param sectionDocumentIds    Section heading provenance (may be empty)
     * @param sectionPages          Section heading provenance (may be empty)
     * @return ordered list: [table_summary, row_chunks...]
     */
    public List<Document> chunk(CanonicalTable table,
                                String sourceDocumentId,
                                List<String> sourceElementIndices,
                                List<String> sourceDocumentIds,
                                List<Integer> sourcePages,
                                List<String> sectionElementIndices,
                                List<String> sectionDocumentIds,
                                List<Integer> sectionPages) {

        List<Document> chunks = new ArrayList<>();

        if (table == null || table.getTableId() == null) {
            return chunks;
        }

        int rowCount = table.getRows() == null ? 0 : table.getRows().size();

        // ── Tier 1: Table Summary Chunk ───────────────────────────────────────
        if (policy.isAlwaysCreateSummary()) {
            chunks.add(buildTableSummaryChunk(table, sourceDocumentId,
                    sourceElementIndices, sourceDocumentIds, sourcePages,
                    sectionElementIndices, sectionDocumentIds, sectionPages));
        }

        if (rowCount == 0) {
            log.warn("Table {} has no rows — only summary chunk emitted", table.getTableId());
            return chunks;
        }

        // ── Tier 2: Row Data Chunks ───────────────────────────────────────────
        if ("correlation".equals(table.getTableType())) {
            // Correlation matrices: cell-pair chunks (behaviour unchanged, plus summary above)
            chunks.addAll(chunkCorrelationMatrix(table, sourceDocumentId,
                    sourceElementIndices, sourceDocumentIds, sourcePages,
                    sectionElementIndices, sectionDocumentIds, sectionPages));
        } else if (policy.isSmallTable(rowCount)) {
            // Small table: one chunk per row (precise, fine for small tables)
            chunks.addAll(chunkRowsIndividually(table, sourceDocumentId,
                    sourceElementIndices, sourceDocumentIds, sourcePages,
                    sectionElementIndices, sectionDocumentIds, sectionPages));
        } else {
            // Large table: grouped row chunks (data-preserving, explosion-resistant)
            chunks.addAll(chunkRowsGrouped(table, sourceDocumentId,
                    sourceElementIndices, sourceDocumentIds, sourcePages,
                    sectionElementIndices, sectionDocumentIds, sectionPages));
        }

        log.info("CanonicalTableChunker: table={} type={} rows={} → {} chunks",
                table.getTableId(), table.getTableType(), rowCount, chunks.size());

        return chunks;
    }

    // ── BACKWARD-COMPATIBLE overload (no sourceDocumentId) ───────────────────
    /**
     * Backward-compatible overload for call-sites that have not yet been updated
     * to pass sourceDocumentId.  Passes an empty string, which is valid but
     * means chunks won't be scoped to a document during idempotent deletion.
     */
    public List<Document> chunk(CanonicalTable table,
                                List<String> sourceElementIndices,
                                List<String> sourceDocumentIds,
                                List<Integer> sourcePages,
                                List<String> sectionElementIndices,
                                List<String> sectionDocumentIds,
                                List<Integer> sectionPages) {
        return chunk(table, "", sourceElementIndices, sourceDocumentIds, sourcePages,
                sectionElementIndices, sectionDocumentIds, sectionPages);
    }

    // ── Tier 1: Table Summary ─────────────────────────────────────────────────

    private Document buildTableSummaryChunk(CanonicalTable table,
                                             String sourceDocumentId,
                                             List<String> sourceElementIndices,
                                             List<String> sourceDocumentIds,
                                             List<Integer> sourcePages,
                                             List<String> sectionElementIndices,
                                             List<String> sectionDocumentIds,
                                             List<Integer> sectionPages) {

        int rowCount   = table.getRows() == null ? 0 : table.getRows().size();
        int colCount   = table.getHeaderLevels() == null || table.getHeaderLevels().isEmpty()
                ? 0 : table.getHeaderLevels().get(0).length;

        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(table.getTitle()).append("\n");
        sb.append("Section: ").append(table.getSection()).append("\n");
        sb.append("Page: ").append(table.getPageNumber()).append("\n");
        sb.append("Table ID: ").append(table.getTableId()).append("\n");
        sb.append("Rows: ").append(rowCount).append(" | Columns: ").append(colCount).append("\n");

        // Column headers (all levels joined)
        if (table.getHeaderLevels() != null && !table.getHeaderLevels().isEmpty()) {
            sb.append("\nColumns:\n");
            // Use first header level as primary label
            for (String h : table.getHeaderLevels().get(0)) {
                if (h != null && !h.isBlank()) {
                    sb.append("- ").append(h.trim()).append("\n");
                }
            }
        }

        // All unique row entities
        if (table.getRows() != null) {
            Set<String> entities = table.getRows().stream()
                    .map(CanonicalTable.CanonicalRow::getRowLabel)
                    .filter(l -> l != null && !l.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!entities.isEmpty()) {
                sb.append("\nEntities (").append(entities.size()).append("):\n");
                entities.forEach(e -> sb.append("- ").append(e).append("\n"));
            }
        }

        Map<String, Object> metadata = baseMetadata("table_summary", table, sourceDocumentId);
        addProvenanceMetadata(metadata, sourceElementIndices, sourceDocumentIds, sourcePages,
                sectionElementIndices, sectionDocumentIds, sectionPages);

        String chunkId = deterministicId(table.getTableId() + "-summary");
        return new Document(chunkId, sb.toString().trim(), metadata);
    }

    private List<Document> chunkRowsIndividually(CanonicalTable table,
                                                  String sourceDocumentId,
                                                  List<String> sourceElementIndices,
                                                  List<String> sourceDocumentIds,
                                                  List<Integer> sourcePages,
                                                  List<String> sectionElementIndices,
                                                  List<String> sectionDocumentIds,
                                                  List<Integer> sectionPages) {

        List<Document> chunks = new ArrayList<>();
        if (table.getRows() == null) return chunks;

        for (CanonicalTable.CanonicalRow row : table.getRows()) {
            String content = renderRow(table, row);
            if (content == null || content.isBlank()) continue;

            Map<String, Object> metadata = baseMetadata("table", table, sourceDocumentId);
            metadata.put("row_index", row.getRowIndex());
            metadata.put("row_entity", row.getRowLabel());
            if (row.getCategory() != null && !row.getCategory().isBlank()) {
                metadata.put("row_category", row.getCategory());
                metadata.put("parent_category", row.getCategory());
            } else if (table.getTitle() != null && !table.getTitle().isBlank() && isAnonymousEntity(row.getRowLabel())) {
                metadata.put("row_category", table.getTitle());
                metadata.put("parent_category", table.getTitle());
            }
            // First cell's metric/period/unit as metadata hints
            if (row.getCells() != null && !row.getCells().isEmpty()) {
                CanonicalTable.CanonicalCell first = row.getCells().get(0);
                if (first.getMetric() != null && !first.getMetric().isBlank())
                    metadata.put("metric", first.getMetric());
                if (first.getPeriod() != null && !first.getPeriod().isBlank())
                    metadata.put("period", first.getPeriod());
                if (first.getUnit() != null && !first.getUnit().isBlank())
                    metadata.put("unit", first.getUnit());
            }
            addProvenanceMetadata(metadata, sourceElementIndices, sourceDocumentIds, sourcePages,
                    sectionElementIndices, sectionDocumentIds, sectionPages);

            String chunkId = deterministicId(table.getTableId() + "-row" + row.getRowIndex());
            chunks.add(new Document(chunkId, content.trim(), metadata));
        }
        return chunks;
    }

    // ── Tier 2b: Large Table — Grouped Row Chunks ─────────────────────────────

    private List<Document> chunkRowsGrouped(CanonicalTable table,
                                             String sourceDocumentId,
                                             List<String> sourceElementIndices,
                                             List<String> sourceDocumentIds,
                                             List<Integer> sourcePages,
                                             List<String> sectionElementIndices,
                                             List<String> sectionDocumentIds,
                                             List<Integer> sectionPages) {

        List<Document> chunks = new ArrayList<>();
        List<CanonicalTable.CanonicalRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) return chunks;

        // Estimate average rendered row length to adapt group size
        int sampleSize = Math.min(5, rows.size());
        int totalLen   = rows.subList(0, sampleSize).stream()
                .mapToInt(r -> renderRow(table, r) == null ? 0 : renderRow(table, r).length())
                .sum();
        int avgRowLen  = sampleSize > 0 ? totalLen / sampleSize : 80;
        int groupSize  = policy.effectiveGroupSize(rows.size(), avgRowLen);

        log.debug("Table {} groupSize={} (avgRowLen={}, rowCount={})",
                table.getTableId(), groupSize, avgRowLen, rows.size());

        int groupIdx = 0;
        for (int start = 0; start < rows.size(); start += groupSize) {
            int end = Math.min(start + groupSize, rows.size());
            List<CanonicalTable.CanonicalRow> group = rows.subList(start, end);

            StringBuilder sb = new StringBuilder();
            // Group header
            sb.append("Table: ").append(table.getTitle()).append("\n");
            sb.append("Section: ").append(table.getSection()).append("\n");
            sb.append("Page: ").append(table.getPageNumber()).append("\n");
            sb.append("Table ID: ").append(table.getTableId()).append("\n");
            sb.append("Rows ").append(start + 1).append("–").append(end)
              .append(" of ").append(rows.size()).append("\n\n");

            // Data rows rendered with explicit key-value attributes for semantic clarity
            for (CanonicalTable.CanonicalRow row : group) {
                if (row == null) continue;
                if (row.getCategory() != null && !row.getCategory().isBlank()) {
                    sb.append("Category: ").append(row.getCategory()).append("\n");
                }
                sb.append("Entity: ").append(row.getRowLabel()).append("\n");
                if (row.getCells() != null) {
                    for (CanonicalTable.CanonicalCell cell : row.getCells()) {
                        sb.append(renderCell(cell));
                    }
                }
                sb.append("\n");
            }

            String content = sb.toString().trim();
            if (content.isBlank()) continue;

            Map<String, Object> metadata = baseMetadata("table", table, sourceDocumentId);
            metadata.put("group_index", groupIdx);
            metadata.put("row_start", start);
            metadata.put("row_end", end - 1);
            metadata.put("row_count_in_group", group.size());
            // Entities in this group
            String entities = group.stream()
                    .map(CanonicalTable.CanonicalRow::getRowLabel)
                    .filter(l -> l != null && !l.isBlank())
                    .collect(Collectors.joining(", "));
            if (!entities.isBlank()) metadata.put("entities_in_group", entities);

            addProvenanceMetadata(metadata, sourceElementIndices, sourceDocumentIds, sourcePages,
                    sectionElementIndices, sectionDocumentIds, sectionPages);

            String chunkId = deterministicId(table.getTableId() + "-group" + groupIdx);
            chunks.add(new Document(chunkId, content, metadata));
            groupIdx++;
        }
        return chunks;
    }

    // ── Correlation Matrix ────────────────────────────────────────────────────

    private List<Document> chunkCorrelationMatrix(CanonicalTable table,
                                                   String sourceDocumentId,
                                                   List<String> sourceElementIndices,
                                                   List<String> sourceDocumentIds,
                                                   List<Integer> sourcePages,
                                                   List<String> sectionElementIndices,
                                                   List<String> sectionDocumentIds,
                                                   List<Integer> sectionPages) {

        List<Document> chunks = new ArrayList<>();
        if (table.getRows() == null) return chunks;

        // Extract column entities from first header level
        List<String> columnEntities = new ArrayList<>();
        if (table.getHeaderLevels() != null && !table.getHeaderLevels().isEmpty()) {
            for (String h : table.getHeaderLevels().get(0)) {
                if (h != null && !h.isBlank()) columnEntities.add(h.trim());
            }
        }

        for (CanonicalTable.CanonicalRow row : table.getRows()) {
            if (row.getCells() == null) continue;
            String rowEntity = row.getRowLabel();

            for (int i = 0; i < row.getCells().size(); i++) {
                CanonicalTable.CanonicalCell cell = row.getCells().get(i);

                // Skip diagonal (self-correlation)
                if (i < columnEntities.size() && rowEntity.equalsIgnoreCase(columnEntities.get(i))) {
                    continue;
                }

                String colEntity = (i < columnEntities.size()) ? columnEntities.get(i) : "Col" + i;
                String metric    = resolveCorrelationMetric(cell.getMetric(), rowEntity, colEntity, table.getSection());

                StringBuilder sb = new StringBuilder();
                sb.append("Table: ").append(table.getTitle()).append("\n");
                sb.append("Section: ").append(table.getSection()).append("\n");
                sb.append("Page: ").append(table.getPageNumber()).append("\n");
                sb.append("Table ID: ").append(table.getTableId()).append("\n\n");
                sb.append("Row Entity: ").append(rowEntity).append("\n");
                sb.append("Column Entity: ").append(colEntity).append("\n");
                sb.append("Metric: ").append(metric).append("\n");
                if (cell.getPeriod() != null && !cell.getPeriod().isBlank()) {
                    sb.append("Period: ").append(cell.getPeriod()).append("\n");
                }
                sb.append("Value: ").append(cell.getValue()).append("\n");

                Map<String, Object> metadata = baseMetadata("correlation_table", table, sourceDocumentId);
                metadata.put("row_index",    row.getRowIndex());
                metadata.put("column_index", cell.getColumnIndex());
                metadata.put("row_entity",   rowEntity);
                metadata.put("column_entity", colEntity);
                metadata.put("metric",       metric);
                if (cell.getPeriod() != null) metadata.put("period", cell.getPeriod());
                if (cell.getValue()  != null) metadata.put("value",  cell.getValue());

                addProvenanceMetadata(metadata, sourceElementIndices, sourceDocumentIds, sourcePages,
                        sectionElementIndices, sectionDocumentIds, sectionPages);

                String chunkId = deterministicId(table.getTableId() + "-row" + row.getRowIndex() + "-col" + i);
                chunks.add(new Document(chunkId, sb.toString().trim(), metadata));
            }
        }
        return chunks;
    }

    // ── Row Rendering Helpers ─────────────────────────────────────────────────

    private static final Set<String> ANONYMOUS_ENTITIES = Set.of(
            "5y", "10y", "30y", "2y", "3y", "7y", "1y", "aaa", "aa", "a", "bbb", "bb", "b", "ccc", "cc", "c"
    );

    private boolean isAnonymousEntity(String entity) {
        if (entity == null) return false;
        String lower = entity.trim().toLowerCase();
        return ANONYMOUS_ENTITIES.contains(lower) || lower.matches("^\\d+-year$") || lower.matches("^\\d+-yr$");
    }

    /**
     * Render a row as a full self-describing paragraph (used for small-table individual chunks).
     */
    private String renderRow(CanonicalTable table, CanonicalTable.CanonicalRow row) {
        if (row == null) return null;

        StringBuilder sb = new StringBuilder();
        if (table.getSection() != null && !table.getSection().isBlank()) {
            sb.append("Section: ").append(table.getSection()).append("\n");
        }
        if (table.getTitle() != null && !table.getTitle().isBlank()) {
            sb.append("Table: ").append(table.getTitle()).append("\n");
        }
        sb.append("Page: ").append(table.getPageNumber()).append("\n");
        sb.append("Table ID: ").append(table.getTableId()).append("\n");

        if (row.getCategory() != null && !row.getCategory().isBlank()) {
            sb.append("Category: ").append(row.getCategory()).append("\n");
        } else if (table.getTitle() != null && !table.getTitle().isBlank() && isAnonymousEntity(row.getRowLabel())) {
            sb.append("Category: ").append(table.getTitle()).append("\n");
        }
        sb.append("Entity: ").append(row.getRowLabel()).append("\n");

        if (row.getCells() != null) {
            for (CanonicalTable.CanonicalCell cell : row.getCells()) {
                sb.append(renderCell(cell));
            }
        }
        return sb.toString();
    }

    /**
     * Render a row as a compact pipe-delimited line (used for grouped chunks).
     * Format: "EntityName | v1 | v2 | ..."
     */
    private String renderGroupLine(CanonicalTable table, CanonicalTable.CanonicalRow row) {
        if (row == null) return null;
        StringBuilder sb = new StringBuilder();
        if (row.getCategory() != null && !row.getCategory().isBlank()) {
            sb.append("[").append(row.getCategory()).append("] ");
        }
        sb.append(row.getRowLabel() != null ? row.getRowLabel() : "");
        if (row.getCells() != null) {
            for (CanonicalTable.CanonicalCell cell : row.getCells()) {
                sb.append(" | ").append(cell.getValue() != null ? cell.getValue() : "—");
            }
        }
        return sb.toString();
    }

    private String renderCell(CanonicalTable.CanonicalCell cell) {
        if (cell == null) return "";
        StringBuilder sb = new StringBuilder();
        String label = (cell.getColumnPath() != null && !cell.getColumnPath().isBlank())
                ? cell.getColumnPath()
                : (cell.getMetric() != null && !cell.getMetric().isBlank() ? cell.getMetric() : "Value");
        sb.append("  ").append(label);
        if (cell.getPeriod() != null && !cell.getPeriod().isBlank() && !label.contains(cell.getPeriod())) {
            sb.append(" (").append(cell.getPeriod()).append(")");
        }
        if (cell.getUnit() != null && !cell.getUnit().isBlank() && !label.contains(cell.getUnit())) {
            sb.append(" [").append(cell.getUnit()).append("]");
        }
        sb.append(": ").append(cell.getValue() != null ? cell.getValue() : "").append("\n");
        return sb.toString();
    }

    // ── Metadata Helpers ──────────────────────────────────────────────────────

    /**
     * Build the standard provenance metadata every chunk must carry.
     * source = "pymupdf" for table chunks (extracted deterministically by PyMuPDF).
     */
    private Map<String, Object> baseMetadata(String chunkType, CanonicalTable table, String sourceDocumentId) {
        Map<String, Object> m = new HashMap<>();
        m.put("chunk_type",         chunkType);
        m.put("content_type",       "table");
        m.put("page_number",        table.getPageNumber());
        m.put("section",            table.getSection() != null ? table.getSection() : "");
        m.put("table_name",         table.getTitle() != null ? table.getTitle() : "");
        m.put("table_id",           table.getTableId());
        m.put("source",             "pymupdf");
        m.put("source_file",        table.getTableId()); // table_id encodes the source file reference
        m.put("source_document_id", sourceDocumentId != null ? sourceDocumentId : "");
        return m;
    }

    private void addProvenanceMetadata(Map<String, Object> m,
                                       List<String> sourceElementIndices,
                                       List<String> sourceDocumentIds,
                                       List<Integer> sourcePages,
                                       List<String> sectionElementIndices,
                                       List<String> sectionDocumentIds,
                                       List<Integer> sectionPages) {
        if (!sourceElementIndices.isEmpty()) m.put("source_element_indices", new ArrayList<>(sourceElementIndices));
        if (!sourceDocumentIds.isEmpty())    m.put("source_document_ids",    new ArrayList<>(sourceDocumentIds));
        if (!sourcePages.isEmpty())          m.put("source_pages",           new ArrayList<>(sourcePages));
        if (!sectionElementIndices.isEmpty()) m.put("section_element_indices", new ArrayList<>(sectionElementIndices));
        if (!sectionDocumentIds.isEmpty())   m.put("section_document_ids",   new ArrayList<>(sectionDocumentIds));
        if (!sectionPages.isEmpty())         m.put("section_pages",          new ArrayList<>(sectionPages));
    }

    private String resolveCorrelationMetric(String raw, String rowEntity, String colEntity, String section) {
        if (raw != null && !raw.isBlank()
                && !raw.equalsIgnoreCase(rowEntity)
                && !raw.equalsIgnoreCase(colEntity)) {
            return raw;
        }
        return (section != null && section.contains("Correlation"))
                ? "Return Correlation" : "Return Correlation";
    }

    /** Deterministic UUID-v3-style ID from an arbitrary seed string. */
    private String deterministicId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
