package com.localai.model;

public final class ModelCatalogException extends RuntimeException {
    public ModelCatalogException(String message) {
        super(message);
    }

    public ModelCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
