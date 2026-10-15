package com.localai.contracts;

import java.util.UUID;

public record TurnResponse(UUID conversationId, UUID userMessageId, UUID assistantMessageId, String assistantReply) {
    public TurnResponse {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (userMessageId == null) {
            throw new IllegalArgumentException("userMessageId must not be null");
        }
        if (assistantMessageId == null) {
            throw new IllegalArgumentException("assistantMessageId must not be null");
        }
    }
}
