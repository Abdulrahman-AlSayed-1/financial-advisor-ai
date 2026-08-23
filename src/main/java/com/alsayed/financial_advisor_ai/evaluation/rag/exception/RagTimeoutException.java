package com.alsayed.financial_advisor_ai.evaluation.rag.exception;

/**
 * Exception thrown when a RAG query times out.
 */
public class RagTimeoutException extends RagQueryException {

    public RagTimeoutException(String message) {
        super(message);
    }

    public RagTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
