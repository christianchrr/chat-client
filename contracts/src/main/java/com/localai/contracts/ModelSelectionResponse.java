package com.localai.contracts;

public record ModelSelectionResponse(String modelId, boolean installed) {
    public static final String NO_SELECTION_MARKER = "none";

    public ModelSelectionResponse {
        if ((modelId == null || modelId.isBlank()) && installed) {
            throw new IllegalArgumentException("An unselected model cannot be installed");
        }
    }
}
