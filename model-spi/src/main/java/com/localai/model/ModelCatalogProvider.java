package com.localai.model;

import java.util.List;

public interface ModelCatalogProvider {
    List<AvailableModel> listAvailableModels();
}
