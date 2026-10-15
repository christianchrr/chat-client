package com.localai.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.localai.contracts.ConversationCreateRequest;
import com.localai.contracts.ConversationRecord;
import com.localai.contracts.ContextSummary;
import com.localai.contracts.ContextSummaryRequest;
import com.localai.contracts.MessageRecord;
import com.localai.contracts.ModelCatalogEntry;
import com.localai.contracts.ModelDownloadProgress;
import com.localai.contracts.ModelOption;
import com.localai.contracts.ModelSelectionRequest;
import com.localai.contracts.ModelSelectionResponse;
import com.localai.contracts.StreamEvent;
import com.localai.contracts.TurnRequest;
import com.localai.contracts.TurnResponse;
import com.localai.contracts.TurnStatusResponse;
import com.localai.contracts.ToolInvocationStatusResponse;
import com.localai.contracts.TurnCheckpoint;
import com.localai.contracts.WebContextRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class LocalApiClient {
    private static final Duration TURN_STREAM_TIMEOUT = Duration.ofMinutes(35);
    private final URI baseUri;
    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LocalApiClient(URI baseUri, String token) {
        this(baseUri, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    LocalApiClient(URI baseUri, String token, HttpClient httpClient) {
        if (baseUri == null || !"http".equalsIgnoreCase(baseUri.getScheme())
                || baseUri.getHost() == null || !baseUri.getHost().equalsIgnoreCase("127.0.0.1")
                    && !baseUri.getHost().equalsIgnoreCase("localhost")
                    && !baseUri.getHost().equalsIgnoreCase("::1")) {
            throw new IllegalArgumentException("baseUri must use HTTP and a loopback host");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient must not be null");
        }
        this.baseUri = baseUri;
        this.token = token;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public ConversationRecord createConversation(String title) {
        return send("POST", "/api/v1/conversations", new ConversationCreateRequest(title), ConversationRecord.class);
    }

    public List<ConversationRecord> listConversations() {
        ConversationRecord[] conversations = send("GET", "/api/v1/conversations", null, ConversationRecord[].class);
        return List.copyOf(Arrays.asList(conversations));
    }

    public void deleteConversation(java.util.UUID conversationId) {
        send("DELETE", "/api/v1/conversations/" + conversationId, null,
                com.fasterxml.jackson.databind.JsonNode.class);
    }

    public List<ModelCatalogEntry> listAvailableModels() {
        ModelCatalogEntry[] models = send("GET", "/api/v1/models", null, ModelCatalogEntry[].class);
        return List.copyOf(Arrays.asList(models));
    }

    public List<ModelOption> listModelOptions() {
        ModelOption[] options = send("GET", "/api/v1/models/options", null, ModelOption[].class);
        return List.copyOf(Arrays.asList(options));
    }

    public void installModel(String modelId) {
        send("POST", "/api/v1/models/install", new ModelSelectionRequest(modelId),
                com.fasterxml.jackson.databind.JsonNode.class, Duration.ofHours(12));
    }

    public boolean modelSupportsThinking(String modelId) {
        String encodedModelId = java.net.URLEncoder.encode(modelId, java.nio.charset.StandardCharsets.UTF_8);
        return send("GET", "/api/v1/models/capabilities?modelId=" + encodedModelId, null,
                com.fasterxml.jackson.databind.JsonNode.class).path("thinking").asBoolean(false);
    }

    public com.localai.contracts.ModelSpeedResult measureModelSpeed(String modelId) {
        return send("POST", "/api/v1/models/speed-test", new ModelSelectionRequest(modelId),
                com.localai.contracts.ModelSpeedResult.class, Duration.ofMinutes(5));
    }

    public void cancelModelDownload(String modelId) {
        send("POST", "/api/v1/models/cancel-download", new ModelSelectionRequest(modelId),
                com.fasterxml.jackson.databind.JsonNode.class);
    }

    public void uninstallModel(String modelId) {
        send("POST", "/api/v1/models/uninstall", new ModelSelectionRequest(modelId),
                com.fasterxml.jackson.databind.JsonNode.class);
    }

    public ModelDownloadProgress getModelDownloadProgress(String modelId) {
        String encodedModelId = java.net.URLEncoder.encode(modelId, java.nio.charset.StandardCharsets.UTF_8);
        return send("GET", "/api/v1/models/download-progress?modelId=" + encodedModelId,
                null, ModelDownloadProgress.class);
    }

    public ModelSelectionResponse getModelSelection() {
        return send("GET", "/api/v1/models/selection", null, ModelSelectionResponse.class);
    }

    public ModelSelectionResponse selectModel(String modelId) {
        return send(
                "POST",
                "/api/v1/models/selection",
                new ModelSelectionRequest(modelId),
                ModelSelectionResponse.class,
                Duration.ofHours(12));
    }

    public List<MessageRecord> listMessages(UUID conversationId) {
        MessageRecord[] messages = send("GET", "/api/v1/conversations/" + conversationId + "/messages",
                null, MessageRecord[].class);
        return List.copyOf(Arrays.asList(messages));
    }

    public List<MessageRecord> addWebContext(UUID conversationId, List<String> urls) {
        MessageRecord[] sources = send(
                "POST",
                "/api/v1/conversations/" + conversationId + "/web-context",
                new WebContextRequest(urls),
                MessageRecord[].class);
        return List.copyOf(Arrays.asList(sources));
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record DropboxFile(String name, long sizeBytes, boolean editable) {
    }

    private static String encodeSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    public List<DropboxFile> listDropboxFiles() {
        return List.copyOf(Arrays.asList(send("GET", "/api/v1/dropbox/files", null, DropboxFile[].class)));
    }

    public DropboxFile uploadDropboxFile(String name, byte[] content) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/dropbox/files/" + encodeSegment(name)))
                .timeout(Duration.ofSeconds(60))
                .header("X-Local-Auth", token)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new LocalApiException("Upload failed: " + response.body());
            }
            return objectMapper.readValue(response.body(), DropboxFile.class);
        } catch (IOException e) {
            throw new LocalApiException("Could not communicate with the local API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalApiException("Local API request was interrupted", e);
        }
    }

    public void deleteDropboxFile(String name) {
        send("DELETE", "/api/v1/dropbox/files/" + encodeSegment(name), null, Object.class);
    }

    public String saveDropboxEdit(String name, String content) {
        com.fasterxml.jackson.databind.JsonNode result = send(
                "POST", "/api/v1/dropbox/files/" + encodeSegment(name) + "/edited",
                java.util.Map.of("content", content), com.fasterxml.jackson.databind.JsonNode.class);
        return result.get("name").asText();
    }

    public List<MessageRecord> addDropboxContext(UUID conversationId, List<String> names) {
        return addDropboxContext(conversationId, names, null);
    }

    public List<MessageRecord> addDropboxContext(UUID conversationId, List<String> names, String query) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("names", names);
        body.put("query", query);
        MessageRecord[] sources = send(
                "POST", "/api/v1/conversations/" + conversationId + "/dropbox-context",
                body, MessageRecord[].class);
        return List.copyOf(Arrays.asList(sources));
    }

    public List<TurnStatusResponse> listTurns(UUID conversationId) {
        TurnStatusResponse[] turns = send(
                "GET",
                "/api/v1/conversations/" + conversationId + "/turns",
                null,
                TurnStatusResponse[].class);
        return List.copyOf(Arrays.asList(turns));
    }

    public List<ToolInvocationStatusResponse> listToolInvocations(UUID turnId) {
        ToolInvocationStatusResponse[] invocations = send(
                "GET",
                "/api/v1/turns/" + turnId + "/tool-invocations",
                null,
                ToolInvocationStatusResponse[].class);
        return List.copyOf(Arrays.asList(invocations));
    }

    public List<ContextSummary> listContextSummaries(UUID conversationId) {
        ContextSummary[] summaries = send(
                "GET",
                "/api/v1/conversations/" + conversationId + "/summaries",
                null,
                ContextSummary[].class);
        return List.copyOf(Arrays.asList(summaries));
    }

    public ContextSummary createContextSummary(UUID conversationId, List<UUID> sourceMessageIds) {
        return send(
                "POST",
                "/api/v1/conversations/" + conversationId + "/summaries",
                new ContextSummaryRequest(sourceMessageIds),
                ContextSummary.class);
    }

    public void deleteContextSummary(UUID conversationId, UUID summaryId) {
        send("DELETE", "/api/v1/conversations/" + conversationId + "/summaries/" + summaryId,
                null, Object.class);
    }

    public TurnResponse submitTurn(UUID conversationId, String content) {
        return send("POST", "/api/v1/conversations/" + conversationId + "/turns",
                new TurnRequest(content, UUID.randomUUID().toString()), TurnResponse.class);
    }

    public void streamTurn(UUID conversationId, String content, Consumer<StreamEvent> eventConsumer) {
        streamTurn(conversationId, content, UUID.randomUUID().toString(), eventConsumer);
    }

    public void streamTurn(
            UUID conversationId,
            String content,
            String clientRequestId,
            Consumer<StreamEvent> eventConsumer) {
        streamTurn(conversationId, content, clientRequestId, false, eventConsumer);
    }

    public void streamTurn(
            UUID conversationId,
            String content,
            String clientRequestId,
            boolean thinking,
            Consumer<StreamEvent> eventConsumer) {
        if (eventConsumer == null) {
            throw new IllegalArgumentException("eventConsumer must not be null");
        }
        String path = "/api/v1/conversations/" + conversationId + "/turns/stream";
        StreamCursor cursor = new StreamCursor();
        try {
            String requestBody = objectMapper.writeValueAsString(new TurnRequest(content, clientRequestId, thinking));
            for (int attempt = 0; attempt <= 3; attempt++) {
                HttpRequest request = attempt == 0
                        ? streamPost(path, requestBody)
                        : replayRequest(cursor);
                try {
                    if (readEventStream(request, cursor, eventConsumer)) {
                        return;
                    }
                } catch (IOException e) {
                    if (cursor.turnId == null || attempt == 3) {
                        throw e;
                    }
                }
                if (cursor.turnId == null) {
                    throw new LocalApiException("Stream disconnected before the server identified the turn");
                }
                if (attempt < 3) {
                    Thread.sleep(200L << attempt);
                }
            }
            throw new LocalApiException("Local API stream could not reconnect after three attempts");
        } catch (IOException e) {
            throw new LocalApiException("Could not read the local API event stream", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalApiException("Local API stream was interrupted", e);
        }
    }

    private HttpRequest streamPost(String path, String requestBody) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(TURN_STREAM_TIMEOUT)
                .header("X-Local-Auth", token)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    private HttpRequest replayRequest(StreamCursor cursor) {
        if (cursor.turnId == null) {
            throw new LocalApiException("Cannot reconnect before receiving the turn ID");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(
                        "/api/v1/turns/" + cursor.turnId + "/events"))
                .timeout(TURN_STREAM_TIMEOUT)
                .header("X-Local-Auth", token)
                .header("Accept", "text/event-stream")
                .GET();
        if (cursor.lastEventId != null) {
            builder.header("Last-Event-ID", cursor.lastEventId);
        }
        return builder.build();
    }

    private boolean readEventStream(
            HttpRequest request,
            StreamCursor cursor,
            Consumer<StreamEvent> eventConsumer) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new LocalApiException("Local API stream failed with status " + response.statusCode());
        }
        String turnIdHeader = response.headers().firstValue("X-Turn-ID").orElse(null);
        if (turnIdHeader != null) {
            UUID responseTurnId;
            try {
                responseTurnId = UUID.fromString(turnIdHeader);
            } catch (IllegalArgumentException e) {
                response.body().close();
                throw new LocalApiException("Local API returned an invalid turn ID", e);
            }
            if (cursor.turnId != null && !cursor.turnId.equals(responseTurnId)) {
                response.body().close();
                throw new LocalApiException("Local API response turn ID does not match the event stream");
            }
            cursor.turnId = responseTurnId;
        }
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String eventJson = line.substring("data:".length()).trim();
                StreamEvent event = objectMapper.readValue(eventJson, StreamEvent.class);
                if (cursor.turnId != null && !cursor.turnId.equals(event.turnId())) {
                    throw new LocalApiException("Event stream returned an unexpected turn ID");
                }
                eventConsumer.accept(event);
                cursor.turnId = event.turnId();
                cursor.lastEventId = event.eventId();
                if ("turn.completed".equals(event.type()) || "turn.failed".equals(event.type())
                        || "turn.cancelled".equals(event.type()) || "turn.interrupted".equals(event.type())
                        || "turn.needs_review".equals(event.type())) {
                    cursor.terminal = true;
                    return true;
                }
            }
            return cursor.terminal;
        }
    }

    private static final class StreamCursor {
        private UUID turnId;
        private String lastEventId;
        private boolean terminal;
    }

    public void cancelTurn(UUID turnId) {
        send("POST", "/api/v1/turns/" + turnId + "/cancel", null, Object.class);
    }

    public Optional<TurnCheckpoint> getTurnCheckpoint(UUID turnId) {
        HttpRequest request = HttpRequest.newBuilder(
                        baseUri.resolve("/api/v1/turns/" + turnId + "/checkpoint"))
                .timeout(Duration.ofSeconds(35))
                .header("X-Local-Auth", token)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LocalApiException(
                        "Local API checkpoint request failed with status " + response.statusCode());
            }
            return Optional.of(objectMapper.readValue(response.body(), TurnCheckpoint.class));
        } catch (IOException e) {
            throw new LocalApiException("Could not read the local API checkpoint", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalApiException("Local API checkpoint request was interrupted", e);
        }
    }

    private <T> T send(String method, String path, Object body, Class<T> responseType) {
        return send(method, path, body, responseType, Duration.ofSeconds(35));
    }

    private <T> T send(
            String method,
            String path,
            Object body,
            Class<T> responseType,
            Duration timeout) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(timeout)
                .header("X-Local-Auth", token)
                .header("Accept", "application/json");
        try {
            if (body == null) {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                requestBuilder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = response.body() == null ? "" : response.body().strip();
                if (detail.length() > 300) {
                    detail = detail.substring(0, 300) + "...";
                }
                throw new LocalApiException(
                        method + " " + path + " returned HTTP " + response.statusCode()
                                + (detail.isBlank() ? "" : ": " + detail));
            }
            if (response.statusCode() == 204) {
                return null;
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new LocalApiException("Could not communicate with the local API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalApiException("Local API request was interrupted", e);
        }
    }

    public static final class LocalApiException extends RuntimeException {
        LocalApiException(String message) {
            super(message);
        }

        LocalApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
