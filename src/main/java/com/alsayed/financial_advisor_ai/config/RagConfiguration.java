package com.alsayed.financial_advisor_ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared RAG configuration supporting unified Google Gemini (or Ollama fallback).
 */
@Slf4j
@Configuration
public class RagConfiguration {

    @Value("${app.rag.search.top-k:10}")
    private int topK;

    @Value("${app.rag.search.similarity-threshold:0.3}")
    private double similarityThreshold;

    @Value("${app.chat.provider:gemini}")
    private String preferredChatProvider;


    @Bean
    public SearchRequest ragSearchRequest() {
        return SearchRequest.builder()
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
    }

    public SearchRequest ragSearchRequestWithPageFilter(Integer pageNumber) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .topK(topK)
                .similarityThreshold(similarityThreshold);
        
        if (pageNumber != null) {
            builder.filterExpression("metadata.page_number == '" + pageNumber + "'");
        }
        
        return builder.build();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(
            @Qualifier("googleGenAiChatModel") ObjectProvider<ChatModel> googleChatProvider,
            @Qualifier("ollamaChatModel") ObjectProvider<ChatModel> ollamaChatProvider) {

        ChatModel googleModel = googleChatProvider.getIfAvailable();
        ChatModel ollamaModel = ollamaChatProvider.getIfAvailable();
        ChatModel modelToUse;

        if ("gemini".equalsIgnoreCase(preferredChatProvider) && googleModel != null) {
            log.info("Using Google Gemini as the unified model for RAG Chat and Vision.");
            modelToUse = googleModel;
        } else if (ollamaModel != null) {
            log.info("Using Ollama for RAG Chat.");
            modelToUse = ollamaModel;
        } else if (googleModel != null) {
            log.info("Using Google Gemini for RAG Chat.");
            modelToUse = googleModel;
        } else {
            throw new IllegalStateException("No ChatModel bean is available. Please configure GOOGLE_GENAI_API_KEY in .env or start Ollama.");
        }

        return ChatClient.builder(modelToUse);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .build();
    }
}