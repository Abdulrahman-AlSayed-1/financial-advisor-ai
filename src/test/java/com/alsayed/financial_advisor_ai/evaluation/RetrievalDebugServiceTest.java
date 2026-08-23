package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.RetrievalDebugResult;
import com.alsayed.financial_advisor_ai.evaluation.dto.RetrievalDatasetResult;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.RetrievalEvaluator;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetrievalDebugServiceTest {

    @Mock
    private HybridRetrievalService hybridRetrievalService;

    @Mock
    private RetrievalEvaluator retrievalEvaluator;

    private RetrievalDebugService retrievalDebugService;

    @BeforeEach
    void setUp() {
        retrievalDebugService = new RetrievalDebugService(hybridRetrievalService, retrievalEvaluator);
    }

    @Test
    void debugRetrieval_normalQuestion_returnsRetrievedChunks() {
        // Arrange
        String question = "What is the yield?";
        int topK = 5;
        
        List<Document> mockDocuments = createTestDocuments(5);
        when(hybridRetrievalService.search(eq(question), eq(topK))).thenReturn(mockDocuments);
        
        // Act
        List<RetrievalDebugResult> results = retrievalDebugService.debugRetrieval(question, topK);
        
        // Assert
        assertEquals(5, results.size(), "Should return 5 retrieved chunks");
        assertEquals(1, results.get(0).getRank(), "First chunk should have rank 1");
        assertEquals(2, results.get(1).getRank(), "Second chunk should have rank 2");
        assertEquals("chunk-1", results.get(0).getChunkId(), "First chunk ID should match");
        
        verify(hybridRetrievalService, times(1)).search(eq(question), eq(topK));
    }

    @Test
    void debugRetrieval_withCanonicalTableChunk_metadataExtractedCorrectly() {
        // Arrange
        String question = "What is the return?";
        int topK = 1;
        
        Document canonicalTableChunk = createCanonicalTableChunk();
        when(hybridRetrievalService.search(eq(question), eq(topK))).thenReturn(List.of(canonicalTableChunk));
        
        // Act
        List<RetrievalDebugResult> results = retrievalDebugService.debugRetrieval(question, topK);
        
        // Assert
        assertEquals(1, results.size());
        RetrievalDebugResult result = results.get(0);
        
        assertEquals(40, result.getPage(), "Page should be 40");
        assertEquals("Asset Class Return Analysis (%)", result.getSection(), "Section should match");
        assertEquals("99c8bf38-455a-0f8a-e75f-852c814f604f", result.getTableId(), "Table ID should match");
        assertEquals("S&P 500 Utilities", result.getRowEntity(), "Row entity should match");
        assertEquals("Return (%)", result.getMetric(), "Metric should match");
        assertEquals("YTD", result.getPeriod(), "Period should match");
        assertEquals("%", result.getUnit(), "Unit should match");
        assertEquals("30.63", result.getValue(), "Value should match");
        assertEquals("table", result.getChunkType(), "Chunk type should be table");
    }

    @Test
    void debugRetrieval_withMissingMetadata_noException() {
        // Arrange
        String question = "What is the yield?";
        int topK = 1;
        
        Document chunkWithMissingMetadata = new Document("chunk-1", "Test content", new HashMap<>());
        when(hybridRetrievalService.search(eq(question), eq(topK))).thenReturn(List.of(chunkWithMissingMetadata));
        
        // Act & Assert - should not throw exception
        assertDoesNotThrow(() -> {
            List<RetrievalDebugResult> results = retrievalDebugService.debugRetrieval(question, topK);
            assertEquals(1, results.size());
            assertNull(results.get(0).getPage(), "Page should be null when missing");
            assertNull(results.get(0).getSection(), " Section should be null when missing");
            assertNull(results.get(0).getTableId(), "Table ID should be null when missing");
        });
    }

    @Test
    void debugRetrieval_doesNotInvokeChatClient() {
        // Arrange
        String question = "What is the yield?";
        int topK = 5;
        
        when(hybridRetrievalService.search(eq(question), eq(topK))).thenReturn(createTestDocuments(5));
        
        // Act
        retrievalDebugService.debugRetrieval(question, topK);
        
        // Assert - verify only HybridRetrievalService was called, not any ChatClient
        verify(hybridRetrievalService, times(1)).search(eq(question), eq(topK));
        // No ChatClient in RetrievalDebugService, so this test validates the design
    }

    @Test
    void evaluateDatasetRetrieval_calculatesHitMetricsCorrectly() {
        // Arrange
        List<EvaluationQuestion> questions = createTestQuestions(3);
        int topK = 10;
        
        List<Document> mockDocuments = createTestDocuments(10);
        when(hybridRetrievalService.search(any(String.class), eq(topK))).thenReturn(mockDocuments);
        
        // Configure hit results: Q1 passes both, Q2 passes only Hit@10, Q3 fails both
        when(retrievalEvaluator.hitAtK(eq(questions.get(0)), any(), eq(5))).thenReturn(true);
        when(retrievalEvaluator.hitAtK(eq(questions.get(0)), any(), eq(10))).thenReturn(true);
        when(retrievalEvaluator.hitAtK(eq(questions.get(1)), any(), eq(5))).thenReturn(false);
        when(retrievalEvaluator.hitAtK(eq(questions.get(1)), any(), eq(10))).thenReturn(true);
        when(retrievalEvaluator.hitAtK(eq(questions.get(2)), any(), eq(5))).thenReturn(false);
        when(retrievalEvaluator.hitAtK(eq(questions.get(2)), any(), eq(10))).thenReturn(false);
        
        // Act
        RetrievalDatasetResult result = retrievalDebugService.evaluateDatasetRetrieval(questions, topK);
        
        // Assert
        assertEquals(3, result.getTotalQuestions(), "Total questions should be 3");
        assertEquals(1, result.getHitAt5Count(), "Hit@5 count should be 1 (only Q1 passed)");
        assertEquals(2, result.getHitAt10Count(), "Hit@10 count should be 2 (Q1 and Q2 passed)");
        assertEquals(1.0/3.0, result.getHitAt5Rate(), 0.001, "Hit@5 rate should be 33.3%");
        assertEquals(2.0/3.0, result.getHitAt10Rate(), 0.001, "Hit@10 rate should be 66.7%");
        assertEquals(3, result.getQuestionResults().size(), "Should have 3 question results");
        
        verify(hybridRetrievalService, times(3)).search(any(String.class), eq(topK));
        verify(retrievalEvaluator, times(6)).hitAtK(any(), any(), anyInt()); // 3 questions * 2 metrics each
    }

    @Test
    void evaluateDatasetRetrieval_emptyQuestions_returnsEmptyResult() {
        // Arrange
        List<EvaluationQuestion> questions = List.of();
        int topK = 10;
        
        // Act
        RetrievalDatasetResult result = retrievalDebugService.evaluateDatasetRetrieval(questions, topK);
        
        // Assert
        assertEquals(0, result.getTotalQuestions(), "Total questions should be 0");
        assertEquals(0, result.getHitAt5Count(), "Hit@5 count should be 0");
        assertEquals(0, result.getHitAt10Count(), "Hit@10 count should be 0");
        assertEquals(0.0, result.getHitAt5Rate(), 0.001, "Hit@5 rate should be 0%");
        assertEquals(0.0, result.getHitAt10Rate(), 0.001, "Hit@10 rate should be 0%");
        assertTrue(result.getQuestionResults().isEmpty(), "Question results should be empty");
        
        verify(hybridRetrievalService, never()).search(any(), anyInt());
        verify(retrievalEvaluator, never()).hitAtK(any(), any(), anyInt());
    }

    // Helper methods

    private List<Document> createTestDocuments(int count) {
        return java.util.stream.IntStream.range(1, count + 1)
                .mapToObj(i -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("page_number", i);
                    metadata.put("chunk_type", i % 2 == 0 ? "table" : "text");
                    metadata.put("section", "Test Section " + i);
                    return new Document("chunk-" + i, "Test content for chunk " + i, metadata);
                })
                .toList();
    }

    private Document createCanonicalTableChunk() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("page_number", 40);
        metadata.put("section", "Asset Class Return Analysis (%)");
        metadata.put("table_id", "99c8bf38-455a-0f8a-e75f-852c814f604f");
        metadata.put("row_entity", "S&P 500 Utilities");
        metadata.put("metric", "Return (%)");
        metadata.put("period", "YTD");
        metadata.put("unit", "%");
        metadata.put("value", "30.63");
        metadata.put("chunk_type", "table");
        
        return new Document("chunk-1", "Table content with S&P 500 Utilities data", metadata);
    }

    private List<EvaluationQuestion> createTestQuestions(int count) {
        return java.util.stream.IntStream.range(1, count + 1)
                .mapToObj(i -> {
                    EvaluationQuestion question = new EvaluationQuestion();
                    question.setId("q-" + i);
                    question.setQuestionText("Question " + i);
                    question.setGroundTruth("Answer " + i);
                    return question;
                })
                .toList();
    }
}
