package com.localai.model;

import java.util.List;

public interface ModelProvider {
    String providerId();

    List<ModelDescriptor> listInstalledModels();

    default boolean isSupported(String modelId) {
        return listInstalledModels().stream().anyMatch(m -> m.modelId().equals(modelId));
    }
}
