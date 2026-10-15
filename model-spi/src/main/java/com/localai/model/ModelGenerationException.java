package com.localai.model;

public final class ModelGenerationException extends RuntimeException {
    public ModelGenerationException(String message) {
        super(message);
    }

    public ModelGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
