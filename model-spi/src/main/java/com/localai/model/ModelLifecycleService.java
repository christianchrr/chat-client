package com.localai.model;

import com.localai.contracts.ModelDownloadProgress;

public interface ModelLifecycleService {
    void installModel(String modelId);

    void uninstallModel(String modelId);

    ModelDownloadProgress downloadProgress(String modelId);

    default void cancelDownload(String modelId) {
    }

    default com.localai.contracts.ModelSpeedResult measureSpeed(String modelId) {
        throw new UnsupportedOperationException("Speed tests are not supported by this model provider");
    }
}
