package com.alsayed.financial_advisor_ai.retrieval;

import com.alsayed.financial_advisor_ai.retrieval.filter.DiversityFilter;
import com.alsayed.financial_advisor_ai.retrieval.fusion.ReciprocalRankFuser;
import com.alsayed.financial_advisor_ai.retrieval.fusion.ScoreCombiner;
import com.alsayed.financial_advisor_ai.retrieval.model.CandidateProvenance;
import com.alsayed.financial_advisor_ai.retrieval.model.MergedDocument;
import com.alsayed.financial_advisor_ai.retrieval.model.ScoredDocument;
import com.alsayed.financial_advisor_ai.retrieval.rerank.RerankedDocument;
import com.alsayed.financial_advisor_ai.retrieval.rerank.Reranker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval service combining vector search, lexical search, and metadata-based reranking.
 * Improves retrieval precision for fine-grained financial queries.
 * 
 * Supports two retrieval paths:
 * 1. Normal retrieval: multi-channel vector + lexical + structured search, RRF fusion, reranking, and diversity filtering.
 * 2. Comparative retrieval: structured metadata filtering + primary container grouping for aggregation queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final VectorStore vectorStore;
    private final LexicalSearchService lexicalSearchService;
    private final StructuredCandidateRetriever structuredCandidateRetriever;
    private final QueryAnalyzer queryAnalyzer;
    private final NumericComparator numericComparator;
    private final Reranker reranker;
    private final ReciprocalRankFuser rrfFuser;
    private final ScoreCombiner scoreCombiner;
    private final DiversityFilter diversityFilter;

    @Value("${app.rag.search.hybrid.enabled:false}")
    private boolean hybridEnabled;

    @Value("${app.rag.search.hybrid.lexical-weight:0.3}")
    private double lexicalWeight;

    @Value("${app.rag.search.hybrid.vector-weight:0.7}")
    private double vectorWeight;

    /**
     * Perform hybrid retrieval combining vector and lexical search with reranking.
     * Automatically detects comparative queries and uses the appropriate retrieval path.
     */
    public List<Document> search(String query, int topK) {
        // Analyze query to determine retrieval path
        QuerySignals signals = queryAnalyzer.analyze(query);
        String retrievalQuery = buildRetrievalQuery(query, signals);

        log.info("""
            [RETRIEVAL DIAGNOSTIC TRACE]
            QUESTION: {}
            PARSED INTENT: entities={}, metric={}, period={}, operation={}
            NORMALIZED INTENT: canonicalMetric={}, tableTypes={}
            RETRIEVAL QUERY: {}
            """,
            query, signals.getEntities(), signals.getMetric(), signals.getPeriod(), signals.getOperation(),
            signals.getCanonicalMetric(), signals.getTableTypes(), retrievalQuery);

        List<Document> results;
        if (signals.isComparative()) {
            log.info("Detected comparative query, using structured retrieval path");
            results = comparativeRetrieval(query, signals, topK);
        } else {
            results = normalRetrieval(query, topK);
        }

        if (log.isInfoEnabled() && results != null) {
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                Document d = results.get(i);
                log.info("  TOP RESULT #{}: [Page {}] {}", (i + 1), d.getMetadata().get("page_number"),
                        d.getMetadata().get("title") != null ? d.getMetadata().get("title") : d.getMetadata().get("table_name"));
            }
        }

        return results;
    }

    private String buildRetrievalQuery(String rawQuery, QuerySignals signals) {
        if (signals == null || (signals.getCanonicalMetric() == null && (signals.getCanonicalMetrics() == null || signals.getCanonicalMetrics().isEmpty()))) {
            return rawQuery;
        }
        StringBuilder sb = new StringBuilder();
        if (signals.getEntities() != null && !signals.getEntities().isEmpty()) {
            sb.append(String.join(" ", signals.getEntities())).append(" ");
        }
        if (signals.getCanonicalMetrics() != null && !signals.getCanonicalMetrics().isEmpty()) {
            for (CanonicalMetric cm : signals.getCanonicalMetrics()) {
                for (String alias : cm.getSearchAliases()) {
                    sb.append(alias).append(" ");
                }
            }
        } else if (signals.getCanonicalMetric() != null) {
            for (String alias : signals.getCanonicalMetric().getSearchAliases()) {
                sb.append(alias).append(" ");
            }
        }
        if (signals.getPeriod() != null) {
            sb.append(signals.getPeriod()).append(" ");
        }
        if (signals.getDerivedEvidenceMetrics() != null && !signals.getDerivedEvidenceMetrics().isEmpty()) {
            sb.append(String.join(" ", signals.getDerivedEvidenceMetrics())).append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * Normal retrieval path for factual queries using independent multi-channel retrieval and RRF fusion.
     */
    private List<Document> normalRetrieval(String query, int topK) {
        if (!hybridEnabled) {
            log.debug("Hybrid retrieval disabled, using vector search only");
            return vectorSearch(query, topK);
        }

        log.info("Performing multi-channel hybrid retrieval for query: '{}'", query);
        QuerySignals signals = queryAnalyzer.analyze(query);
        String retrievalQuery = buildRetrievalQuery(query, signals);

        // Channel 1: Dense Semantic Retrieval
        List<Document> denseResults = new ArrayList<>(vectorSearch(query, 50));
        if (!retrievalQuery.equals(query)) {
            List<Document> expandedDense = vectorSearch(retrievalQuery, 30);
            Set<String> seenIds = denseResults.stream().map(Document::getId).collect(Collectors.toSet());
            for (Document doc : expandedDense) {
                if (seenIds.add(doc.getId())) {
                    denseResults.add(doc);
                }
            }
        }
        if (signals.getEntities() != null && !signals.getEntities().isEmpty()) {
            for (String entity : signals.getEntities()) {
                StringBuilder entityQuery = new StringBuilder(entity);
                if (signals.getMetric() != null) entityQuery.append(" ").append(signals.getMetric());
                if (signals.getPeriod() != null) entityQuery.append(" ").append(signals.getPeriod());

                List<Document> entityResults = vectorSearch(entityQuery.toString().trim(), 20);
                Set<String> seenIds = denseResults.stream().map(Document::getId).collect(Collectors.toSet());
                for (Document doc : entityResults) {
                    if (seenIds.add(doc.getId())) {
                        denseResults.add(doc);
                    }
                }
            }
        }
        log.debug("Dense channel returned {} candidates", denseResults.size());

        // Channel 2: Independent Corpus-Wide Lexical Search (PostgreSQL FTS + BM25)
        List<LexicalSearchService.ScoredDocument> lexicalResults =
                new ArrayList<>(lexicalSearchService.searchCorpus(query, 50));
        if (!retrievalQuery.equals(query)) {
            List<LexicalSearchService.ScoredDocument> expandedLexical =
                    lexicalSearchService.searchCorpus(retrievalQuery, 30);
            Set<String> seenIds = lexicalResults.stream().map(d -> d.document().getId()).collect(Collectors.toSet());
            for (LexicalSearchService.ScoredDocument doc : expandedLexical) {
                if (seenIds.add(doc.document().getId())) {
                    lexicalResults.add(doc);
                }
            }
        }
        log.debug("Lexical channel returned {} candidates", lexicalResults.size());

        // Channel 3: Structured Metadata & Entity Retrieval
        List<StructuredCandidateRetriever.ScoredDocument> structuredResults =
                structuredCandidateRetriever.retrieveByStructuredSignals(signals, 30);
        log.debug("Structured channel returned {} candidates", structuredResults.size());

        // Step 4: Multi-Channel Reciprocal Rank Fusion (RRF) & Candidate Deduplication
        Map<String, MergedDocument> mergedCandidates = rrfFuser.fuse(denseResults, lexicalResults, structuredResults);
        log.debug("Fused into {} unique candidates across all channels", mergedCandidates.size());

        // Step 5: Neural or Heuristic Reranking via Reranker Strategy
        List<Document> candidateDocs = mergedCandidates.values().stream()
                .map(MergedDocument::document)
                .collect(Collectors.toList());
        List<RerankedDocument> reranked = reranker.rerank(query, candidateDocs);

        // Step 6: Combine Final Scores (RRF + Rerank) & Provenance Tracking
        List<ScoredDocument> finalScores = scoreCombiner.combine(mergedCandidates, reranked);

        // Step 7: Sort and apply container diversity filter
        List<ScoredDocument> sorted = finalScores.stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::finalScore).reversed())
                .collect(Collectors.toList());

        return diversityFilter.filter(sorted, topK);
    }

    /**
     * Simplify query for vector search by extracting key terms.
     */
    private String simplifyQueryForVectorSearch(String query) {
        QuerySignals signals = queryAnalyzer.analyze(query);
        StringBuilder simplified = new StringBuilder();

        if (signals.getEntities() != null && !signals.getEntities().isEmpty()) {
            simplified.append(String.join(" ", signals.getEntities())).append(" ");
        }
        if (signals.getMetric() != null) {
            simplified.append(signals.getMetric()).append(" ");
        }
        if (signals.getPeriod() != null) {
            simplified.append(signals.getPeriod()).append(" ");
        }
        if (simplified.length() == 0) {
            return query;
        }

        return simplified.toString().trim();
    }

    /**
     * Comparative retrieval path for aggregation queries.
     */
    private List<Document> comparativeRetrieval(String query, QuerySignals signals, int topK) {
        log.info("Performing comparative retrieval with signals: {}", signals);

        StringBuilder searchQuery = new StringBuilder();
        if (signals.getEntities() != null && !signals.getEntities().isEmpty()) {
            searchQuery.append(String.join(" ", signals.getEntities())).append(" ");
        }
        if (signals.getPeriod() != null) {
            searchQuery.append(signals.getPeriod()).append(" ");
        }
        if (signals.getMetric() != null) {
            String metric = signals.getMetric().toLowerCase();
            if (metric.contains("return")) {
                searchQuery.append("return ");
            } else if (metric.contains("yield")) {
                searchQuery.append("yield ");
            } else {
                searchQuery.append(signals.getMetric()).append(" ");
            }
        }
        if (signals.getGroupBy() != null) {
            searchQuery.append(signals.getGroupBy());
        }

        log.debug("Comparative retrieval with original query: '{}'", query);

        List<Document> candidates = normalRetrieval(query, topK * 20);
        log.debug("Normal retrieval returned {} candidates", candidates.size());

        if (candidates.isEmpty()) {
            log.warn("No candidates found via normal retrieval");
            return candidates;
        }

        if (signals.getPeriod() != null) {
            String targetPeriod = signals.getPeriod().toLowerCase().replace("-", " ").replaceAll("\\s+", " ").trim();
            String altPeriod = targetPeriod.contains("month") ? targetPeriod.replace("month", "mo")
                    : targetPeriod.contains("year") ? targetPeriod.replace("year", "yr")
                    : targetPeriod;

            List<Document> filtered = candidates.stream()
                .filter(doc -> {
                    String docPeriod = (String) doc.getMetadata().get("period");
                    if (docPeriod != null) {
                        String dp = docPeriod.toLowerCase().replace("-", " ").replaceAll("\\s+", " ").trim();
                        if (dp.contains(targetPeriod) || dp.contains(altPeriod)) {
                            return true;
                        }
                    }
                    String text = doc.getText() != null ? doc.getText().toLowerCase().replace("-", " ").replaceAll("\\s+", " ") : "";
                    return text.contains(targetPeriod) || text.contains(altPeriod);
                })
                .collect(Collectors.toList());
            if (!filtered.isEmpty()) {
                candidates = filtered;
            }
            log.debug("After period filtering: {} candidates", candidates.size());
        }

        List<Document> finalCandidates = new ArrayList<>(candidates);

        if (signals.isComparative() && !finalCandidates.isEmpty()) {
            Map<String, Double> containerScoreWeights = new HashMap<>();
            Map<String, Integer> containerCounts = new HashMap<>();

            int evalLimit = Math.min(finalCandidates.size(), 20);
            for (int i = 0; i < evalLimit; i++) {
                Document d = finalCandidates.get(i);
                String containerId = (String) d.getMetadata().get("table_id");
                if (containerId == null || containerId.isBlank()) {
                    containerId = (String) d.getMetadata().get("figure_id");
                }
                if (containerId != null && !containerId.isBlank()) {
                    double rankWeight = 1.0 / (1.0 + i);
                    String title = (String) d.getMetadata().getOrDefault("title", "");
                    String text = d.getText() != null ? d.getText().toLowerCase() : "";

                    // If query has group intent (e.g., sector, etf, currency), boost containers matching the group
                    if (signals.getGroupBy() != null && (title.toLowerCase().contains(signals.getGroupBy()) || text.contains(signals.getGroupBy()))) {
                        rankWeight *= 2.0;
                    }
                    if (signals.getTableTypes() != null) {
                        for (String tt : signals.getTableTypes()) {
                            if (title.toLowerCase().contains(tt) || text.contains(tt)) {
                                rankWeight *= 2.0;
                            }
                        }
                    }

                    containerScoreWeights.merge(containerId, rankWeight, Double::sum);
                    containerCounts.merge(containerId, 1, Integer::sum);
                }
            }

            String primaryContainerId = containerScoreWeights.entrySet().stream()
                .filter(e -> containerCounts.getOrDefault(e.getKey(), 0) >= 2)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

            if (primaryContainerId != null) {
                List<Document> primaryContainerChunks = finalCandidates.stream()
                    .filter(d -> primaryContainerId.equals(d.getMetadata().get("table_id")) ||
                                 primaryContainerId.equals(d.getMetadata().get("figure_id")))
                    .collect(Collectors.toList());

                List<Document> otherChunks = finalCandidates.stream()
                    .filter(d -> !primaryContainerId.equals(d.getMetadata().get("table_id")) &&
                                 !primaryContainerId.equals(d.getMetadata().get("figure_id")))
                    .collect(Collectors.toList());

                List<Document> reordered = new ArrayList<>(primaryContainerChunks);
                reordered.addAll(otherChunks);
                finalCandidates = reordered;
            }
        }

        return finalCandidates.stream()
            .limit(topK)
            .collect(Collectors.toList());
    }

    /**
     * Perform vector search only (fallback when hybrid is disabled).
     */
    private List<Document> vectorSearch(String query, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.15)
                .build();

        return vectorStore.similaritySearch(searchRequest);
    }
}
