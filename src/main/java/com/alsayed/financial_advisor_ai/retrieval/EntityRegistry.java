package com.alsayed.financial_advisor_ai.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic entity registry that extracts and stores entities from document metadata.
 * Replaces hardcoded entity lists with dynamic extraction from actual document content.
 * This ensures any entity present in the documents will be recognized during retrieval.
 */
@Slf4j
@Component
public class EntityRegistry {

    private final JdbcTemplate jdbcTemplate;

    public EntityRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Thread-safe storage for entities
    private final Set<String> entities = ConcurrentHashMap.newKeySet();

    // Entity frequency tracking for ranking
    private final Map<String, Integer> entityFrequency = new ConcurrentHashMap<>();

    /**
     * Lazy populate from PostgreSQL database if registry is empty.
     */
    private void ensurePopulated() {
        if (!entities.isEmpty()) return;

        try {
            String sql = """
                SELECT DISTINCT metadata->>'row_entity' AS e FROM vector_store WHERE metadata->>'row_entity' IS NOT NULL
                UNION
                SELECT DISTINCT metadata->>'entity' AS e FROM vector_store WHERE metadata->>'entity' IS NOT NULL
                UNION
                SELECT DISTINCT metadata->>'entities_in_group' AS e FROM vector_store WHERE metadata->>'entities_in_group' IS NOT NULL
                """;

            jdbcTemplate.query(sql, rs -> {
                String eStr = rs.getString("e");
                if (eStr != null && !eStr.isBlank()) {
                    for (String part : eStr.split(",")) {
                        String clean = part.trim();
                        if (clean.length() > 1) {
                            addEntity(clean);
                        }
                    }
                }
            });

            log.info("Entity registry lazy-populated from database with {} unique entities", entities.size());
        } catch (Exception e) {
            log.warn("Failed to populate EntityRegistry from database: {}", e.getMessage());
        }
    }
    
    /**
     * Extract entities from document metadata and populate the registry.
     * Should be called during ingestion after chunking.
     */
    public void populateFromDocuments(List<Document> documents) {
        log.info("Populating entity registry from {} documents", documents.size());
        
        int entityCount = 0;
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            
            // Extract row_entity
            String rowEntity = (String) metadata.get("row_entity");
            if (rowEntity != null && !rowEntity.isEmpty()) {
                addEntity(rowEntity);
                entityCount++;
            }
            
            // Extract column_entity
            String columnEntity = (String) metadata.get("column_entity");
            if (columnEntity != null && !columnEntity.isEmpty()) {
                addEntity(columnEntity);
                entityCount++;
            }

            // Extract entities_in_group (from grouped table chunks)
            String entitiesInGroup = (String) metadata.get("entities_in_group");
            if (entitiesInGroup != null && !entitiesInGroup.isEmpty()) {
                for (String e : entitiesInGroup.split(",")) {
                    addEntity(e.trim());
                    entityCount++;
                }
            }
        }
        
        log.info("Entity registry populated with {} unique entities ({} total occurrences)", 
            entities.size(), entityCount);
        
        // Log sample entities for debugging
        if (!entities.isEmpty()) {
            log.debug("Sample entities: {}", 
                entities.stream().limit(10).toList());
        }
    }
    
    private static final Set<String> STOPWORDS = Set.of(
            "index", "yield", "rate", "spread", "total", "return", "current", "average", "bps",
            "table", "figure", "section", "page", "basis", "points", "past", "security"
    );

    private static final Map<String, List<String>> CANONICAL_FINANCIAL_ALIASES = Map.ofEntries(
            Map.entry("u.s. federal reserve", List.of("federal reserve", "fed")),
            Map.entry("european central bank", List.of("ecb")),
            Map.entry("bank of england", List.of("boe")),
            Map.entry("bank of japan", List.of("boj")),
            Map.entry("bank of canada", List.of("boc")),
            Map.entry("reserve bank of australia", List.of("rba")),
            Map.entry("swiss national bank", List.of("snb")),
            Map.entry("exchange-traded fund", List.of("etf", "etfs", "exchange traded fund")),
            Map.entry("exchange-traded funds", List.of("etf", "etfs", "exchange traded funds")),
            Map.entry("master limited partnerships", List.of("mlp", "mlps", "master limited partnership")),
            Map.entry("real estate investment trust", List.of("reit", "reits", "real estate investment trusts")),
            Map.entry("asset-backed securities", List.of("abs", "asset backed securities"))
    );

    /**
     * Add a single entity to the registry with generalized tenor and head decomposition.
     */
    private void addEntity(String entity) {
        String normalized = normalizeEntity(entity);
        if (normalized.isEmpty() || STOPWORDS.contains(normalized)) {
            return;
        }

        registerSingleEntity(normalized);

        // Generalized Tenor Normalization (e.g. "10-yr" <-> "10-year")
        String withYear = normalized.replaceAll("\\b(\\d+)-yr\\b", "$1-year").replaceAll("\\b(\\d+) yr\\b", "$1 year");
        String withYr = normalized.replaceAll("\\b(\\d+)-year\\b", "$1-yr").replaceAll("\\b(\\d+) year\\b", "$1-yr");
        if (!withYear.equals(normalized)) registerSingleEntity(withYear);
        if (!withYr.equals(normalized)) registerSingleEntity(withYr);

        // Generalized Head Entity Extraction: strip tenor prefix (e.g. "10-yr german bund" -> "german bund")
        String headWithoutTenor = normalized.replaceAll("^\\d+-(?:yr|year|mo|month)\\s+", "").trim();
        if (headWithoutTenor.length() >= 3 && !headWithoutTenor.equals(normalized) && !STOPWORDS.contains(headWithoutTenor)) {
            registerSingleEntity(headWithoutTenor);
        }

        // Generalized Provider / Benchmark Prefix Extraction (e.g. "bloomberg us municipal" -> "us municipal")
        String headWithoutProvider = normalized.replaceAll("^(?:bloomberg|ice bofa|msci|s&p|s&p 500|ftse|russell)\\s+", "").trim();
        if (headWithoutProvider.length() >= 3 && !headWithoutProvider.equals(normalized) && !STOPWORDS.contains(headWithoutProvider)) {
            registerSingleEntity(headWithoutProvider);
        }

        // Generalized Financial and Institutional Aliases
        for (var entry : CANONICAL_FINANCIAL_ALIASES.entrySet()) {
            String canonical = entry.getKey();
            if (normalized.equals(canonical) || normalized.contains(canonical)) {
                for (String alias : entry.getValue()) {
                    registerSingleEntity(alias);
                }
            }
        }
    }

    private void registerSingleEntity(String entity) {
        if (entity != null && !entity.isBlank() && entity.length() >= 2 && !STOPWORDS.contains(entity)) {
            entities.add(entity);
            entityFrequency.merge(entity, 1, Integer::sum);
        }
    }

    /**
     * Normalize entity string for consistent matching.
     * Handles case normalization and common variations.
     */
    private String normalizeEntity(String entity) {
        if (entity == null || entity.trim().isEmpty()) {
            return "";
        }

        // Convert to lowercase for case-insensitive matching
        String normalized = entity.toLowerCase().trim();

        // Remove extra whitespace
        normalized = normalized.replaceAll("\\s+", " ");

        return normalized;
    }

    /**
     * Check if a query contains any registered entity.
     */
    public boolean containsEntity(String query) {
        ensurePopulated();
        String lowerQuery = query.toLowerCase();

        for (String entity : entities) {
            if (lowerQuery.contains(entity)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extract entities from a query that match registered entities.
     */
    public Set<String> extractEntitiesFromQuery(String query) {
        ensurePopulated();
        Set<String> matchedEntities = new HashSet<>();
        String lowerQuery = query.toLowerCase();
        String unpunctuatedQuery = lowerQuery.replace(".", "").replaceAll("\\s+", " ").trim();
        String tenorNormalizedQuery = lowerQuery.replaceAll("\\b(\\d+)-year\\b", "$1-yr")
                .replaceAll("\\b(\\d+) year\\b", "$1-yr")
                .replaceAll("\\b(\\d+)-month\\b", "$1-mo")
                .replaceAll("\\b(\\d+) month\\b", "$1-mo");

        for (String entity : entities) {
            String unpunctuatedEntity = entity.replace(".", "").replaceAll("\\s+", " ").trim();
            if (lowerQuery.contains(entity)
                    || (!unpunctuatedEntity.isEmpty() && unpunctuatedQuery.contains(unpunctuatedEntity))
                    || tenorNormalizedQuery.contains(entity)) {
                matchedEntities.add(entity);
            }
        }

        // Generalized Financial and Institutional Alias Matching with Word Boundaries
        for (var entry : CANONICAL_FINANCIAL_ALIASES.entrySet()) {
            String canonical = entry.getKey();
            if (lowerQuery.contains(canonical)) {
                matchedEntities.add(canonical);
            }
            for (String alias : entry.getValue()) {
                if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(alias) + "\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(lowerQuery).find()) {
                    matchedEntities.add(alias);
                    matchedEntities.add(canonical);
                }
            }
        }

        // Subsumption pruning: If a longer extracted entity contains a shorter entity as substring, remove the shorter substring entity
        matchedEntities.removeIf(shorter -> matchedEntities.stream().anyMatch(longer -> !longer.equals(shorter) && longer.contains(shorter)));

        return matchedEntities;
    }

    /**
     * Get canonical aliases for an entity.
     */
    public Set<String> getCanonicalAliases(String entity) {
        if (entity == null || entity.isBlank()) return Set.of();
        String norm = normalizeEntity(entity);
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(norm);

        for (var entry : CANONICAL_FINANCIAL_ALIASES.entrySet()) {
            String canonical = entry.getKey();
            List<String> list = entry.getValue();
            if (norm.equals(canonical) || norm.contains(canonical) || list.contains(norm)) {
                aliases.add(canonical);
                aliases.addAll(list);
            }
        }
        return aliases;
    }
    
    /**
     * Get all registered entities.
     */
    public Set<String> getAllEntities() {
        return Collections.unmodifiableSet(entities);
    }
    
    /**
     * Get entity frequency map.
     */
    public Map<String, Integer> getEntityFrequency() {
        return Collections.unmodifiableMap(entityFrequency);
    }
    
    /**
     * Clear the registry (useful for re-ingestion).
     */
    public void clear() {
        entities.clear();
        entityFrequency.clear();
        log.info("Entity registry cleared");
    }
    
    /**
     * Get registry statistics.
     */
    public RegistryStats getStats() {
        return new RegistryStats(entities.size(), entityFrequency.size());
    }
    
    /**
     * Statistics about the entity registry.
     */
    public record RegistryStats(int uniqueEntities, int totalOccurrences) {
        public int getUniqueEntities() { return uniqueEntities; }
        public int getTotalOccurrences() { return totalOccurrences; }
    }
}
