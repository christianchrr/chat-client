package com.localai.contracts;

import java.time.Instant;
import java.util.UUID;

public record TurnRecord(
        UUID id,
        UUID conversationId,
        UUID userMessageId,
        String clientRequestId,
        String requestHash,
        TurnStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
