package com.alsayed.financial_advisor_ai.retrieval;

import com.alsayed.financial_advisor_ai.retrieval.rerank.RerankedDocument;
import com.alsayed.financial_advisor_ai.retrieval.rerank.Reranker;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Neural Cross-Encoder Reranker using Cohere Rerank API (v3.5).
 * Natively resolves entity discrepancies, financial terminology, and vocabulary mismatch.
 */
@Slf4j
@Service
public class CohereNeuralReranker implements Reranker {

    private final RestClient restClient;
    private final String apiKey;
    private final String url;
    private final String model;
    private final boolean enabled;

    public CohereNeuralReranker(
            RestClient.Builder restClientBuilder,
            @Value("${app.rag.search.reranker.cohere.api-key:${app.rag.reranker.cohere.api-key:}}") String apiKey,
            @Value("${app.rag.search.reranker.cohere.url:${app.rag.reranker.cohere.url:https://api.cohere.com/v1/rerank}}") String url,
            @Value("${app.rag.search.reranker.cohere.model:${app.rag.reranker.cohere.model:rerank-v3.5}}") String model,
            @Value("${app.rag.search.reranker.cohere.enabled:${app.rag.reranker.cohere.enabled:true}}") boolean enabled) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.url = url;
        this.model = model;
        this.enabled = enabled;
    }

    public boolean isConfigured() {
        return enabled && !apiKey.isBlank();
    }

    /**
     * Reranks candidate documents using Cohere Neural Cross-Encoder.
     * Returns the documents wrapped with their neural relevance score.
     */
    @Override
    public List<RerankedDocument> rerank(String query, List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        if (!isConfigured()) {
            log.warn("Cohere Neural Reranker is not configured or disabled. Returning unranked candidates.");
            return candidates.stream()
                    .map(doc -> new RerankedDocument(doc, 0.0))
                    .toList();
        }

        try {
            log.info("Sending {} candidate documents to Cohere Neural Reranker ({}) for query: '{}'",
                    candidates.size(), model, query);

            List<String> documentTexts = candidates.stream()
                    .map(doc -> {
                        String text = doc.getText();
                        // Truncate individual document text if excessively long to avoid token limits
                        if (text != null && text.length() > 4000) {
                            return text.substring(0, 4000);
                        }
                        return text != null ? text : "";
                    })
                    .toList();

            CohereRerankRequest requestPayload = new CohereRerankRequest(
                    model,
                    query,
                    documentTexts,
                    candidates.size()
            );

            CohereRerankResponse response = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestPayload)
                    .retrieve()
                    .body(CohereRerankResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("Cohere returned empty reranking results");
                return candidates.stream()
                        .map(doc -> new RerankedDocument(doc, 0.0))
                        .toList();
            }

            List<RerankedDocument> rerankedList = new ArrayList<>();
            Set<Integer> includedIndices = new HashSet<>();

            for (CohereRerankResult res : response.results()) {
                int idx = res.index();
                if (idx >= 0 && idx < candidates.size()) {
                    Document doc = candidates.get(idx);
                    // Cohere relevance_score is 0.0 to 1.0. Scale by 2.0 to match the system's normalization factor
                    double scaledScore = res.relevanceScore() * 2.0;
                    rerankedList.add(new RerankedDocument(doc, scaledScore));
                    includedIndices.add(idx);
                }
            }

            // Include any remaining candidates that were not in the top results with score 0.0
            for (int i = 0; i < candidates.size(); i++) {
                if (!includedIndices.contains(i)) {
                    rerankedList.add(new RerankedDocument(candidates.get(i), 0.0));
                }
            }

            log.info("Cohere reranking completed successfully. Top candidate relevance: {}",
                    response.results().get(0).relevanceScore());

            return rerankedList;

        } catch (Exception e) {
            log.error("Failed to execute Cohere neural reranking: {}. Falling back to default scoring.", e.getMessage(), e);
            throw new RuntimeException("Cohere rerank execution failed", e);
        }
    }

    // DTOs for Cohere API
    public record CohereRerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CohereRerankResponse(
            String id,
            List<CohereRerankResult> results
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CohereRerankResult(
            int index,
            @JsonProperty("relevance_score") double relevanceScore
    ) {}
}
