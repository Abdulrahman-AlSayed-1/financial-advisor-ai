package com.alsayed.financial_advisor_ai.evaluation.rag.exception;

/**
 * Base exception for RAG query failures.
 */
public class RagQueryException extends RuntimeException {

    public RagQueryException(String message) {
        super(message);
    }

    public RagQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
