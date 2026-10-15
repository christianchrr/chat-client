package com.localai.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContextManifest(
        UUID turnId,
        String modelId,
        String templateVersion,
        int tokenBudget,
        int estimatedTokens,
        List<UUID> selectedMessageIds,
        List<UUID> selectedSummaryIds,
        Instant createdAt) {
    public ContextManifest {
        if (turnId == null || modelId == null || modelId.isBlank()
                || templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("Context manifest identity and versions are required");
        }
        if (tokenBudget < 1 || estimatedTokens < 0 || estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("Context manifest token counts are invalid");
        }
        selectedMessageIds = List.copyOf(selectedMessageIds);
        selectedSummaryIds = selectedSummaryIds == null ? List.of() : List.copyOf(selectedSummaryIds);
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
