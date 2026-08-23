package com.alsayed.financial_advisor_ai.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Retrieves candidates using structured signals (entities, metrics, periods, table types, values)
 * directly from PostgreSQL JSONB metadata and content.
 * Provides independent structured candidate generation that does not rely on dense vector search.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuredCandidateRetriever {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final EntityRegistry entityRegistry;
    private final MetricResolver metricResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Retrieve candidates from the database matching structured query signals.
     */
    public List<ScoredDocument> retrieveByStructuredSignals(QuerySignals signals, int topK) {
        if (signals == null) {
            return List.of();
        }

        Map<String, ScoredDocument> candidateMap = new LinkedHashMap<>();

        // 1. Entity-based structured lookup (using dynamic entity registry and signals)
        Set<String> searchEntities = new LinkedHashSet<>();
        if (signals.getEntities() != null) {
            searchEntities.addAll(signals.getEntities());
        }
        if (entityRegistry != null && signals.getRawQuery() != null) {
            searchEntities.addAll(entityRegistry.extractEntitiesFromQuery(signals.getRawQuery()));
        }

        if (!searchEntities.isEmpty()) {
            for (String entity : searchEntities) {
                if (entity == null || entity.isBlank()) continue;
                String entityPattern = "%" + entity.trim() + "%";
                String cleanEntity = entity.replace(".", "").trim();
                String cleanPattern = "%" + cleanEntity + "%";

                try {
                    String sql = """
                        SELECT id, content, metadata
                        FROM vector_store
                        WHERE metadata->>'row_entity' ILIKE ?
                           OR metadata->>'row_entity' ILIKE ?
                           OR metadata->>'entities_in_group' ILIKE ?
                           OR metadata->>'entities_in_group' ILIKE ?
                           OR metadata->>'entity' ILIKE ?
                           OR metadata->>'entity' ILIKE ?
                           OR metadata->>'table_name' ILIKE ?
                           OR metadata->>'parent_category' ILIKE ?
                           OR content ILIKE ?
                        LIMIT ?
                        """;

                    jdbcTemplate.query(sql, rs -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        Document doc = new Document(id, content, metadata);

                        double score = calculateStructuredMatchScore(doc, signals, searchEntities);
                        candidateMap.compute(id, (k, old) -> old == null || score > old.score() ? new ScoredDocument(doc, score) : old);
                    }, entityPattern, cleanPattern, entityPattern, cleanPattern, entityPattern, cleanPattern, entityPattern, entityPattern, "%Entity: " + entity + "%", topK * 2);
                } catch (Exception e) {
                    log.warn("Entity metadata query failed for entity '{}': {}", entity, e.getMessage());
                }
            }
        }

        // 2. Table-Type lookup (e.g., sector tables, correlation tables)
        if (signals.getTableTypes() != null && !signals.getTableTypes().isEmpty()) {
            for (String tableType : signals.getTableTypes()) {
                String typePattern = "%" + tableType.trim() + "%";
                try {
                    String sql = """
                        SELECT id, content, metadata
                        FROM vector_store
                        WHERE metadata->>'table_type' ILIKE ?
                           OR metadata->>'table_name' ILIKE ?
                           OR metadata->>'section' ILIKE ?
                           OR metadata->>'table_title' ILIKE ?
                           OR content ILIKE ?
                        LIMIT ?
                        """;

                    jdbcTemplate.query(sql, rs -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        Document doc = new Document(id, content, metadata);

                        double score = calculateStructuredMatchScore(doc, signals);
                        candidateMap.compute(id, (k, old) -> old == null || score > old.score() ? new ScoredDocument(doc, score) : old);
                    }, typePattern, typePattern, typePattern, typePattern, "%" + tableType + "%", topK);
                } catch (Exception e) {
                    log.warn("Table type query failed for '{}': {}", tableType, e.getMessage());
                }

                // Generalized metadata lookup for table types / categories
                String categoryPattern = "%" + tableType.trim() + "%";
                try {
                    String catSql = """
                        SELECT id, content, metadata
                        FROM vector_store
                        WHERE metadata->>'table_type' ILIKE ?
                           OR metadata->>'table_name' ILIKE ?
                           OR metadata->>'section' ILIKE ?
                           OR metadata->>'table_title' ILIKE ?
                           OR metadata->>'figure_title' ILIKE ?
                           OR metadata->>'title' ILIKE ?
                           OR content ILIKE ?
                        LIMIT ?
                        """;
                    jdbcTemplate.query(catSql, rs -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        Document doc = new Document(id, content, metadata);
                        double score = calculateStructuredMatchScore(doc, signals);
                        candidateMap.compute(id, (k, old) -> old == null || score > old.score() ? new ScoredDocument(doc, score) : old);
                    }, categoryPattern, categoryPattern, categoryPattern, categoryPattern, categoryPattern, categoryPattern, "%" + tableType + "%", topK);
                } catch (Exception e) {
                    log.warn("Category SQL query failed for '{}': {}", tableType, e.getMessage());
                }
            }
        }

        // 3. Metric / Section lookup
        if (signals.getCanonicalMetric() != null || (signals.getMetric() != null && !signals.getMetric().isBlank())) {
            List<String> metricAliases = signals.getCanonicalMetric() != null
                    ? signals.getCanonicalMetric().getSearchAliases()
                    : getMetricAliases(signals.getMetric());

            for (String alias : metricAliases) {
                String pattern = "%" + alias + "%";
                try {
                    String sql = """
                        SELECT id, content, metadata
                        FROM vector_store
                        WHERE metadata->>'metric' ILIKE ?
                           OR metadata->>'section' ILIKE ?
                           OR metadata->>'figure_title' ILIKE ?
                           OR metadata->>'table_name' ILIKE ?
                           OR metadata->>'table_title' ILIKE ?
                           OR metadata->>'title' ILIKE ?
                        LIMIT 50
                        """;

                    jdbcTemplate.query(sql, rs -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        Document doc = new Document(id, content, metadata);

                        double score = calculateStructuredMatchScore(doc, signals);
                        if (score > 0) {
                            candidateMap.compute(id, (k, old) -> old == null || score > old.score() ? new ScoredDocument(doc, score) : old);
                        }
                    }, pattern, pattern, pattern, pattern, pattern, pattern);

                    if (alias.length() >= 5) {
                        String contentSql = "SELECT id, content, metadata FROM vector_store WHERE content ILIKE ? LIMIT 30";
                        jdbcTemplate.query(contentSql, rs -> {
                            String id = rs.getString("id");
                            String content = rs.getString("content");
                            String metadataJson = rs.getString("metadata");
                            Map<String, Object> metadata = parseMetadata(metadataJson);
                            Document doc = new Document(id, content, metadata);

                            double score = calculateStructuredMatchScore(doc, signals);
                            if (score > 0) {
                                candidateMap.compute(id, (k, old) -> old == null || score > old.score() ? new ScoredDocument(doc, score) : old);
                            }
                        }, pattern);
                    }
                } catch (Exception e) {
                    log.warn("Metric metadata query failed for pattern '{}': {}", pattern, e.getMessage());
                }
            }
        }

        log.debug("retrieveByStructuredSignals found {} candidates for signals: {}", candidateMap.size(), signals);
        return candidateMap.values().stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<String> getMetricAliases(String rawMetric) {
        if (rawMetric == null || rawMetric.isBlank()) return Collections.emptyList();
        List<MetricResolution> resolutions = metricResolver.resolve(rawMetric);
        if (!resolutions.isEmpty()) {
            return resolutions.get(0).canonicalMetric().getSearchAliases();
        }
        return List.of(rawMetric.trim().toLowerCase());
    }

    private double calculateStructuredMatchScore(Document doc, QuerySignals signals) {
        Set<String> searchEntities = new LinkedHashSet<>();
        if (signals != null && signals.getEntities() != null) {
            searchEntities.addAll(signals.getEntities());
        }
        if (entityRegistry != null && signals != null && signals.getRawQuery() != null) {
            searchEntities.addAll(entityRegistry.extractEntitiesFromQuery(signals.getRawQuery()));
        }
        return calculateStructuredMatchScore(doc, signals, searchEntities);
    }

    private double calculateStructuredMatchScore(Document doc, QuerySignals signals, Set<String> entitiesToCheck) {
        double score = 0.5; // Base match score
        Map<String, Object> metadata = doc.getMetadata();
        String content = doc.getText().toLowerCase();

        // Entity check
        if (entitiesToCheck != null && !entitiesToCheck.isEmpty()) {
            for (String entity : entitiesToCheck) {
                String eLower = entity.toLowerCase();
                String eClean = eLower.replace(".", "").trim();
                String rowEntity = ((String) metadata.getOrDefault("row_entity", "")).toLowerCase();
                String entitiesInGroup = ((String) metadata.getOrDefault("entities_in_group", "")).toLowerCase();
                String singleEntity = ((String) metadata.getOrDefault("entity", "")).toLowerCase();

                if (rowEntity.contains(eLower) || rowEntity.replace(".", "").contains(eClean)
                        || entitiesInGroup.contains(eLower) || entitiesInGroup.replace(".", "").contains(eClean)
                        || singleEntity.contains(eLower) || singleEntity.replace(".", "").contains(eClean)) {
                    score += 0.35;
                } else if (content.contains("entity: " + eLower) || content.contains(eLower)) {
                    score += 0.25;
                }
            }
        }

        // Canonical Metric check
        if (signals != null && (signals.getCanonicalMetric() != null || signals.getMetric() != null)) {
            List<String> metricAliases = signals.getCanonicalMetric() != null 
                    ? signals.getCanonicalMetric().getSearchAliases() 
                    : getMetricAliases(signals.getMetric());

            String metricMeta = ((String) metadata.getOrDefault("metric", "")).toLowerCase();
            String sectionMeta = ((String) metadata.getOrDefault("section", "")).toLowerCase();
            String figureTitleMeta = ((String) metadata.getOrDefault("figure_title", "")).toLowerCase();
            String tableTitleMeta = ((String) metadata.getOrDefault("table_title", "")).toLowerCase();
            String tableNameMeta = ((String) metadata.getOrDefault("table_name", "")).toLowerCase();
            String titleMeta = ((String) metadata.getOrDefault("title", "")).toLowerCase();

            boolean matchedExact = false;
            boolean matchedTitle = false;
            boolean matchedSection = false;
            boolean matchedContent = false;

            for (String alias : metricAliases) {
                if (!matchedExact && metricMeta.contains(alias)) matchedExact = true;
                if (!matchedTitle && (figureTitleMeta.contains(alias) || tableTitleMeta.contains(alias) || tableNameMeta.contains(alias) || titleMeta.contains(alias))) matchedTitle = true;
                if (!matchedSection && sectionMeta.contains(alias)) matchedSection = true;
                if (!matchedContent && content.contains(alias)) matchedContent = true;
            }

            if (matchedExact || matchedTitle) {
                score += 0.60;
            } else if (matchedSection) {
                score += 0.45;
            } else if (matchedContent) {
                score += 0.25;
            }
        }

        // Period check
        if (signals != null && signals.getPeriod() != null) {
            String pLower = signals.getPeriod().toLowerCase();
            String pAlt = pLower.contains("month") ? pLower.replace("month", "mo")
                    : pLower.contains("year") ? pLower.replace("year", "yr") : pLower;
            String pAltShort = pAlt.replace("-", "").replace(" ", "");

            String periodMeta = ((String) metadata.getOrDefault("period", "")).toLowerCase();
            if (periodMeta.contains(pLower) || periodMeta.contains(pAlt) || periodMeta.contains(pAltShort)) {
                score += 0.25;
            } else if (content.contains(pLower) || content.contains(pAlt) || content.contains(pAltShort)) {
                score += 0.20;
            }
        }

        // Structured Attribute (Tenor/Maturity) Alignment
        if (signals != null && signals.getTenors() != null && !signals.getTenors().isEmpty()) {
            String rowEntity = ((String) metadata.getOrDefault("row_entity", "")).toLowerCase();
            String entitiesInGroup = ((String) metadata.getOrDefault("entities_in_group", "")).toLowerCase();
            String singleEntity = ((String) metadata.getOrDefault("entity", "")).toLowerCase();
            Set<String> docEntityTenors = QueryAnalyzer.extractTenors(rowEntity + " " + entitiesInGroup + " " + singleEntity);
            if (!docEntityTenors.isEmpty()) {
                if (!Collections.disjoint(signals.getTenors(), docEntityTenors)) {
                    score += 0.40;
                } else {
                    score -= 0.20;
                }
            }
        }

        // Table Type check
        if (signals != null && signals.getTableTypes() != null && !signals.getTableTypes().isEmpty()) {
            for (String tableType : signals.getTableTypes()) {
                String ttLower = tableType.toLowerCase();
                String tableNameMeta = ((String) metadata.getOrDefault("table_name", "")).toLowerCase();
                String tableTitleMeta = ((String) metadata.getOrDefault("table_title", "")).toLowerCase();
                String sectionMeta = ((String) metadata.getOrDefault("section", "")).toLowerCase();
                String tableTypeMeta = ((String) metadata.getOrDefault("table_type", "")).toLowerCase();
                String entitiesInGroup = ((String) metadata.getOrDefault("entities_in_group", "")).toLowerCase();

                if (tableNameMeta.contains(ttLower) || tableTitleMeta.contains(ttLower)
                        || sectionMeta.contains(ttLower) || tableTypeMeta.contains(ttLower)) {
                    score += 0.35;
                } else if (content.contains(ttLower)) {
                    score += 0.20;
                } else if (ttLower.contains("sector") && (entitiesInGroup.contains("utilities") || entitiesInGroup.contains("financials") || content.contains("utilities") || content.contains("financials"))) {
                    score += 0.30;
                }
            }
        }

        return score;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse metadata JSON: {}", json);
            return new HashMap<>();
        }
    }

    public record ScoredDocument(Document document, double score) {}

    /**
     * Retrieve candidates using structured signals from the query (comparative path).
     */
    public List<Document> retrieveBySignals(QuerySignals signals, int topK) {
        if (!signals.isComparative() || signals.getMetric() == null) {
            log.debug("Query is not comparative or lacks metric, falling back to vector search");
            return vectorSearchFallback(signals, topK);
        }

        log.debug("Performing structured retrieval with signals: {}", signals);

        // Build a metadata-filtered search request
        // Note: pgvector supports metadata filtering, but we need to construct it properly
        // For now, we'll use vector search with a broader query and filter results
        
        // Use entities and metric for vector search
        StringBuilder searchQuery = new StringBuilder();
        
        if (signals.getEntities() != null && !signals.getEntities().isEmpty()) {
            searchQuery.append(String.join(" ", signals.getEntities())).append(" ");
        }
        
        if (signals.getMetric() != null) {
            searchQuery.append(signals.getMetric()).append(" ");
        }
        
        if (signals.getPeriod() != null) {
            searchQuery.append(signals.getPeriod()).append(" ");
        }
        
        if (signals.getTableTypes() != null && !signals.getTableTypes().isEmpty()) {
            searchQuery.append(String.join(" ", signals.getTableTypes()));
        }
        
        log.debug("Vector search query: '{}'", searchQuery.toString());
        
        // Perform vector search with broader topK to get more candidates
        List<Document> candidates = vectorSearch(searchQuery.toString(), topK * 10);
        log.debug("Vector search returned {} candidates", candidates.size());
        
        // If vector search returns no results, try a simpler query
        if (candidates.isEmpty()) {
            log.warn("Vector search returned no results, trying simpler query");
            StringBuilder simpleQuery = new StringBuilder();
            if (signals.getEntities() != null && !signals.getEntities().isEmpty()) {
                simpleQuery.append(String.join(" ", signals.getEntities()));
            }
            if (signals.getPeriod() != null) {
                simpleQuery.append(" ").append(signals.getPeriod());
            }
            candidates = vectorSearch(simpleQuery.toString(), topK * 10);
            log.debug("Simplified vector search returned {} candidates", candidates.size());
        }
        
        // Log sample candidate metadata for debugging
        if (!candidates.isEmpty()) {
            Document sample = candidates.get(0);
            log.debug("Sample candidate metadata: {}", sample.getMetadata());
        }
        
        // Filter candidates by metadata signals
        List<Document> filtered = filterByMetadata(candidates, signals);
        
        log.debug("Structured retrieval: {} candidates -> {} after filtering", 
            candidates.size(), filtered.size());
        
        return filtered.stream()
            .limit(topK)
            .collect(Collectors.toList());
    }

    /**
     * Filter candidates by metadata signals.
     * Uses a more lenient approach: requires metric and period, but entity matching is optional.
     */
    private List<Document> filterByMetadata(List<Document> candidates, QuerySignals signals) {
        List<Document> filtered = candidates.stream()
            .filter(doc -> {
                boolean metricMatch = matchesMetric(doc, signals.getMetric());
                boolean periodMatch = matchesPeriod(doc, signals.getPeriod());
                
                // Table type matching is optional
                boolean tableTypeMatch = signals.getTableTypes() == null || signals.getTableTypes().isEmpty() || 
                                       matchesTableType(doc, signals.getTableTypes());
                
                if (!metricMatch) {
                    log.debug("Metric mismatch for doc {}: metric={}, expected={}", 
                        doc.getId(), doc.getMetadata().get("metric"), signals.getMetric());
                }
                if (!periodMatch) {
                    log.debug("Period mismatch for doc {}: period={}, expected={}", 
                        doc.getId(), doc.getMetadata().get("period"), signals.getPeriod());
                }
                
                // Require metric and period, but table type is optional
                return metricMatch && periodMatch && tableTypeMatch;
            })
            .collect(Collectors.toList());
        
        log.debug("Filter results: {} candidates -> {} after filtering", 
            candidates.size(), filtered.size());
        
        return filtered;
    }

    /**
     * Check if document matches the specified metric.
     */
    private boolean matchesMetric(Document doc, String metric) {
        if (metric == null) return true;
        
        String docMetric = (String) doc.getMetadata().get("metric");
        String docText = doc.getText().toLowerCase();
        
        // Check metadata if available
        if (docMetric != null) {
            String docMetricLower = docMetric.toLowerCase();
            String metricLower = metric.toLowerCase();
            
            // Exact match
            if (docMetricLower.equals(metricLower)) return true;
            
            // Partial match (e.g., "total return" matches "Return (%)")
            if (docMetricLower.contains(metricLower) || metricLower.contains(docMetricLower)) return true;
            
            // Synonym matching
            if (metricLower.contains("return") && docMetricLower.contains("return")) return true;
            if (metricLower.contains("yield") && docMetricLower.contains("yield")) return true;
            if (metricLower.contains("spread") && docMetricLower.contains("spread")) return true;
        }
        
        // Fallback: check if metric appears in content
        return docText.contains(metric.toLowerCase());
    }

    /**
     * Check if document matches the specified period.
     */
    private boolean matchesPeriod(Document doc, String period) {
        if (period == null) return true;
        
        String docPeriod = (String) doc.getMetadata().get("period");
        if (docPeriod == null) {
            // Fallback: check if period appears in content
            return doc.getText().toLowerCase().contains(period.toLowerCase());
        }
        
        return docPeriod.toLowerCase().contains(period.toLowerCase()) ||
               period.toLowerCase().contains(docPeriod.toLowerCase());
    }

    /**
     * Check if document matches the specified entities.
     */
    private boolean matchesEntities(Document doc, Set<String> entities) {
        if (entities == null || entities.isEmpty()) return true;
        
        String rowEntity = (String) doc.getMetadata().get("row_entity");
        String columnEntity = (String) doc.getMetadata().get("column_entity");
        String docText = doc.getText().toLowerCase();
        
        for (String entity : entities) {
            String entityLower = entity.toLowerCase();
            
            // Check row_entity metadata
            if (rowEntity != null && rowEntity.toLowerCase().contains(entityLower)) {
                return true;
            }
            
            // Check column_entity metadata (for correlation matrices)
            if (columnEntity != null && columnEntity.toLowerCase().contains(entityLower)) {
                return true;
            }
            
            // Fallback: check content
            if (docText.contains(entityLower)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Check if document matches the specified table type.
     */
    private boolean matchesTableType(Document doc, Set<String> tableTypes) {
        if (tableTypes == null || tableTypes.isEmpty()) return true;
        
        String section = (String) doc.getMetadata().get("section");
        if (section == null) return true;
        
        String sectionLower = section.toLowerCase();
        for (String tableType : tableTypes) {
            if (sectionLower.contains(tableType.toLowerCase())) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Fallback to vector search when structured retrieval is not applicable.
     */
    private List<Document> vectorSearchFallback(QuerySignals signals, int topK) {
        StringBuilder query = new StringBuilder();
        
        if (signals.getEntities() != null) {
            query.append(String.join(" ", signals.getEntities())).append(" ");
        }
        
        if (signals.getMetric() != null) {
            query.append(signals.getMetric()).append(" ");
        }
        
        if (signals.getPeriod() != null) {
            query.append(signals.getPeriod());
        }
        
        return vectorSearch(query.toString(), topK);
    }

    /**
     * Perform vector search.
     */
    private List<Document> vectorSearch(String query, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.25) // Lower threshold for broader retrieval
                .build();
        
        return vectorStore.similaritySearch(searchRequest);
    }

    /**
     * Extract numeric value from document metadata.
     */
    public Double extractNumericValue(Document doc) {
        String valueStr = (String) doc.getMetadata().get("value");
        if (valueStr == null) {
            return null;
        }
        
        try {
            // Clean the value string (remove %, commas, etc.)
            String cleaned = valueStr.replaceAll("[%,\\s]", "");
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            log.debug("Failed to parse numeric value from: {}", valueStr);
            return null;
        }
    }

    /**
     * Get row entity from document.
     */
    public String getRowEntity(Document doc) {
        return (String) doc.getMetadata().get("row_entity");
    }

    /**
     * Get column entity from document (for correlation matrices).
     */
    public String getColumnEntity(Document doc) {
        return (String) doc.getMetadata().get("column_entity");
    }

    /**
     * Get metric from document.
     */
    public String getMetric(Document doc) {
        return (String) doc.getMetadata().get("metric");
    }

    /**
     * Get period from document.
     */
    public String getPeriod(Document doc) {
        return (String) doc.getMetadata().get("period");
    }

    /**
     * Get table ID from document.
     */
    public String getTableId(Document doc) {
        return (String) doc.getMetadata().get("table_id");
    }
}
