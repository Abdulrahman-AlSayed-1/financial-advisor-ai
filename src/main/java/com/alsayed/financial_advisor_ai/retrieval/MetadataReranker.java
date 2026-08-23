package com.alsayed.financial_advisor_ai.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reranker that uses metadata signals to improve retrieval precision.
 * Scores documents based on entity, metric, period, and section matching.
 */
@Slf4j
@Component
public class MetadataReranker {

    private final EntityRegistry entityRegistry;
    private final QueryAnalyzer queryAnalyzer;

    @org.springframework.beans.factory.annotation.Autowired
    public MetadataReranker(EntityRegistry entityRegistry, QueryAnalyzer queryAnalyzer) {
        this.entityRegistry = entityRegistry;
        this.queryAnalyzer = queryAnalyzer;
    }

    /**
     * Rerank documents based on metadata signals extracted from the query.
     * Returns documents sorted by their reranked scores.
     */
    public List<RerankedDocument> rerank(String query, List<Document> candidates) {
        QuerySignals signals = extractQuerySignals(query);
        
        List<RerankedDocument> reranked = new ArrayList<>();
        for (Document doc : candidates) {
            double score = calculateRerankScore(doc, signals);
            reranked.add(new RerankedDocument(doc, score));
        }
        
        // Sort by reranked score
        reranked.sort(Comparator.comparingDouble(RerankedDocument::rerankedScore).reversed());
        
        log.debug("Reranked {} documents for query: '{}', signals: {}", 
            reranked.size(), query, signals);
        
        return reranked;
    }

    /**
     * Extract signals from query using QueryAnalyzer.
     */
    private QuerySignals extractQuerySignals(String query) {
        if (queryAnalyzer != null) {
            return queryAnalyzer.analyze(query);
        }
        QuerySignals signals = new QuerySignals();
        signals.setRawQuery(query);
        return signals;
    }

    /**
     * Extract period terms from query.
     */
    private Set<String> extractPeriods(String query) {
        Set<String> periods = new HashSet<>();
        String[] periodPatterns = {
            "ytd", "1-year", "1y", "3-year", "3y", "5-year", "5y", "10-year", "10y",
            "current", "12-mo", "1-mo", "3-mo", "average", "median"
        };
        
        for (String pattern : periodPatterns) {
            if (query.contains(pattern)) {
                periods.add(pattern);
            }
        }
        
        return periods;
    }

    /**
     * Extract numeric values from query.
     */
    private Set<String> extractNumbers(String query) {
        Set<String> numbers = new HashSet<>();
        Pattern pattern = Pattern.compile("\\d+\\.?\\d*%");
        Matcher matcher = pattern.matcher(query);
        
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        
        return numbers;
    }

    /**
     * Extract entity-like terms using the dynamic entity registry.
     * This replaces hardcoded entity lists with dynamic extraction from document metadata.
     */
    private Set<String> extractEntities(String query) {
        // Use dynamic entity registry for matching
        Set<String> matchedEntities = entityRegistry.extractEntitiesFromQuery(query);
        
        log.debug("Extracted entities from query using registry: {}", matchedEntities);
        return matchedEntities;
    }

    /**
     * Extract metric-like terms from query.
     */
    private Set<String> extractMetrics(String query) {
        Set<String> metrics = new HashSet<>();
        String[] metricPatterns = {
            "return", "yield", "spread", "correlation", "default rate", "bps", "basis points",
            "total return", "ytd return"
        };
        
        for (String pattern : metricPatterns) {
            if (query.contains(pattern)) {
                metrics.add(pattern);
            }
        }
        if (query.contains("default")) {
            metrics.add("default");
            metrics.add("default rate");
        }
        
        return metrics;
    }

    /**
     * Extract table type hints from query.
     */
    private Set<String> extractTableTypes(String query) {
        Set<String> tableTypes = new HashSet<>();
        String lowerQuery = query.toLowerCase();
        
        if (lowerQuery.contains("sector")) {
            tableTypes.add("sector");
        }
        if (lowerQuery.contains("asset class")) {
            tableTypes.add("asset_class");
        }
        if (lowerQuery.contains("correlation")) {
            tableTypes.add("correlation");
        }
        if (lowerQuery.matches("(?i).*\\b(target rate|target range|policy rates?|central bank rates?|central bank policy rates?|fed funds|federal funds)\\b.*")) {
            tableTypes.add("central bank policy rates");
        }
        
        return tableTypes;
    }

    /**
     * Calculate rerank score based on metadata signals.
     */
    private double calculateRerankScore(Document doc, QuerySignals signals) {
        double score = 0.0;
        String docText = doc.getText().toLowerCase();
        Map<String, Object> metadata = doc.getMetadata();
        
        // Entity matching - check metadata first, then text
        String rowEntity = ((String) metadata.getOrDefault("row_entity", "")).toLowerCase();
        String columnEntity = ((String) metadata.getOrDefault("column_entity", "")).toLowerCase();
        String entitiesInGroup = ((String) metadata.getOrDefault("entities_in_group", "")).toLowerCase();
        String parentCategory = ((String) metadata.getOrDefault("parent_category", "")).toLowerCase();
        String tableName = ((String) metadata.getOrDefault("table_name", "")).toLowerCase();

        // Entity matching
        boolean hasQueryEntities = signals.getEntities() != null && !signals.getEntities().isEmpty();
        boolean matchedAnyEntity = false;

        if (hasQueryEntities) {
            for (String entity : signals.getEntities()) {
                String eLower = entity.toLowerCase().trim();
                String eClean = eLower.replace(".", "").trim();

                // Exact row entity match in data chunk
                if (docText.contains("entity: " + eLower) || rowEntity.equals(eLower) || rowEntity.equals(eClean)) {
                    score += 4.00; // Massive boost for ground-truth target entity row chunk
                    matchedAnyEntity = true;
                    break;
                }

                Set<String> aliases = entityRegistry != null ? entityRegistry.getCanonicalAliases(entity) : Set.of(entity);
                for (String alias : aliases) {
                    String cleanAlias = alias.replace(".", "").trim();

                    if (!rowEntity.isEmpty() && (rowEntity.contains(alias) || rowEntity.replace(".", "").contains(cleanAlias))) {
                        score += 2.00;
                        matchedAnyEntity = true;
                        break;
                    } else if (!columnEntity.isEmpty() && (columnEntity.contains(alias) || columnEntity.replace(".", "").contains(cleanAlias))) {
                        score += 2.00;
                        matchedAnyEntity = true;
                        break;
                    } else if (!entitiesInGroup.isEmpty() && (entitiesInGroup.contains(alias) || entitiesInGroup.replace(".", "").contains(cleanAlias))) {
                        score += 2.00;
                        matchedAnyEntity = true;
                        break;
                    } else if (!parentCategory.isEmpty() && (parentCategory.contains(alias) || parentCategory.replace(".", "").contains(cleanAlias))) {
                        score += 1.20;
                        matchedAnyEntity = true;
                        break;
                    } else if (!tableName.isEmpty() && (tableName.contains(alias) || tableName.replace(".", "").contains(cleanAlias))) {
                        score += 1.00;
                        matchedAnyEntity = true;
                        break;
                    } else if (docText.contains(alias) || docText.replace(".", "").contains(cleanAlias)) {
                        score += 1.20;
                        matchedAnyEntity = true;
                        break;
                    }
                }
            }
        }

        // Section matching
        String section = ((String) metadata.getOrDefault("section", "")).toLowerCase();
        if (!section.isEmpty() && signals.getEntities() != null) {
            for (String entity : signals.getEntities()) {
                if (section.contains(entity)) {
                    score += 0.30;
                }
            }
            if (signals.getCanonicalMetric() != null) {
                for (String m : signals.getCanonicalMetric().getSearchAliases()) {
                    if (section.contains(m)) {
                        score += 0.15;
                    }
                }
            }
        }

        // Narrative / Stance matching (when query intent is narrative view or stance)
        if (signals.getQueryIntent() == QueryIntent.NARRATIVE_VIEW || signals.getQueryIntent() == QueryIntent.EXPLANATION_WHY) {
            if (docText.contains("overweight") || docText.contains("underweight") || docText.contains("neutral")
                    || docText.contains("our view") || docText.contains("allocation stance") || docText.contains("asset allocation")
                    || docText.contains("tactical view") || docText.contains("attractive") || docText.contains("unattractive")) {
                score += 0.40;
            }
        }

        // Metric matching - check metadata/title first, then text (conditioned on entity relevance when entity is specified)
        String metric = ((String) metadata.getOrDefault("metric", "")).toLowerCase();
        String figureTitle = ((String) metadata.getOrDefault("figure_title", "")).toLowerCase();
        String tableTitle = ((String) metadata.getOrDefault("table_title", "")).toLowerCase();
        String title = ((String) metadata.getOrDefault("title", "")).toLowerCase();

        if (signals.getCanonicalMetric() != null && (!hasQueryEntities || matchedAnyEntity)) {
            for (String metricSignal : signals.getCanonicalMetric().getSearchAliases()) {
                if ((!metric.isEmpty() && metric.contains(metricSignal))
                        || (!figureTitle.isEmpty() && figureTitle.contains(metricSignal))
                        || (!tableTitle.isEmpty() && tableTitle.contains(metricSignal))
                        || (!tableName.isEmpty() && tableName.contains(metricSignal))
                        || (!title.isEmpty() && title.contains(metricSignal))) {
                    score += 0.80; // High weight for metadata/title match
                    break;
                } else if (docText.contains(metricSignal)) {
                    score += 0.30;
                    break;
                }
            }
        }
        // Chunk-Type Awareness based on Generalized Query Intent
        String chunkType = ((String) metadata.getOrDefault("chunk_type", "")).toLowerCase();
        QueryIntent intent = signals.getQueryIntent();
        if (intent == null) intent = QueryIntent.GENERAL_DOCUMENT_QUERY;

        if (intent == QueryIntent.NARRATIVE_VIEW || intent == QueryIntent.EXPLANATION_WHY) {
            if (chunkType.equals("paragraph") || chunkType.equals("narrative") || chunkType.equals("section_summary")) {
                score += 0.50; // Positive preference for explanatory and qualitative text
            } else if (chunkType.equals("figure_summary") || chunkType.equals("table_summary")) {
                score += 0.20;
            }
        } else if (intent == QueryIntent.NUMERICAL_LOOKUP) {
            if (chunkType.equals("table") || chunkType.equals("table_row") || chunkType.equals("figure_panel") || chunkType.equals("figure_datapoints")) {
                score += 0.40; // Positive preference for granular data containers
            } else if (chunkType.equals("table_summary") || chunkType.equals("figure_summary")) {
                score += 0.25;
            }
        } else if (intent == QueryIntent.COMPARATIVE_NUMERICAL) {
            if (chunkType.equals("table") || chunkType.equals("figure_panel") || chunkType.equals("table_summary") || chunkType.equals("figure_summary")) {
                score += 0.35; // Positive preference for comparative tables/panels
            }
        } else if (intent == QueryIntent.DECLARATIVE_QUOTATION) {
            if (chunkType.equals("paragraph") || chunkType.equals("section_summary") || chunkType.equals("figure_summary")) {
                score += 0.35;
            }
        }

        // Period matching - check metadata first, then text
        String period = ((String) metadata.getOrDefault("period", "")).toLowerCase();
        if (signals.getPeriod() != null && !signals.getPeriod().isBlank()) {
            String pSig = signals.getPeriod().toLowerCase();
            if (!period.isEmpty() && period.contains(pSig)) {
                score += 0.30;
            } else if (docText.contains(pSig)) {
                score += 0.15;
            }
        }
        
        // Intra-Chunk Atomic Co-occurrence Boost
        if (signals.getEntities() != null) {
            for (String entity : signals.getEntities()) {
                String cleanEntity = entity.replace(".", "").trim();
                boolean hasEntity = docText.contains("entity: " + entity) || docText.contains(entity)
                        || docText.contains(cleanEntity);
                if (hasEntity) {
                    if (signals.getPeriod() != null && !signals.getPeriod().isBlank()) {
                        String pSig = signals.getPeriod().toLowerCase();
                        if (docText.contains(pSig + ":") || docText.contains(pSig + " :")
                                || docText.matches("(?s).*" + Pattern.quote(entity) + ".*" + Pattern.quote(pSig) + ".*\\d+\\.\\d+.*")) {
                            score += 0.60; // Strong atomic evidence boost
                        }
                    }
                    if (signals.getCanonicalMetric() != null) {
                        for (String mSig : signals.getCanonicalMetric().getSearchAliases()) {
                            if (docText.contains(mSig + ":") || docText.contains(mSig + " :")
                                    || docText.matches("(?s).*" + Pattern.quote(entity) + ".*" + Pattern.quote(mSig) + ".*\\d+\\.\\d+.*")) {
                                score += 0.40; // Strong atomic evidence boost
                                break;
                            }
                        }
                    }
                }
            }
        }
        
        // Table type matching
        if (signals.getTableTypes() != null) {
            for (String tableType : signals.getTableTypes()) {
                if ("sector".equals(tableType)) {
                    String[] sectorNames = {"utilities", "info tech", "financials", "energy", "healthcare", 
                        "real estate", "consumer discretionary", "consumer staples", "industrials", 
                        "materials", "communication services", "technology"};
                    
                    boolean hasSectorData = false;
                    for (String sector : sectorNames) {
                        if (docText.contains(sector)) {
                            String sectorPattern = sector + ".*\\d+\\.\\d+";
                            if (docText.matches("(?s).*" + sectorPattern + ".*")) {
                                hasSectorData = true;
                                break;
                            }
                        }
                    }
                    if (hasSectorData) score += 0.5;
                }
                if ("central bank policy rates".equals(tableType)) {
                    String tableNameMeta = ((String) metadata.getOrDefault("table_name", "")).toLowerCase();
                    String tableTitleMeta = ((String) metadata.getOrDefault("table_title", "")).toLowerCase();
                    String sectionMeta = ((String) metadata.getOrDefault("section", "")).toLowerCase();
                    if (tableNameMeta.contains("central bank policy rates") || tableNameMeta.contains("central bank rates")
                            || tableTitleMeta.contains("central bank policy rates") || tableTitleMeta.contains("central bank rates")
                            || sectionMeta.contains("central bank policy rates") || sectionMeta.contains("central bank rates")) {
                        score += 0.80;
                    } else if (docText.contains("central bank policy rates") || docText.contains("central bank rates")) {
                        score += 0.40;
                    }
                }
            }
        }

        // Structured Attribute (Tenor/Maturity) Alignment
        if (signals.getTenors() != null && !signals.getTenors().isEmpty()) {
            String docEntities = rowEntity + " " + columnEntity + " " + entitiesInGroup + " " + parentCategory;
            Set<String> docEntityTenors = QueryAnalyzer.extractTenors(docEntities);
            if (!docEntityTenors.isEmpty()) {
                boolean matchesTenor = !Collections.disjoint(signals.getTenors(), docEntityTenors);
                if (matchesTenor) {
                    score += 0.80;
                } else {
                    score -= 0.40;
                }
            }
        }
        
        return score;
    }

    /**
     * Wrapper for document with its reranked score.
     */
    public record RerankedDocument(Document document, double rerankedScore) {}
}
