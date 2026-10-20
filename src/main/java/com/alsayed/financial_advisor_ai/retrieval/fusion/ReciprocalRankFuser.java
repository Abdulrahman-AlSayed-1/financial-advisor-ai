package com.alsayed.financial_advisor_ai.retrieval.fusion;

import com.alsayed.financial_advisor_ai.retrieval.LexicalSearchService;
import com.alsayed.financial_advisor_ai.retrieval.StructuredCandidateRetriever;
import com.alsayed.financial_advisor_ai.retrieval.model.CandidateProvenance;
import com.alsayed.financial_advisor_ai.retrieval.model.MergedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Fuses multi-channel retrieval results (Dense Vector, Lexical BM25, and Structured Metadata)
 * using Reciprocal Rank Fusion (RRF) with multi-channel consensus bonus.
 * Follows Single Responsibility Principle (SRP).
 */
@Slf4j
@Component
public class ReciprocalRankFuser {

    private static final int DEFAULT_K = 60;
    private static final double WEIGHT_DENSE = 1.0;
    private static final double WEIGHT_LEXICAL = 1.0;
    private static final double WEIGHT_STRUCTURED = 1.0;
    private static final double CONSENSUS_BONUS_RATE = 0.15;

    /**
     * Fuses candidates from Dense, Lexical, and Structured channels using Reciprocal Rank Fusion (RRF).
     *
     * @param denseResults      Candidates from Vector search.
     * @param lexicalResults    Candidates from BM25 Lexical search.
     * @param structuredResults Candidates from Structured Metadata search.
     * @return Map of document ID to MergedDocument containing document payload and candidate provenance.
     */
    public Map<String, MergedDocument> fuse(
            List<Document> denseResults,
            List<LexicalSearchService.ScoredDocument> lexicalResults,
            List<StructuredCandidateRetriever.ScoredDocument> structuredResults) {

        Map<String, Document> docMap = new LinkedHashMap<>();
        Map<String, Integer> denseRanks = new HashMap<>();
        Map<String, Double> denseScores = new HashMap<>();
        Map<String, Integer> lexicalRanks = new HashMap<>();
        Map<String, Double> lexicalScores = new HashMap<>();
        Map<String, Integer> structuredRanks = new HashMap<>();
        Map<String, Double> structuredScores = new HashMap<>();
        Map<String, Set<String>> sourcesMap = new HashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 1. Dense Channel (Vector Search)
        if (denseResults != null) {
            for (int i = 0; i < denseResults.size(); i++) {
                Document doc = denseResults.get(i);
                String id = doc.getId();
                docMap.put(id, doc);
                int rank = i + 1;
                denseRanks.put(id, rank);
                denseScores.put(id, doc.getScore() != null ? doc.getScore() : (1.0 - (i * 0.01)));
                sourcesMap.computeIfAbsent(id, k1 -> new LinkedHashSet<>()).add("DENSE");
                rrfScores.merge(id, WEIGHT_DENSE / (DEFAULT_K + rank), Double::sum);
            }
        }

        // 2. Lexical Channel (PostgreSQL FTS + BM25)
        if (lexicalResults != null) {
            for (int i = 0; i < lexicalResults.size(); i++) {
                LexicalSearchService.ScoredDocument sc = lexicalResults.get(i);
                Document doc = sc.document();
                String id = doc.getId();
                docMap.putIfAbsent(id, doc);
                int rank = i + 1;
                lexicalRanks.put(id, rank);
                lexicalScores.put(id, sc.score());
                sourcesMap.computeIfAbsent(id, k1 -> new LinkedHashSet<>()).add("LEXICAL");
                rrfScores.merge(id, WEIGHT_LEXICAL / (DEFAULT_K + rank), Double::sum);
            }
        }

        // 3. Structured / Entity Channel
        if (structuredResults != null) {
            for (int i = 0; i < structuredResults.size(); i++) {
                StructuredCandidateRetriever.ScoredDocument sc = structuredResults.get(i);
                Document doc = sc.document();
                String id = doc.getId();
                docMap.putIfAbsent(id, doc);
                int rank = i + 1;
                structuredRanks.put(id, rank);
                structuredScores.put(id, sc.score());
                sourcesMap.computeIfAbsent(id, k1 -> new LinkedHashSet<>()).add("STRUCTURED");
                rrfScores.merge(id, WEIGHT_STRUCTURED / (DEFAULT_K + rank), Double::sum);
            }
        }

        // 4. Multi-channel consensus bonus: if a document appears across >= 2 channels,
        // apply a proportional bonus based on accumulated RRF score and channel coverage.
        for (String id : docMap.keySet()) {
            Set<String> sources = sourcesMap.getOrDefault(id, Set.of());
            int channelCount = sources.size();
            if (channelCount >= 2) {
                double currentRrf = rrfScores.getOrDefault(id, 0.0);
                double proportionalBonus = currentRrf * CONSENSUS_BONUS_RATE * (channelCount - 1);
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

        log.debug("RRF fusion merged {} unique documents across channels", merged.size());
        return merged;
    }
}
