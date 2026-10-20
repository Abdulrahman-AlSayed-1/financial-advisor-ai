package com.alsayed.financial_advisor_ai.retrieval.fusion;

import com.alsayed.financial_advisor_ai.retrieval.model.MergedDocument;
import com.alsayed.financial_advisor_ai.retrieval.model.ScoredDocument;
import com.alsayed.financial_advisor_ai.retrieval.rerank.RerankedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Combines RRF consensus scores and neural/heuristic reranking scores into a normalized final score.
 * Attaches comprehensive candidate provenance into the Document metadata.
 * Follows Single Responsibility Principle (SRP).
 */
@Slf4j
@Component
public class ScoreCombiner {

    private static final double RRF_WEIGHT = 0.40;
    private static final double RERANK_WEIGHT = 0.60;
    private static final double RRF_MAX_NORMALIZER = 0.05;
    private static final double RERANK_MAX_NORMALIZER = 2.0;

    /**
     * Combines vector, lexical, and reranking scores into final score and attaches candidate provenance.
     *
     * @param mergedCandidates Fused candidates from RRF with provenance.
     * @param reranked         Candidates scored by the reranker.
     * @return List of ScoredDocument objects ready for sorting.
     */
    public List<ScoredDocument> combine(
            Map<String, MergedDocument> mergedCandidates,
            List<RerankedDocument> reranked) {

        if (mergedCandidates == null || mergedCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> rerankScores = (reranked != null ? reranked.stream() : List.<RerankedDocument>of().stream())
                .collect(Collectors.toMap(
                        d -> d.document().getId(),
                        RerankedDocument::score,
                        (a, b) -> Math.max(a, b)));

        List<ScoredDocument> scored = new ArrayList<>();
        for (MergedDocument merged : mergedCandidates.values()) {
            Document doc = merged.document();
            String docId = doc.getId();
            double rrfScore = merged.provenance().rrfScore();
            double rerankScore = rerankScores.getOrDefault(docId, 0.0);

            // Record provenance in Document metadata for full auditability
            Map<String, Object> provMap = new LinkedHashMap<>();
            provMap.put("denseRank", merged.provenance().denseRank());
            provMap.put("lexicalRank", merged.provenance().lexicalRank());
            provMap.put("structuredRank", merged.provenance().structuredRank());
            provMap.put("sources", new ArrayList<>(merged.provenance().sources()));
            provMap.put("rrfScore", rrfScore);
            provMap.put("rerankScore", rerankScore);
            doc.getMetadata().put("retrieval_provenance", provMap);

            double normalizedRrf = Math.min(rrfScore / RRF_MAX_NORMALIZER, 1.0);
            double normalizedRerank = Math.min(rerankScore / RERANK_MAX_NORMALIZER, 1.0);

            double finalScore = (normalizedRrf * RRF_WEIGHT) + (normalizedRerank * RERANK_WEIGHT);

            scored.add(new ScoredDocument(doc, finalScore));
        }

        return scored;
    }
}
