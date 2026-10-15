package com.localai.contracts;

import java.time.Instant;
import java.util.UUID;

public record TurnStatusResponse(
        UUID turnId,
        UUID conversationId,
        UUID userMessageId,
        TurnStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
