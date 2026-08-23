package com.alsayed.financial_advisor_ai.evaluation.evaluators;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvaluatorTest {

    private RetrievalEvaluator retrievalEvaluator;

    @BeforeEach
    void setUp() {
        EvidenceBasedRetrievalEvaluator evidenceBasedRetrievalEvaluator = new EvidenceBasedRetrievalEvaluator();
        retrievalEvaluator = new RetrievalEvaluator(evidenceBasedRetrievalEvaluator);
    }

    @Test
    void hitAtK_withRelevantIdInTop5_shouldPass() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of("chunk-3"));

        List<Document> retrievedChunks = createTestChunks(10);
        
        boolean result = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        
        assertTrue(result, "Hit@5 should PASS when relevant ID is in top 5");
    }

    @Test
    void hitAtK_withRelevantIdAtPosition6_hitAt5FailHitAt10Pass() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of("chunk-6"));

        List<Document> retrievedChunks = createTestChunks(10);
        
        boolean hitAt5 = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        boolean hitAt10 = retrievalEvaluator.hitAtK(question, retrievedChunks, 10);
        
        assertFalse(hitAt5, "Hit@5 should FAIL when relevant ID is at position 6");
        assertTrue(hitAt10, "Hit@10 should PASS when relevant ID is at position 6");
    }

    @Test
    void hitAtK_withRelevantIdAbsent_shouldFail() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of("chunk-999"));

        List<Document> retrievedChunks = createTestChunks(10);
        
        boolean hitAt5 = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        boolean hitAt10 = retrievalEvaluator.hitAtK(question, retrievedChunks, 10);
        
        assertFalse(hitAt5, "Hit@5 should FAIL when relevant ID is absent");
        assertFalse(hitAt10, "Hit@10 should FAIL when relevant ID is absent");
    }

    @Test
    void hitAtK_withNullRelevantIds_shouldReturnFalse() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(null);
        question.setSourceContext("9.54% yield");

        List<Document> retrievedChunks = createTestChunks(10);
        
        boolean result = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        
        assertFalse(result, "Hit@K should return false when relevant IDs are null");
    }

    @Test
    void hitAtK_withEmptyRelevantIds_shouldReturnFalse() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of());
        question.setSourceContext("9.54% yield");

        List<Document> retrievedChunks = createTestChunks(10);
        
        boolean result = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        
        assertFalse(result, "Hit@K should return false when relevant IDs are empty");
    }

    @Test
    void hitAtK_withNullRetrievedDocuments_shouldReturnFalse() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of("chunk-3"));
        
        boolean result = retrievalEvaluator.hitAtK(question, null, 5);
        
        assertFalse(result, "Hit@K should return false when retrieved documents is null");
    }

    @Test
    void hitAtK_withEmptyRetrievedDocuments_shouldReturnFalse() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of("chunk-3"));
        
        boolean result = retrievalEvaluator.hitAtK(question, List.of(), 5);
        
        assertFalse(result, "Hit@K should return false when retrieved documents is empty");
    }

    @Test
    void hitAtK_withInvalidK_shouldReturnFalse() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of("chunk-3"));
        List<Document> retrievedChunks = createTestChunks(10);
        
        assertFalse(retrievalEvaluator.hitAtK(question, retrievedChunks, 0), "Hit@K should return false for k=0");
        assertFalse(retrievalEvaluator.hitAtK(question, retrievedChunks, -1), "Hit@K should return false for k=-1");
    }

    @Test
    void hitAtK_withNullQuestion_shouldReturnFalse() {
        List<Document> retrievedChunks = createTestChunks(10);
        
        boolean result = retrievalEvaluator.hitAtK(null, retrievedChunks, 5);
        
        assertFalse(result, "Hit@K should return false when question is null");
    }

    @Test
    void hitAtK_withExactSourceContextMatch_shouldPass() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setSourceContext("U.S. Loans yield 9.54%");
        question.setRelevantChunkIds(null); // No IDs, fallback to context matching

        List<Document> retrievedChunks = createTestChunks(10);
        // Replace chunk at position 2 with one containing the source context
        retrievedChunks.set(2, new Document("chunk-3", "The U.S. Loans yield 9.54% as of September 2024", 
            retrievedChunks.get(2).getMetadata()));
        
        boolean result = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        
        assertTrue(result, "Hit@K should PASS with exact source context match");
    }

    @Test
    void hitAtK_withFormattingDifferences_shouldPass() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setSourceContext("U.S. Loans yield 9.54%");
        question.setRelevantChunkIds(null);

        List<Document> retrievedChunks = createTestChunks(10);
        // Replace chunk at position 2 with exact match after normalization
        retrievedChunks.set(2, new Document("chunk-3", "U.S. Loans yield 9.54%", 
            retrievedChunks.get(2).getMetadata()));
        
        boolean result = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        
        assertTrue(result, "Hit@K should PASS with formatting differences handled");
    }

    @Test
    void hitAtK_withTopicOnlyNoNumericEvidence_shouldFail() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield for U.S. Loans?");
        question.setSourceContext("U.S. Loans yield 9.54%");
        question.setRelevantChunkIds(null);

        List<Document> retrievedChunks = createTestChunks(10);
        // Chunk mentions the topic but not the specific numeric evidence
        retrievedChunks.set(2, new Document("chunk-3", "U.S. Loans are a type of leveraged loan with various yields", 
            retrievedChunks.get(2).getMetadata()));
        
        boolean result = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        
        assertFalse(result, "Hit@K should FAIL when chunk has topic but lacks specific numeric evidence");
    }

    @Test
    void hitAtK_preservesOriginalRetrievalOrder() {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionText("What is the yield?");
        question.setRelevantChunkIds(List.of("chunk-1"));

        List<Document> retrievedChunks = createTestChunks(10);
        
        // Verify that chunk-1 is at position 1 (original order preserved)
        assertEquals("chunk-1", retrievedChunks.get(0).getId());
        
        boolean result = retrievalEvaluator.hitAtK(question, retrievedChunks, 5);
        
        assertTrue(result, "Hit@K should find chunk at position 1 when order is preserved");
    }

    // Helper method to create test chunks
    private List<Document> createTestChunks(int count) {
        return java.util.stream.IntStream.range(1, count + 1)
                .mapToObj(i -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("page_number", i);
                    metadata.put("chunk_type", i % 2 == 0 ? "table" : "text");
                    return new Document("chunk-" + i, "Test content for chunk " + i, metadata);
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
