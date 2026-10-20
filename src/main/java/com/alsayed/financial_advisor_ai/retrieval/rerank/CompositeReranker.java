package com.alsayed.financial_advisor_ai.retrieval.rerank;

import com.alsayed.financial_advisor_ai.retrieval.CohereNeuralReranker;
import com.alsayed.financial_advisor_ai.retrieval.MetadataReranker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Composite reranker that routes to Neural Cross-Encoder (Cohere) when enabled and configured,
 * with automatic, graceful fallback to the in-process heuristic MetadataReranker.
 * Demonstrates the Graceful Degradation pattern and adheres to SOLID principles.
 */
@Slf4j
@Primary
@Service
public class CompositeReranker implements Reranker {

    private final CohereNeuralReranker cohereNeuralReranker;
    private final MetadataReranker metadataReranker;
    private final String preferredProvider;

    public CompositeReranker(
            CohereNeuralReranker cohereNeuralReranker,
            MetadataReranker metadataReranker,
            @Value("${app.rag.search.reranker.provider:cohere}") String preferredProvider) {
        this.cohereNeuralReranker = cohereNeuralReranker;
        this.metadataReranker = metadataReranker;
        this.preferredProvider = preferredProvider;
    }

    @Override
    public List<RerankedDocument> rerank(String query, List<Document> candidates) {
        if ("cohere".equalsIgnoreCase(preferredProvider) && cohereNeuralReranker != null && cohereNeuralReranker.isConfigured()) {
            try {
                return cohereNeuralReranker.rerank(query, candidates);
            } catch (Exception e) {
                log.warn("Primary neural reranker failed ({}), gracefully degrading to heuristic reranker", e.getMessage());
                return metadataReranker.rerank(query, candidates);
            }
        }
        return metadataReranker.rerank(query, candidates);
    }
}
