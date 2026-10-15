package com.localai.contracts;

public record ApiVersion(String value) {
    public ApiVersion {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("API version must not be blank");
        }
    }
}
