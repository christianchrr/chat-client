package com.localai.model;

import com.localai.contracts.ModelOption;

import java.util.List;

public interface ModelSelectionService {
    String activeModelId();

    List<ModelOption> modelOptions();

    void selectModel(String modelId);
}
