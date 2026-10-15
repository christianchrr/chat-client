package com.localai.contracts;

public record ModelOption(
        String modelId,
        String displayName,
        long estimatedDownloadBytes,
        long recommendedVramBytes,
        long recommendedRamBytes) {
    public ModelOption(
            String modelId,
            long estimatedDownloadBytes,
            long recommendedVramBytes,
            long recommendedRamBytes) {
        this(modelId, modelId, estimatedDownloadBytes, recommendedVramBytes, recommendedRamBytes);
    }

    public ModelOption {
        if (modelId == null || modelId.isBlank()
                || displayName == null || displayName.isBlank()
                || estimatedDownloadBytes < 0
                || recommendedVramBytes < 0
                || recommendedRamBytes < 0) {
            throw new IllegalArgumentException("Model option requirements are invalid");
        }
    }
}
