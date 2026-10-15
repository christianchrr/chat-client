package com.localai.model;

import java.util.Objects;

public record ModelDescriptor(
        String modelId,
        String providerId,
        String family,
        String version,
        String quantization,
        long sizeBytes,
        String runtimeCompatibility
) {
    public ModelDescriptor {
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(runtimeCompatibility, "runtimeCompatibility must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
    }
}
