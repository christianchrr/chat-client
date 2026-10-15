package com.localai.launcher;

@FunctionalInterface
public interface SetupProgress {
    void update(String message, long completedBytes, long totalBytes);
}
