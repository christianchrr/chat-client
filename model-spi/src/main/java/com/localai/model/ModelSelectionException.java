package com.localai.model;

public final class ModelSelectionException extends RuntimeException {
    public ModelSelectionException(String message) {
        super(message);
    }

    public ModelSelectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
