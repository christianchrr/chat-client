package com.localai.launcher;

import com.localai.apihost.ApiHostApplication;
import com.localai.contracts.ModelOption;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductLauncherApiTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recognizesPersistedNoModelSelectionInsteadOfApplyingDefault() throws Exception {
        Path selectionFile = temporaryDirectory.resolve("selected-model.txt");
        List<ModelOption> options = List.of(new ModelOption("qwen3:30b", 1, 1, 1));

        assertFalse(ProductLauncher.hasExplicitNoSelection(selectionFile));
        Files.writeString(selectionFile,
                com.localai.contracts.ModelSelectionResponse.NO_SELECTION_MARKER + System.lineSeparator());

        assertTrue(ProductLauncher.hasExplicitNoSelection(selectionFile));
        assertTrue(ProductLauncher.selectedModel(selectionFile, options).isEmpty());
    }

    @Test
    void packagedApiReadsAndCreatesConversationsUsingItsSqliteDatabase() throws Exception {
        String token = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext api = new SpringApplicationBuilder(ApiHostApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.address=127.0.0.1",
                        "local.api.token=" + token,
                        "local.ai.model.id=qwen3:30b",
                        "local.ai.db.path=" + temporaryDirectory.resolve("data").resolve("assistant.db"),
                        "local.ai.model.selection.path=" + temporaryDirectory.resolve("selected-model.txt"),
                        "local.ai.ollama.base-url=http://127.0.0.1:1")
                .run(
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--local.ai.model.id=qwen3:30b",
                        "--local.ai.db.path=" + temporaryDirectory.resolve("data").resolve("assistant.db"),
                        "--local.ai.model.selection.path=" + temporaryDirectory.resolve("selected-model.txt"),
                        "--local.ai.ollama.base-url=http://127.0.0.1:1")) {
            Environment environment = api.getEnvironment();
            int port = environment.getProperty("local.server.port", Integer.class);
            URI baseUri = URI.create("http://127.0.0.1:" + port);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            HttpResponse<String> options = send(client, baseUri, token, "GET", "/api/v1/models/options", null);
            assertEquals(200, options.statusCode());
            assertTrue(options.body().contains("llama3.2:latest"));
            assertTrue(options.body().contains("qwen3:14b"));
            assertTrue(options.body().contains("qwen3:30b"));
            assertTrue(options.body().contains("qwen3:32b"));
            assertTrue(!options.body().contains("qwen3:235b"));

            HttpResponse<String> selected = send(client, baseUri, token, "GET", "/api/v1/models/selection", null);
            assertEquals(200, selected.statusCode());
            assertTrue(selected.body().contains("\"modelId\":\"qwen3:30b\""), selected.body());

            HttpResponse<String> disallowed = send(
                    client,
                    baseUri,
                    token,
                    "POST",
                    "/api/v1/models/selection",
                    "{\"modelId\":\"qwen3:235b\"}");
            assertEquals(400, disallowed.statusCode());

            HttpResponse<String> initial = send(client, baseUri, token, "GET", "/api/v1/conversations", null);
            assertEquals(200, initial.statusCode());
            assertEquals("[]", initial.body());

            HttpResponse<String> created = send(
                    client,
                    baseUri,
                    token,
                    "POST",
                    "/api/v1/conversations",
                    "{\"title\":\"First chat\"}");
            assertEquals(200, created.statusCode());
            assertTrue(created.body().contains("\"title\":\"First chat\""));

            Matcher idMatcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created.body());
            assertTrue(idMatcher.find());
            String conversationId = idMatcher.group(1);
            HttpResponse<String> messages = send(
                    client, baseUri, token, "GET", "/api/v1/conversations/" + conversationId + "/messages", null);
            assertEquals(200, messages.statusCode());
            assertEquals("[]", messages.body());

            HttpResponse<String> turns = send(
                    client, baseUri, token, "GET", "/api/v1/conversations/" + conversationId + "/turns", null);
            assertEquals(200, turns.statusCode());
            assertEquals("[]", turns.body());

            HttpResponse<String> search = send(
                    client,
                    baseUri,
                    token,
                    "GET",
                    "/api/v1/conversations/" + conversationId + "/search?query=hello",
                    null);
            assertEquals(200, search.statusCode());
            assertEquals("[]", search.body());

            HttpResponse<String> summaries = send(
                    client, baseUri, token, "GET", "/api/v1/conversations/" + conversationId + "/summaries", null);
            assertEquals(200, summaries.statusCode());
            assertEquals("[]", summaries.body());
        }
    }

    @Test
    void streamedChatTurnCompletesAgainstInstalledLocalModelWhenConfigured() throws Exception {
        String ollamaUrl = System.getenv("LOCALAI_TEST_OLLAMA_URL");
        Assumptions.assumeTrue(ollamaUrl != null && !ollamaUrl.isBlank());

        String token = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext api = new SpringApplicationBuilder(ApiHostApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.address=127.0.0.1",
                        "local.api.token=" + token,
                        "local.ai.model.id=llama3.2",
                        "local.ai.db.path=" + temporaryDirectory.resolve("chat-data").resolve("assistant.db"),
                        "local.ai.ollama.base-url=" + ollamaUrl)
                .run(
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--local.ai.ollama.base-url=" + ollamaUrl)) {
            int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
            URI baseUri = URI.create("http://127.0.0.1:" + port);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            HttpResponse<String> created = send(
                    client, baseUri, token, "POST", "/api/v1/conversations", "{\"title\":\"Model smoke test\"}");
            assertEquals(200, created.statusCode());
            Matcher idMatcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created.body());
            assertTrue(idMatcher.find());
            String conversationId = idMatcher.group(1);

            HttpResponse<String> turn = send(
                    client,
                    baseUri,
                    token,
                    "POST",
                    "/api/v1/conversations/" + conversationId + "/turns/stream",
                    "{\"content\":\"Reply with exactly: local chatbot works.\",\"clientRequestId\":\""
                            + UUID.randomUUID() + "\"}");
            assertEquals(200, turn.statusCode());
            assertTrue(turn.body().contains("turn.completed"), turn.body());
            assertTrue(turn.body().contains("token.delta"), turn.body());
        }
    }

    @Test
    void streamedChatTurnReturnsServerSentEventsOverHttp() throws Exception {
        HttpServer ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/generate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"response\":\"Local \",\"done\":false}\n"
                    + "{\"response\":\"chat works.\",\"done\":true}\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        ollama.start();

        String token = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext api = new SpringApplicationBuilder(ApiHostApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "local.api.token=" + token,
                        "local.ai.model.id=llama3.2",
                        "local.ai.db.path=" + temporaryDirectory.resolve("stream-data").resolve("assistant.db"))
                .run(
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--local.ai.ollama.base-url=http://127.0.0.1:" + ollama.getAddress().getPort())) {
            int port = api.getEnvironment().getProperty("local.server.port", Integer.class);
            URI baseUri = URI.create("http://127.0.0.1:" + port);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> created = send(
                    client, baseUri, token, "POST", "/api/v1/conversations", "{\"title\":\"Stream test\"}");
            assertEquals(200, created.statusCode());
            Matcher idMatcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created.body());
            assertTrue(idMatcher.find());

            HttpResponse<String> turn = send(
                    client,
                    baseUri,
                    token,
                    "POST",
                    "/api/v1/conversations/" + idMatcher.group(1) + "/turns/stream",
                    "{\"content\":\"Say hello.\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}");
            assertEquals(200, turn.statusCode());
            assertTrue(turn.body().contains("event: token.delta"), turn.body());
            assertTrue(turn.body().contains("event: turn.completed"), turn.body());
        } finally {
            ollama.stop(0);
        }
    }

    private HttpResponse<String> send(
            HttpClient client,
            URI baseUri,
            String token,
            String method,
            String path,
            String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Local-Auth", token)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
