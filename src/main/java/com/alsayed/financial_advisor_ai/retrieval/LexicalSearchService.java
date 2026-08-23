package com.alsayed.financial_advisor_ai.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lexical search service using PostgreSQL Full-Text Search (tsvector/tsquery + ts_rank_cd)
 * complemented by BM25-inspired keyword matching.
 * Provides independent corpus-level retrieval that does not depend on dense vector search.
 */
@Slf4j
@Service
public class LexicalSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LexicalSearchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Perform independent corpus-wide lexical search directly on PostgreSQL using Full-Text Search.
     * Combines ts_rank_cd ranking with BM25-inspired term coverage scoring.
     */
    public List<ScoredDocument> searchCorpus(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> queryTerms = extractQueryTerms(query);
        Map<String, ScoredDocument> candidateMap = new LinkedHashMap<>();

        // 1. PostgreSQL Full-Text Search using plainto_tsquery and ts_rank_cd
        try {
            String ftsSql = """
                SELECT id, content, metadata,
                       ts_rank_cd(to_tsvector('english', content), plainto_tsquery('english', ?)) AS fts_rank
                FROM vector_store
                WHERE to_tsvector('english', content) @@ plainto_tsquery('english', ?)
                ORDER BY fts_rank DESC
                LIMIT ?
                """;

            jdbcTemplate.query(ftsSql, rs -> {
                String id = rs.getString("id");
                String content = rs.getString("content");
                String metadataJson = rs.getString("metadata");
                double ftsRank = rs.getDouble("fts_rank");

                Map<String, Object> metadata = parseMetadata(metadataJson);
                Document doc = new Document(id, content, metadata);

                double bm25Score = calculateLexicalScore(content, queryTerms);
                double combinedScore = (ftsRank * 0.4) + (bm25Score * 0.6);

                candidateMap.put(id, new ScoredDocument(doc, combinedScore));
            }, query, query, topK * 2);
        } catch (Exception e) {
            log.warn("PostgreSQL Full-Text Search query failed, falling back to term matching: {}", e.getMessage());
        }

        // 2. Term-based matching and BM25 scoring for key query terms
        if (!queryTerms.isEmpty()) {
            try {
                for (String term : queryTerms) {
                    if (term == null || term.length() < 3) continue;
                    String ilikeSql = """
                        SELECT id, content, metadata
                        FROM vector_store
                        WHERE content ILIKE ?
                        LIMIT ?
                        """;

                    jdbcTemplate.query(ilikeSql, rs -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");

                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        Document doc = new Document(id, content, metadata);

                        double bm25Score = calculateLexicalScore(content, queryTerms);
                        if (bm25Score > 0) {
                            candidateMap.compute(id, (k, old) -> old == null || bm25Score > old.score() ? new ScoredDocument(doc, bm25Score) : old);
                        }
                    }, "%" + term + "%", topK);
                }
            } catch (Exception e) {
                log.warn("Lexical term search error: {}", e.getMessage());
            }
        }

        log.debug("searchCorpus for '{}' found {} candidates", query, candidateMap.size());

        return candidateMap.values().stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * Perform in-memory lexical search across a pre-filtered list of candidates.
     */
    public List<ScoredDocument> search(List<Document> candidates, String query, int topK) {
        List<ScoredDocument> scoredDocs = new ArrayList<>();
        Set<String> queryTerms = extractQueryTerms(query);

        for (Document doc : candidates) {
            double score = calculateLexicalScore(doc.getText(), queryTerms);
            if (score > 0) {
                scoredDocs.add(new ScoredDocument(doc, score));
            }
        }

        return scoredDocs.stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * Extract significant terms from query (filter stop words).
     */
    private Set<String> extractQueryTerms(String query) {
        String[] stopWords = {"the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
                "by", "as", "is", "was", "are", "were", "be", "been", "being", "have", "has", "had", "do", "does",
                "did", "will", "would", "could", "should", "may", "might", "must", "what", "which", "who", "where",
                "when", "why", "how"};

        Set<String> stopWordSet = new HashSet<>(Arrays.asList(stopWords));
        String[] terms = query.toLowerCase().split("\\s+");

        Set<String> significantTerms = new HashSet<>();
        for (String term : terms) {
            String cleaned = term.replaceAll("[^a-z0-9.%-]", "");
            if (cleaned.length() > 1 && !stopWordSet.contains(cleaned)) {
                significantTerms.add(cleaned);
            }
        }

        return significantTerms;
    }

    /**
     * Calculate lexical score using BM25-inspired term frequency and coverage.
     */
    public double calculateLexicalScore(String documentText, Set<String> queryTerms) {
        if (documentText == null || queryTerms.isEmpty()) {
            return 0.0;
        }

        String normalizedDoc = documentText.toLowerCase();
        int matches = 0;
        int totalMatches = 0;

        for (String term : queryTerms) {
            int termCount = countOccurrences(normalizedDoc, term);
            if (termCount > 0) {
                matches++;
                totalMatches += termCount;
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        double coverage = (double) matches / queryTerms.size();
        double frequencyBonus = Math.log1p(totalMatches);

        return (coverage * 0.7) + (frequencyBonus * 0.3);
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int index = 0;

        while ((index = text.indexOf(term, index)) != -1) {
            count++;
            index += term.length();
        }

        return count;
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
}
