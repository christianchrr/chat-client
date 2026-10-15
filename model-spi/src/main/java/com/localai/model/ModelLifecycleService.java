package com.localai.model;

import com.localai.contracts.ModelDownloadProgress;

public interface ModelLifecycleService {
    void installModel(String modelId);

    void uninstallModel(String modelId);

    ModelDownloadProgress downloadProgress(String modelId);
}
