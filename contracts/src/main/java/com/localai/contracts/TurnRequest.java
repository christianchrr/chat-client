package com.localai.contracts;

public record TurnRequest(String content, String clientRequestId, Boolean thinking) {
    public TurnRequest(String content, String clientRequestId) {
        this(content, clientRequestId, null);
    }

    public TurnRequest {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Turn content must not be blank");
        }
    }
}
