package com.localai.contracts;

public record TurnRequest(String content, String clientRequestId) {
    public TurnRequest {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Turn content must not be blank");
        }
    }
}
