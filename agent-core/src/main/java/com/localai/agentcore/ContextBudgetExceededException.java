package com.localai.agentcore;

public final class ContextBudgetExceededException extends RuntimeException {
    public ContextBudgetExceededException(String message) {
        super(message);
    }
}
