package com.localai.contracts;

import java.time.Instant;
import java.util.UUID;

public record MessageRecord(UUID id, UUID conversationId, MessageRole role, String content, Instant createdAt) {
    public MessageRecord {
        if (id == null) {
            throw new IllegalArgumentException("Message id must not be null");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation id must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("Message role must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("Message content must not be null");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
