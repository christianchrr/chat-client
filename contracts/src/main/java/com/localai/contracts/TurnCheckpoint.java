package com.localai.contracts;

import java.time.Instant;
import java.util.UUID;

public record TurnCheckpoint(UUID turnId, String partialAssistantText, Instant updatedAt) {
    public TurnCheckpoint {
        if (turnId == null || partialAssistantText == null || updatedAt == null) {
            throw new IllegalArgumentException("Turn checkpoint fields are required");
        }
    }
}
