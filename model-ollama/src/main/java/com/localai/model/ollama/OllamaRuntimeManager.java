package com.localai.model.ollama;

import com.localai.model.ModelRuntimeConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class OllamaRuntimeManager {
    private final ModelRuntimeConfig config;
    private final HttpClient httpClient;
    private Process process;

    public OllamaRuntimeManager(ModelRuntimeConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    public OllamaRuntimeManager(ModelRuntimeConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public synchronized Process start() throws IOException {
        ensureModelRoot();
        if (process != null && process.isAlive()) {
            return process;
        }

        List<String> command = new ArrayList<>(config.command());
        command.addAll(List.of(
                "--host", config.host(),
                "--port", Integer.toString(config.port()),
                "--model-root", config.modelRoot().toString()
        ));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(config.workingDirectory().toFile())
                .redirectErrorStream(true);
        for (var entry : config.environment().entrySet()) {
            builder.environment().put(entry.getKey(), entry.getValue());
        }

        process = builder.start();
        return process;
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public boolean waitForReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isReady()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isReady();
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        process = null;
    }

    private boolean isReady() {
        try {
            URI uri = URI.create("http://" + config.host() + ":" + config.port() + "/api/version");
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureModelRoot() throws IOException {
        if (config.modelRoot() == null) {
            throw new IllegalStateException("model root must not be null");
        }
        Files.createDirectories(config.modelRoot());
    }
}
