package com.localai.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRuntimeConfig(
        List<String> command,
        Path workingDirectory,
        Path modelRoot,
        String host,
        int port,
        Map<String, String> environment
) {
    public ModelRuntimeConfig {
        Objects.requireNonNull(command, "command must not be null");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (workingDirectory == null) {
            workingDirectory = Path.of(".").toAbsolutePath().normalize();
        }
        if (modelRoot == null) {
            modelRoot = workingDirectory;
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        command = List.copyOf(new ArrayList<>(command));
        environment = environment == null ? Map.of() : Collections.unmodifiableMap(new java.util.HashMap<>(environment));
    }
}
