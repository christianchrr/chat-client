package com.localai.model.ollama;

import com.localai.model.ModelGenerationService;
import com.localai.model.ModelGenerationException;
import com.localai.model.AvailableModel;
import com.localai.model.ModelCatalogException;
import com.localai.model.ModelCatalogProvider;
import com.localai.model.ModelLifecycleService;
import com.localai.model.ModelSelectionException;
import com.localai.model.ModelSelectionService;
import com.localai.contracts.ModelOption;
import com.localai.contracts.ModelDownloadProgress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class OllamaGenerationService
        implements ModelGenerationService, ModelCatalogProvider, ModelSelectionService, ModelLifecycleService {
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_ERROR_BODY_BYTES = 8_192;

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ModelOption> modelOptions;
    private final Path selectionFile;
    private final AtomicReference<String> activeModelId;
    private final ConcurrentHashMap<String, ModelDownloadProgress> downloadProgress = new ConcurrentHashMap<>();

    public OllamaGenerationService(URI baseUri) {
        this(baseUri, HttpClient.newBuilder().connectTimeout(READINESS_TIMEOUT).build(),
                "llama3.2", List.of(), null);
    }

    public OllamaGenerationService(URI baseUri, HttpClient httpClient) {
        this(baseUri, httpClient, "llama3.2", List.of(), null);
    }

    public OllamaGenerationService(
            URI baseUri,
            HttpClient httpClient,
            String defaultModelId,
            List<ModelOption> modelOptions,
            Path selectionFile) {
        if (baseUri == null) {
            throw new IllegalArgumentException("baseUri must not be null");
        }
        this.baseUri = baseUri;
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        if (defaultModelId == null || defaultModelId.isBlank() || modelOptions == null) {
            throw new IllegalArgumentException("Default model and model options are required");
        }
        this.modelOptions = modelOptions.stream()
                .filter(option -> option != null)
                .toList();
        if (this.modelOptions.size() != modelOptions.size()
                || this.modelOptions.stream().map(ModelOption::modelId).distinct().count() != this.modelOptions.size()) {
            throw new IllegalArgumentException("Configured model options must be non-null and unique");
        }
        this.selectionFile = selectionFile;
        String selected = readSelection(selectionFile, defaultModelId, this.modelOptions);
        if (selected != null && !this.modelOptions.isEmpty() && this.modelOptions.stream()
                .noneMatch(option -> option.modelId().equals(selected))) {
            throw new IllegalArgumentException("Default model must be included in the configured model allow-list");
        }
        this.activeModelId = new AtomicReference<>(selected);
    }

    @Override
    public String activeModelId() {
        return activeModelId.get();
    }

    @Override
    public List<ModelOption> modelOptions() {
        return modelOptions;
    }

    @Override
    public synchronized void selectModel(String modelId) {
        requireConfiguredModel(modelId);
        if (!isInstalled(modelId)) {
            installModel(modelId);
        }
        if (!modelId.equals(activeModelId.get())) {
            persistSelection(modelId);
            activeModelId.set(modelId);
        }
        preloadModel(modelId);
    }

    public void preloadModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                String body = objectMapper.createObjectNode()
                        .put("model", modelId)
                        .put("keep_alive", "30m")
                        .toString();
                HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/generate"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(GENERATION_TIMEOUT)
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Preloading is only an optimization; the first chat request loads the model if this fails.
            }
        });
    }

    @Override
    public synchronized void installModel(String modelId) {
        requireConfiguredModel(modelId);
        if (!isInstalled(modelId)) {
            downloadProgress.put(modelId, new ModelDownloadProgress(modelId, "downloading", 0, 0, "Starting download"));
            try {
                pullModel(modelId);
                downloadProgress.put(modelId, new ModelDownloadProgress(
                        modelId, "completed", 1, 1, "Download complete"));
            } catch (RuntimeException e) {
                downloadProgress.put(modelId, new ModelDownloadProgress(
                        modelId, "failed", 0, 0, e.getMessage() == null ? "Download failed" : e.getMessage()));
                throw e;
            }
        }
    }

    @Override
    public ModelDownloadProgress downloadProgress(String modelId) {
        requireConfiguredModel(modelId);
        return downloadProgress.getOrDefault(
                modelId, new ModelDownloadProgress(modelId, "idle", 0, 0, "No download is active"));
    }

    @Override
    public synchronized void uninstallModel(String modelId) {
        requireConfiguredModel(modelId);
        boolean uninstallingActiveModel = modelId.equals(activeModelId.get());
        if (!isInstalled(modelId)) {
            if (uninstallingActiveModel) {
                persistNoSelection();
                activeModelId.set(null);
            }
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/delete"))
                .timeout(READINESS_TIMEOUT)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        objectMapper.createObjectNode().put("name", modelId).toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelSelectionException("Ollama model removal failed with HTTP " + response.statusCode());
            }
            if (uninstallingActiveModel) {
                persistNoSelection();
                activeModelId.set(null);
            }
        } catch (IOException e) {
            throw new ModelSelectionException("Could not uninstall the local model.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelSelectionException("Model uninstallation was interrupted.", e);
        }
    }

    private void requireConfiguredModel(String modelId) {
        modelOptions.stream()
                .filter(candidate -> candidate.modelId().equals(modelId))
                .findFirst()
                .orElseThrow(() -> new ModelSelectionException("That model is not in the configured allow-list."));
    }

    @Override
    public boolean isReady(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        try {
            return listAvailableModels().stream().anyMatch(model ->
                    modelId.equals(model.modelId()) || (modelId + ":latest").equals(model.modelId()));
        } catch (ModelCatalogException e) {
            return false;
        }
    }

    private boolean isInstalled(String modelId) {
        try {
            return listAvailableModels().stream().anyMatch(model ->
                    modelId.equals(model.modelId()) || (modelId + ":latest").equals(model.modelId()));
        } catch (ModelCatalogException e) {
            throw new ModelSelectionException("Could not check which local models are installed.", e);
        }
    }

    private String readSelection(Path path, String defaultModelId, List<ModelOption> options) {
        if (path == null || !Files.isRegularFile(path)) {
            return defaultModelId;
        }
        try {
            String saved = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (com.localai.contracts.ModelSelectionResponse.NO_SELECTION_MARKER.equals(saved)) {
                return null;
            }
            return options.stream().anyMatch(option -> option.modelId().equals(saved))
                    ? saved : defaultModelId;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the saved model selection.", e);
        }
    }

    private void persistSelection(String modelId) {
        if (selectionFile == null) {
            return;
        }
        Path parent = selectionFile.toAbsolutePath().getParent();
        Path temporary = selectionFile.resolveSibling(selectionFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, modelId + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, selectionFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, selectionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ModelSelectionException("Could not save the selected model.", e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                throw new ModelSelectionException("Could not clean up the temporary model selection file.", e);
            }
        }
    }

    private void persistNoSelection() {
        if (selectionFile == null) {
            return;
        }
        Path parent = selectionFile.toAbsolutePath().getParent();
        Path temporary = selectionFile.resolveSibling(selectionFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary,
                    com.localai.contracts.ModelSelectionResponse.NO_SELECTION_MARKER + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, selectionFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, selectionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ModelSelectionException("Could not save the empty model selection.", e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                throw new ModelSelectionException("Could not clean up the temporary model selection file.", e);
            }
        }
    }

    private void pullModel(String modelId) {
        URI pullUri = baseUri.resolve("/api/pull");
        HttpRequest request = HttpRequest.newBuilder(pullUri)
                .timeout(Duration.ofHours(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.createObjectNode().put("name", modelId).put("stream", true).toString()))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new ModelSelectionException("Ollama model download failed with HTTP " + response.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                boolean completed = false;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode event = objectMapper.readTree(line);
                    if (event.path("error").isTextual()) {
                        throw new ModelSelectionException("Ollama model download failed: "
                                + event.path("error").asText());
                    }
                    String status = event.path("status").asText("Downloading model");
                    long completedBytes = nonNegativeLong(event.path("completed"));
                    long totalBytes = nonNegativeLong(event.path("total"));
                    downloadProgress.put(modelId, new ModelDownloadProgress(
                            modelId, "downloading", completedBytes, totalBytes, status));
                    if ("success".equalsIgnoreCase(status)) {
                        completed = true;
                    }
                }
                if (!completed) {
                    throw new ModelSelectionException("Ollama ended the download before reporting success.");
                }
            }
        } catch (IOException e) {
            throw new ModelSelectionException("Could not complete the Ollama model download.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelSelectionException("The Ollama model download was interrupted.", e);
        }
    }

    private long nonNegativeLong(JsonNode node) {
        return node.canConvertToLong() && node.longValue() > 0 ? node.longValue() : 0;
    }

    @Override
    public List<AvailableModel> listAvailableModels() {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/tags"))
                .timeout(READINESS_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelCatalogException("Ollama model catalog failed with status " + response.statusCode());
            }
            JsonNode models = objectMapper.readTree(response.body()).path("models");
            if (!models.isArray()) {
                throw new ModelCatalogException("Ollama returned an invalid model catalog");
            }

            List<AvailableModel> availableModels = new ArrayList<>();
            Set<String> modelIds = new HashSet<>();
            for (JsonNode model : models) {
                String modelId = textValue(model, "name");
                if (modelId == null) {
                    modelId = textValue(model, "model");
                }
                JsonNode sizeNode = model.path("size");
                if (modelId == null || !sizeNode.canConvertToLong() || sizeNode.longValue() < 0) {
                    throw new ModelCatalogException("Ollama returned a model without valid identity or size");
                }
                if (!modelIds.add(modelId)) {
                    throw new ModelCatalogException("Ollama returned a duplicate model identity");
                }

                JsonNode details = model.path("details");
                String modifiedAtValue = textValue(model, "modified_at");
                Instant modifiedAt = modifiedAtValue == null ? null : Instant.parse(modifiedAtValue);
                availableModels.add(new AvailableModel(
                        modelId,
                        textValue(model, "digest"),
                        sizeNode.longValue(),
                        modifiedAt,
                        textValue(details, "family"),
                        textArray(details.path("families")),
                        textValue(details, "parameter_size"),
                        textValue(details, "quantization_level")));
            }
            return availableModels.stream()
                    .sorted(Comparator.comparing(AvailableModel::modelId))
                    .toList();
        } catch (IOException e) {
            throw new ModelCatalogException("Could not read the Ollama model catalog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCatalogException("Ollama model catalog request was interrupted", e);
        } catch (java.time.DateTimeException e) {
            throw new ModelCatalogException("Ollama returned an invalid model modification timestamp", e);
        }
    }

    private String textValue(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isTextual() && !field.asText().isBlank() ? field.asText() : null;
    }

    private List<String> textArray(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return List.copyOf(values);
    }

    @Override
    public String generateText(String modelId, String prompt) {
        StringBuilder generated = new StringBuilder();
        streamText(modelId, prompt, generated::append);
        return generated.toString();
    }

    @Override
    public void streamText(String modelId, String prompt, Consumer<String> tokenConsumer) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (tokenConsumer == null) {
            throw new IllegalArgumentException("tokenConsumer must not be null");
        }

        String requestBody;
        try {
            requestBody = objectMapper.createObjectNode()
                    .put("model", modelId)
                    .put("prompt", prompt)
                    .put("stream", true)
                    .put("keep_alive", "30m")
                    .toString();
        } catch (RuntimeException e) {
            throw new ModelGenerationException("Failed to encode Ollama generation request", e);
        }
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(GENERATION_TIMEOUT)
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = readErrorBody(response.body());
                throw new ModelGenerationException("Ollama generation failed with HTTP "
                        + response.statusCode() + (errorBody.isBlank() ? "." : ": " + errorBody));
            }

            try (InputStream body = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                boolean receivedCompletion = false;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode chunk = objectMapper.readTree(line);
                    JsonNode token = chunk.get("response");
                    if (token != null && token.isTextual() && !token.asText().isEmpty()) {
                        tokenConsumer.accept(token.asText());
                    }
                    if (chunk.path("error").isTextual()) {
                        throw new ModelGenerationException("Ollama generation failed: " + chunk.path("error").asText());
                    }
                    if (chunk.path("done").asBoolean(false)) {
                        receivedCompletion = true;
                    }
                }
                if (!receivedCompletion) {
                    throw new ModelGenerationException("Ollama stream ended before generation completed");
                }
            } catch (IOException e) {
                String detail = e.getMessage() == null || e.getMessage().isBlank()
                        ? "the response stream was interrupted"
                        : e.getMessage();
                throw new ModelGenerationException("Could not read the Ollama response stream: " + detail, e);
            }
        } catch (IOException e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? "the local Ollama connection failed"
                    : e.getMessage();
            throw new ModelGenerationException("Could not read a response from local Ollama: " + detail, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelGenerationException("Ollama generation was interrupted", e);
        }
    }

    private String readErrorBody(InputStream body) throws IOException {
        try (body) {
            byte[] bytes = body.readNBytes(MAX_ERROR_BODY_BYTES);
            String response = new String(bytes, StandardCharsets.UTF_8).strip();
            if (response.isBlank()) {
                return "";
            }
            try {
                JsonNode error = objectMapper.readTree(response).path("error");
                if (error.isTextual() && !error.asText().isBlank()) {
                    return error.asText();
                }
            } catch (IOException ignored) {
                return response;
            }
            return response;
        }
    }
}

