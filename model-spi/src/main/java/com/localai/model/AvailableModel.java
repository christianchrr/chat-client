package com.localai.model;

import java.time.Instant;
import java.util.List;

public record AvailableModel(
        String modelId,
        String digest,
        long sizeBytes,
        Instant modifiedAt,
        String family,
        List<String> families,
        String parameterSize,
        String quantizationLevel) {
    public AvailableModel {
        if (modelId == null || modelId.isBlank() || sizeBytes < 0) {
            throw new IllegalArgumentException("Model identity and size are invalid");
        }
        families = families == null ? List.of() : List.copyOf(families);
    }
}
