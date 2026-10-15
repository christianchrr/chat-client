package com.localai.contracts;

public record ModelDownloadProgress(
        String modelId,
        String status,
        long completedBytes,
        long totalBytes,
        String message) {
    public ModelDownloadProgress {
        if (modelId == null || modelId.isBlank()
                || status == null || status.isBlank()
                || completedBytes < 0 || totalBytes < 0) {
            throw new IllegalArgumentException("Model download progress is invalid");
        }
        message = message == null ? "" : message;
    }

    public boolean hasByteProgress() {
        return totalBytes > 0;
    }
}
