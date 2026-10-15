package com.localai.contracts;

import java.time.Instant;
import java.util.UUID;

public record ConversationRecord(UUID id, String title, Instant createdAt, Instant updatedAt) {
    public ConversationRecord {
        if (id == null) {
            throw new IllegalArgumentException("Conversation id must not be null");
        }
        if (title == null || title.isBlank()) {
            title = "New conversation";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}
