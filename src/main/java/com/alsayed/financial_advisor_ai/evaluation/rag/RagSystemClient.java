package com.alsayed.financial_advisor_ai.evaluation.rag;

import com.alsayed.financial_advisor_ai.evaluation.deduplication.DocumentDeduplicator;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResponse;
import com.alsayed.financial_advisor_ai.evaluation.rag.exception.RagQueryException;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with the RAG system during evaluation.
 * Handles RAG system calls with document retrieval, deduplication, and response building.
 */
@Slf4j
@Component
public class RagSystemClient {

    private final ChatClient chatClient;
    private final DocumentDeduplicator documentDeduplicator;
    private final HybridRetrievalService hybridRetrievalService;

    @Value("${app.rag.search.hybrid.enabled:false}")
    private boolean hybridEnabled;

    private static final int ANSWER_PREVIEW_LENGTH = 300;
    private static final int CONTEXT_PREVIEW_LENGTH = 300;

    public RagSystemClient(ChatClient.Builder chatClientBuilder,
                            DocumentDeduplicator documentDeduplicator,
                            HybridRetrievalService hybridRetrievalService) {
        // Build dedicated chat client without QuestionAnswerAdvisor for clean prompting
        this.chatClient = chatClientBuilder.build();
        this.documentDeduplicator = documentDeduplicator;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * Query the RAG system and return the response with context.
     *
     * @param question The question to ask the RAG system
     * @return EvaluationResponse containing the answer and retrieved contexts
     * @throws RagQueryException if the RAG call fails
     */
    public EvaluationResponse queryRagSystem(String question) {
        if (hybridEnabled) {
            return queryRagSystemHybrid(question);
        }
        return queryRagSystemStandard(question);
    }

    /**
     * Query RAG system using hybrid retrieval (vector + lexical + reranking).
     */
    private EvaluationResponse queryRagSystemHybrid(String question) {
        long totalStartTime = System.currentTimeMillis();
        log.info("=== Hybrid RAG Query ===");
        log.info("Question: {}", question);

        // 1. Perform Hybrid Retrieval
        List<Document> retrievedDocs;
        try {
            long retrievalStartTime = System.currentTimeMillis();
            retrievedDocs = hybridRetrievalService.search(question, 10);
            long retrievalTime = System.currentTimeMillis() - retrievalStartTime;
            log.info("Hybrid retrieval completed in {} ms, retrieved {} documents", 
                retrievalTime, retrievedDocs.size());
        } catch (Exception e) {
            log.error("Hybrid retrieval failed for question: {}", question, e);
            throw new RagQueryException("Hybrid retrieval failed", e);
        }

        // Deduplicate for evaluation
        List<Document> uniqueDocs = deduplicateDocuments(retrievedDocs);
        List<String> uniqueContexts = uniqueDocs.stream().map(Document::getText).toList();
        logRetrievedContexts(retrievedDocs, uniqueDocs);

        // 2. Generate Answer using Clean ChatClient
        String answer;
        try {
            List<String> contexts = retrievedDocs.stream().map(Document::getText).toList();
            String contextText = String.join("\n\n", contexts);

            String prompt;
            if (isComparativeQuery(question)) {
                String formattedContext = formatContextForComparative(contexts);
                prompt = "Answer the following question using ONLY the provided context.\n\n" +
                         "Context:\n" + formattedContext + "\n\n" +
                         "Question: " + question + "\n\n" +
                         "IMPORTANT INSTRUCTIONS:\n" +
                         "1. This is a comparative question asking for highest/lowest/top/bottom values.\n" +
                         "2. The context above contains the entities that match the query criteria.\n" +
                         "3. 'YTD' in the context means 'Year-to-Date' and corresponds to 'YTD total returns through September 30, 2024'.\n" +
                         "4. The entities listed in the context ARE the answer to the question.\n" +
                         "5. Simply report the entities and their values from the context.\n" +
                         "6. Do NOT say you need more entities or that the context is incomplete.\n\n" +
                         "Do NOT hallucinate or guess. Only use entities and values explicitly listed in the context.\n\n" +
                         "If the answer is not in the context, say so.";
            } else {
                prompt = "Answer the following question using ONLY the provided context:\n\n" +
                         "Context:\n" + contextText + "\n\n" +
                         "Question: " + question + "\n\n" +
                         "If the answer is not in the context, say so.";
            }

            long llmStartTime = System.currentTimeMillis();
            answer = chatClient.prompt().user(prompt).call().content();
            long llmTime = System.currentTimeMillis() - llmStartTime;
            log.info("LLM generation completed in {} ms", llmTime);
        } catch (Exception e) {
            log.warn("LLM answer generation failed for question '{}': {}. Proceeding with retrieved contexts for evaluation.",
                    question, e.getMessage());
            answer = "LLM_GENERATION_UNAVAILABLE: " + e.getMessage();
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        log.info("Hybrid RAG query completed - retrieved: {}, unique: {}, total latency: {}ms", 
            retrievedDocs.size(), uniqueDocs.size(), totalTime);

        return buildEvaluationResponse(answer != null ? answer : "No answer generated", retrievedDocs, uniqueContexts);
    }

    /**
     * Query RAG system using standard vector search (Spring AI QuestionAnswerAdvisor).
     */
    private EvaluationResponse queryRagSystemStandard(String question) {
        long totalStartTime = System.currentTimeMillis();
        log.info("=== Standard RAG Query ===");
        log.info("Question: {}", question);

        try {
            long llmStartTime = System.currentTimeMillis();
            var callResponseSpec = chatClient.prompt().user(question).call();
            long llmTime = System.currentTimeMillis() - llmStartTime;
            log.info("LLM call completed in {} ms", llmTime);

            long answerStartTime = System.currentTimeMillis();
            var chatClientResponse = callResponseSpec.chatClientResponse();
            String answer = chatClientResponse.chatResponse().getResult().getOutput().getText();
            long answerTime = System.currentTimeMillis() - answerStartTime;
            log.debug("Answer extracted in {} ms", answerTime);
            log.debug("Answer preview: {}", preview(answer, ANSWER_PREVIEW_LENGTH));

            long extractionStartTime = System.currentTimeMillis();
            List<Document> retrievedDocs = extractRetrievedDocuments(chatClientResponse);
            long extractionTime = System.currentTimeMillis() - extractionStartTime;
            log.debug("Document extraction completed in {} ms", extractionTime);

            long deduplicationStartTime = System.currentTimeMillis();
            List<Document> uniqueDocs = deduplicateDocuments(retrievedDocs);
            long deduplicationTime = System.currentTimeMillis() - deduplicationStartTime;
            log.debug("Deduplication completed in {} ms", deduplicationTime);

            List<String> contexts = uniqueDocs.stream().map(Document::getText).toList();
            logRetrievedContexts(retrievedDocs, uniqueDocs);

            long totalTime = System.currentTimeMillis() - totalStartTime;
            log.info("RAG query completed - retrieved: {}, unique: {}, total latency: {}ms", 
                retrievedDocs.size(), uniqueDocs.size(), totalTime);

            return buildEvaluationResponse(answer, retrievedDocs, contexts);

        } catch (Exception e) {
            log.error("Standard RAG query failed for question: {}", question, e);
            throw new RagQueryException("Standard RAG system call failed", e);
        }
    }

    /**
     * Check if a query is a comparative query (asking for highest/lowest/top/bottom).
     */
    private boolean isComparativeQuery(String question) {
        String lower = question.toLowerCase();
        return lower.contains("highest") || lower.contains("lowest") ||
               lower.contains("top") || lower.contains("bottom") ||
               lower.contains("best") || lower.contains("worst") ||
               lower.contains("most") || lower.contains("least") ||
               lower.contains("which two") || lower.contains("which 2") ||
               lower.contains("which three") || lower.contains("which 3");
    }

    /**
     * Format contexts for comparative queries in a cleaner, more structured way.
     */
    private String formatContextForComparative(List<String> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here are the relevant data entries from the report:\n\n");
        
        int count = 1;
        for (String context : contexts) {
            String cleanContext = context.replace("\n", " ").trim();
            sb.append(count++).append(". ").append(cleanContext).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Deduplicate documents by content while preserving order.
     */
    private List<Document> deduplicateDocuments(List<Document> documents) {
        return documentDeduplicator.deduplicate(documents);
    }

    /**
     * Log retrieved and unique contexts with preview.
     */
    private void logRetrievedContexts(List<Document> retrievedDocs, List<Document> uniqueDocs) {
        log.info("Raw retrieved documents: {} chunks", retrievedDocs.size());
        log.info("Deduplicated documents: {} unique chunks", uniqueDocs.size());

        for (int i = 0; i < uniqueDocs.size(); i++) {
            Document doc = uniqueDocs.get(i);
            String preview = preview(doc.getText(), CONTEXT_PREVIEW_LENGTH);
            log.debug("Context [{}]: ID={}, metadata={}, preview={}", 
                i + 1, doc.getId(), doc.getMetadata(), preview);
        }
    }

    /**
     * Build the evaluation response DTO.
     */
    private EvaluationResponse buildEvaluationResponse(String answer, List<Document> retrievedDocs, List<String> contexts) {
        return EvaluationResponse.builder()
                .answer(answer)
                .retrievedDocuments(retrievedDocs)
                .contexts(contexts)
                .build();
    }

    /**
     * Extract retrieved documents from ChatClientResponse metadata.
     */
    @SuppressWarnings("unchecked")
    private List<Document> extractRetrievedDocuments(Object chatClientResponse) {
        try {
            var response = (org.springframework.ai.chat.client.ChatClientResponse) chatClientResponse;
            var contextMap = response.context();
            
            if (contextMap != null && contextMap.containsKey(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS)) {
                var docs = contextMap.get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
                if (docs instanceof List<?>) {
                    return (List<Document>) docs;
                }
            }
            log.warn("No retrieved documents found in ChatClientResponse context");
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to extract retrieved documents from response", e);
            return new ArrayList<>();
        }
    }

    /**
     * Truncate text for logging preview.
     */
    private String preview(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
