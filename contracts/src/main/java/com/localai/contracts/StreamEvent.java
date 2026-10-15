package com.localai.contracts;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record StreamEvent(
        String eventId,
        UUID turnId,
        Instant timestamp,
        String type,
        long sequence,
        Map<String, String> payload,
        int schemaVersion
) {
    public StreamEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (turnId == null) {
            throw new IllegalArgumentException("turnId must not be null");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
    }
}
