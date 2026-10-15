package com.localai.contracts;

import java.util.List;
import java.util.UUID;

public record ContextSummaryRequest(List<UUID> sourceMessageIds) {
    public ContextSummaryRequest {
        if (sourceMessageIds == null || sourceMessageIds.size() < 2 || sourceMessageIds.size() > 200) {
            throw new IllegalArgumentException("Two to 200 source message IDs are required");
        }
        sourceMessageIds = List.copyOf(sourceMessageIds);
        if (sourceMessageIds.stream().anyMatch(java.util.Objects::isNull)
                || sourceMessageIds.stream().distinct().count() != sourceMessageIds.size()) {
            throw new IllegalArgumentException("Source message IDs must be unique and non-null");
        }
    }
}
