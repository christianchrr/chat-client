package com.localai.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContextSummary(
        UUID id,
        UUID conversationId,
        String content,
        List<UUID> sourceMessageIds,
        String modelId,
        Instant createdAt) {
    public ContextSummary {
        if (id == null || conversationId == null || content == null || content.isBlank()
                || modelId == null || modelId.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("Context summary fields are required");
        }
        sourceMessageIds = List.copyOf(sourceMessageIds);
        if (sourceMessageIds.isEmpty() || sourceMessageIds.stream().anyMatch(java.util.Objects::isNull)
                || sourceMessageIds.stream().distinct().count() != sourceMessageIds.size()) {
            throw new IllegalArgumentException("Context summary sources must be unique message IDs");
        }
    }
}
