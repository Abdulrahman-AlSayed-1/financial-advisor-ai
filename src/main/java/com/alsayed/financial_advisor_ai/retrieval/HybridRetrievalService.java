package com.alsayed.financial_advisor_ai.retrieval;

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
 * Now supports two retrieval paths:
 * 1. Normal retrieval: vector + lexical + reranking for factual queries
 * 2. Comparative retrieval: structured metadata filtering + numeric comparison for aggregation queries
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final VectorStore vectorStore;
    private final LexicalSearchService lexicalSearchService;
    private final MetadataReranker metadataReranker;
    private final QueryAnalyzer queryAnalyzer;
    private final StructuredCandidateRetriever structuredCandidateRetriever;
    private final NumericComparator numericComparator;

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

        // Step 4: Reciprocal Rank Fusion (RRF) & Candidate Deduplication
        Map<String, MergedDocument> mergedCandidates = performRrfFusion(
                denseResults, lexicalResults, structuredResults);
        log.debug("Fused into {} unique candidates across all channels", mergedCandidates.size());

        // Step 5: Metadata Reranking (with intra-chunk atomic fact boost)
        List<MetadataReranker.RerankedDocument> reranked =
                metadataReranker.rerank(query,
                        mergedCandidates.values().stream()
                                .map(MergedDocument::document)
                                .collect(Collectors.toList()));

        // Step 6: Combine Final Scores (RRF + Rerank)
        List<ScoredDocument> finalScores = combineScores(mergedCandidates, reranked);

        // Step 7: Sort and apply diversity filter
        List<ScoredDocument> sorted = finalScores.stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::finalScore).reversed())
                .collect(Collectors.toList());

        return applyDiversityFilter(sorted, topK);
    }

    /**
     * Fuses candidates from Dense, Lexical, and Structured channels using Reciprocal Rank Fusion (RRF).
     */
    private Map<String, MergedDocument> performRrfFusion(
            List<Document> denseResults,
            List<LexicalSearchService.ScoredDocument> lexicalResults,
            List<StructuredCandidateRetriever.ScoredDocument> structuredResults) {

        int k = 60;
        double wDense = 1.0;
        double wLexical = 1.0;
        double wStructured = 1.0;

        Map<String, Document> docMap = new LinkedHashMap<>();
        Map<String, Integer> denseRanks = new HashMap<>();
        Map<String, Double> denseScores = new HashMap<>();
        Map<String, Integer> lexicalRanks = new HashMap<>();
        Map<String, Double> lexicalScores = new HashMap<>();
        Map<String, Integer> structuredRanks = new HashMap<>();
        Map<String, Double> structuredScores = new HashMap<>();
        Map<String, Set<String>> sourcesMap = new HashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 1. Dense Channel
        for (int i = 0; i < denseResults.size(); i++) {
            Document doc = denseResults.get(i);
            String id = doc.getId();
            docMap.put(id, doc);
            int rank = i + 1;
            denseRanks.put(id, rank);
            denseScores.put(id, doc.getScore() != null ? doc.getScore() : (1.0 - (i * 0.01)));
            sourcesMap.computeIfAbsent(id, k1 -> new LinkedHashSet<>()).add("DENSE");
            rrfScores.merge(id, wDense / (k + rank), Double::sum);
        }

        // 2. Lexical Channel (PostgreSQL FTS + BM25)
        for (int i = 0; i < lexicalResults.size(); i++) {
            LexicalSearchService.ScoredDocument sc = lexicalResults.get(i);
            Document doc = sc.document();
            String id = doc.getId();
            docMap.putIfAbsent(id, doc);
            int rank = i + 1;
            lexicalRanks.put(id, rank);
            lexicalScores.put(id, sc.score());
            sourcesMap.computeIfAbsent(id, k1 -> new LinkedHashSet<>()).add("LEXICAL");
            rrfScores.merge(id, wLexical / (k + rank), Double::sum);
        }

        // 3. Structured / Entity Channel
        for (int i = 0; i < structuredResults.size(); i++) {
            StructuredCandidateRetriever.ScoredDocument sc = structuredResults.get(i);
            Document doc = sc.document();
            String id = doc.getId();
            docMap.putIfAbsent(id, doc);
            int rank = i + 1;
            structuredRanks.put(id, rank);
            structuredScores.put(id, sc.score());
            sourcesMap.computeIfAbsent(id, k1 -> new LinkedHashSet<>()).add("STRUCTURED");
            rrfScores.merge(id, wStructured / (k + rank), Double::sum);
        }

        // Multi-channel consensus bonus: if a document appears across >= 2 channels (DENSE, LEXICAL, STRUCTURED),
        // apply a normalized proportional bonus based on its accumulated RRF score and channel coverage.
        for (String id : docMap.keySet()) {
            Set<String> sources = sourcesMap.getOrDefault(id, Set.of());
            int channelCount = sources.size();
            if (channelCount >= 2) {
                double currentRrf = rrfScores.getOrDefault(id, 0.0);
                double proportionalBonus = currentRrf * 0.15 * (channelCount - 1);
                rrfScores.merge(id, proportionalBonus, Double::sum);
            }
        }

        Map<String, MergedDocument> merged = new LinkedHashMap<>();
        for (String id : docMap.keySet()) {
            Document doc = docMap.get(id);
            CandidateProvenance provenance = new CandidateProvenance(
                    denseRanks.get(id),
                    denseScores.get(id),
                    lexicalRanks.get(id),
                    lexicalScores.get(id),
                    structuredRanks.get(id),
                    structuredScores.get(id),
                    sourcesMap.getOrDefault(id, Set.of()),
                    rrfScores.getOrDefault(id, 0.0),
                    0.0
            );
            merged.put(id, new MergedDocument(doc, provenance));
        }

        return merged;
    }

    private List<Document> applyDiversityFilter(List<ScoredDocument> sortedScores, int topK) {
        List<Document> selected = new ArrayList<>();
        List<Document> deferred = new ArrayList<>();
        Map<String, Integer> sourceUnitCounts = new HashMap<>();
        for (ScoredDocument scoredDoc : sortedScores) {
            Document doc = scoredDoc.document();
            Map<String, Object> meta = doc.getMetadata();

            // Derive generalized source unit key (distinct panel, table, or paragraph section)
            String sourceKey;
            String chunkType = String.valueOf(meta.getOrDefault("chunk_type", "doc"));
            if (meta.get("figure_id") != null && !meta.get("figure_id").toString().isBlank()) {
                Object panelName = meta.getOrDefault("panel_title", meta.get("panel"));
                if ("figure_panel".equals(chunkType) && panelName != null) {
                    sourceKey = "fig_" + meta.get("figure_id") + "_p_" + panelName;
                } else {
                    sourceKey = "fig_" + meta.get("figure_id") + "_" + chunkType;
                }
            } else if (meta.get("table_id") != null && !meta.get("table_id").toString().isBlank()) {
                sourceKey = "tbl_" + meta.get("table_id") + "_" + chunkType;
            } else {
                Object page = meta.getOrDefault("page_number", meta.get("page"));
                Object section = meta.getOrDefault("section", "doc");
                sourceKey = "p_" + page + "_s_" + section;
            }

            int count = sourceUnitCounts.getOrDefault(sourceKey, 0);
            
            // Allow more granular chunks (rows, panels) from the same table/figure to support multi-entity queries
            int allowedLimit = 1;
            if (chunkType.equals("table") || chunkType.equals("table_row") || chunkType.equals("figure_panel") || chunkType.equals("figure_datapoints")) {
                allowedLimit = 5;
            }
            
            if (count < allowedLimit) {
                selected.add(doc);
                sourceUnitCounts.put(sourceKey, count + 1);
            } else {
                deferred.add(doc);
            }

            if (selected.size() >= topK) {
                break;
            }
        }

        // Backfill from deferred candidates if topK is not reached
        if (selected.size() < topK) {
            for (Document d : deferred) {
                if (selected.size() >= topK) break;
                selected.add(d);
            }
        }

        return selected;
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

    /**
     * Combine vector, lexical, and reranking scores into final score and attach candidate provenance.
     */
    private List<ScoredDocument> combineScores(
            Map<String, MergedDocument> mergedCandidates,
            List<MetadataReranker.RerankedDocument> reranked) {

        Map<String, Double> rerankScores = reranked.stream()
            .collect(Collectors.toMap(
                d -> d.document().getId(),
                MetadataReranker.RerankedDocument::rerankedScore,
                (a, b) -> Math.max(a, b)));

        List<ScoredDocument> scored = new ArrayList<>();
        for (MergedDocument merged : mergedCandidates.values()) {
            Document doc = merged.document();
            String docId = doc.getId();
            double rrfScore = merged.provenance().rrfScore();
            double rerankScore = rerankScores.getOrDefault(docId, 0.0);

            // Record provenance in Document metadata for inspection and debugging
            Map<String, Object> provMap = new LinkedHashMap<>();
            provMap.put("denseRank", merged.provenance().denseRank());
            provMap.put("lexicalRank", merged.provenance().lexicalRank());
            provMap.put("structuredRank", merged.provenance().structuredRank());
            provMap.put("sources", new ArrayList<>(merged.provenance().sources()));
            provMap.put("rrfScore", rrfScore);
            provMap.put("rerankScore", rerankScore);
            doc.getMetadata().put("retrieval_provenance", provMap);

            double normalizedRrf = Math.min(rrfScore / 0.05, 1.0);
            double normalizedRerank = Math.min(rerankScore / 2.0, 1.0);

            double finalScore = (normalizedRrf * 0.40) + (normalizedRerank * 0.60);

            scored.add(new ScoredDocument(doc, finalScore));
        }

        return scored;
    }

    /**
     * Detailed provenance for a retrieval candidate.
     */
    public record CandidateProvenance(
            Integer denseRank,
            Double denseScore,
            Integer lexicalRank,
            Double lexicalScore,
            Integer structuredRank,
            Double structuredScore,
            Set<String> sources,
            double rrfScore,
            double metadataScore
    ) {}

    private record MergedDocument(Document document, CandidateProvenance provenance) {}

    public record ScoredDocument(Document document, double finalScore) {}
}
