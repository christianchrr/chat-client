package com.localai.model.ollama;

import com.localai.model.ModelSelectionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaGenerationServiceTest {

    @Test
    void generatesTextFromConfiguredOllamaEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> generationRequest = new AtomicReference<>();
        server.createContext("/api/tags", exchange -> {
            byte[] response = """
                    {"models":[
                      {"name":"stub-model:latest","modified_at":"2026-01-01T00:00:00Z","size":1234,
                       "digest":"sha256:stub","details":{"family":"llama","families":["llama"],
                       "parameter_size":"8B","quantization_level":"Q4_K_M"}},
                      {"model":"other:latest","size":456,"details":{"families":["mistral"]}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.createContext("/api/generate", exchange -> {
            generationRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"response\":\"hello \",\"done\":false}\n"
                    + "{\"response\":\"from stub\",\"done\":true}\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();

        try {
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            OllamaGenerationService service = new OllamaGenerationService(baseUri);
            assertTrue(service.isReady("stub-model"));
            assertFalse(service.isReady("missing-model"));
            var catalog = service.listAvailableModels();
            assertEquals(List.of("other:latest", "stub-model:latest"),
                    catalog.stream().map(com.localai.model.AvailableModel::modelId).toList());
            assertEquals(1234, catalog.getLast().sizeBytes());
            assertEquals("llama", catalog.getLast().family());
            assertEquals("Q4_K_M", catalog.getLast().quantizationLevel());
            List<String> chunks = new ArrayList<>();
            service.streamText("stub-model", "hello from test", chunks::add);
            assertEquals(List.of("hello ", "from stub"), chunks);
            assertEquals("hello from stub", service.generateText("stub-model", "hello from test"));
            assertTrue(generationRequest.get().contains("\"model\":\"stub-model\""));
            assertTrue(generationRequest.get().contains("\"prompt\":\"hello from test\""));
            assertTrue(generationRequest.get().contains("\"stream\":true"));
            assertTrue(generationRequest.get().contains("\"keep_alive\":\"30m\""));
            assertFalse(generationRequest.get().contains("\"think\""));
            assertTrue(generationRequest.get().contains("\"num_ctx\":8192"));
            assertFalse(generationRequest.get().contains("\"images\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void includesOllamaErrorWhenGenerationRequestIsRejected() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] response = "{\"error\":\"model requires more memory\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();

        try {
            var service = new OllamaGenerationService(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var error = assertThrows(
                    com.localai.model.ModelGenerationException.class,
                    () -> service.streamText("stub-model", "hello", ignored -> { }));
            assertTrue(error.getMessage().contains("HTTP 500"));
            assertTrue(error.getMessage().contains("model requires more memory"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void selectsOnlyAllowListedModelsAndPersistsAfterSuccessfulInstall() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("ollama-model-selection");
        Path selectedModel = temporaryDirectory.resolve("config").resolve("selected-model.txt");
        AtomicReference<String> installedModel = new AtomicReference<>("qwen3:14b");
        AtomicInteger pulls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            String model = installedModel.get();
            byte[] response = ("{\"models\":[{\"name\":\"" + model
                    + "\",\"size\":1000,\"details\":{}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.createContext("/api/pull", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"name\":\"qwen3:30b\""));
            pulls.incrementAndGet();
            installedModel.set("qwen3:30b");
            byte[] response = "{\"status\":\"success\"}\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();

        try {
            var options = ModelCatalogConfiguration.loadDefault();
            var service = new OllamaGenerationService(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    java.net.http.HttpClient.newHttpClient(),
                    "qwen3:14b",
                    options,
                    selectedModel);

            service.selectModel("qwen3:30b");
            assertEquals("qwen3:30b", service.activeModelId());
            assertEquals("qwen3:30b" + System.lineSeparator(), Files.readString(selectedModel));
            assertEquals(1, pulls.get());

            service.selectModel("qwen3:30b");
            assertEquals(1, pulls.get());
            org.junit.jupiter.api.Assertions.assertThrows(
                    ModelSelectionException.class, () -> service.selectModel("qwen3:235b"));
            assertEquals(List.of("llama3.2:latest", "qwen3:4b", "qwen3:8b", "qwen3:14b", "qwen3:30b", "qwen3:32b",
                "qwen2.5vl:7b", "llama3.2-vision:11b", "gemma3:4b", "phi4-mini:latest", "qwen2.5-coder:7b"),
                    options.stream().map(option -> option.modelId()).toList());
            assertTrue(options.getFirst().estimatedDownloadBytes() < options.get(1).estimatedDownloadBytes());
        } finally {
            server.stop(0);
            try (var paths = Files.walk(temporaryDirectory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void installsAndUninstallsConfiguredModelsWithoutChangingTheActiveSelection() throws Exception {
        AtomicReference<java.util.Set<String>> installed =
                new AtomicReference<>(java.util.Set.of("qwen3:30b"));
        Path temporaryDirectory = Files.createTempDirectory("ollama-model-uninstall");
        Path selectionFile = temporaryDirectory.resolve("selected-model.txt");
        AtomicInteger pulls = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            String models = installed.get().stream()
                    .map(model -> "{\"name\":\"" + model + "\",\"size\":1000,\"details\":{}}")
                    .collect(java.util.stream.Collectors.joining(","));
            byte[] response = ("{\"models\":[" + models + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.createContext("/api/pull", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"name\":\"qwen3:14b\""));
            pulls.incrementAndGet();
            installed.updateAndGet(models -> {
                java.util.Set<String> updated = new java.util.HashSet<>(models);
                updated.add("qwen3:14b");
                return java.util.Set.copyOf(updated);
            });
            byte[] response = "{\"status\":\"success\"}\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.createContext("/api/delete", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"name\":\"qwen3:14b\"")
                    || request.contains("\"name\":\"qwen3:30b\""));
            String modelToDelete = request.contains("\"name\":\"qwen3:14b\"")
                    ? "qwen3:14b" : "qwen3:30b";
            deletes.incrementAndGet();
            installed.updateAndGet(models -> {
                java.util.Set<String> updated = new java.util.HashSet<>(models);
                updated.remove(modelToDelete);
                return java.util.Set.copyOf(updated);
            });
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            var service = new OllamaGenerationService(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    java.net.http.HttpClient.newHttpClient(),
                    "qwen3:30b",
                    ModelCatalogConfiguration.loadDefault(),
                    selectionFile);

            service.installModel("qwen3:14b");

            assertEquals("qwen3:30b", service.activeModelId());
            assertEquals(1, pulls.get());
            assertEquals("completed", service.downloadProgress("qwen3:14b").status());
            assertEquals(List.of("qwen3:14b", "qwen3:30b"),
                    service.listAvailableModels().stream()
                            .map(com.localai.model.AvailableModel::modelId).toList());

            service.uninstallModel("qwen3:14b");

            assertEquals("qwen3:30b", service.activeModelId());
            assertEquals(1, deletes.get());
            assertEquals(List.of("qwen3:30b"),
                    service.listAvailableModels().stream()
                            .map(com.localai.model.AvailableModel::modelId).toList());

            service.uninstallModel("qwen3:30b");

            assertNull(service.activeModelId());
            assertEquals(com.localai.contracts.ModelSelectionResponse.NO_SELECTION_MARKER + System.lineSeparator(),
                    Files.readString(selectionFile));
            assertTrue(service.listAvailableModels().isEmpty());
        } finally {
            server.stop(0);
            try (var paths = Files.walk(temporaryDirectory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void restoresExplicitNoSelectionInsteadOfFallingBackToDefaultModel() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("ollama-no-model-selected");
        Path selectionFile = temporaryDirectory.resolve("selected-model.txt");
        Files.writeString(selectionFile,
                com.localai.contracts.ModelSelectionResponse.NO_SELECTION_MARKER + System.lineSeparator());
        try {
            var service = new OllamaGenerationService(
                    URI.create("http://127.0.0.1:11434"),
                    java.net.http.HttpClient.newHttpClient(),
                    "qwen3:30b",
                    ModelCatalogConfiguration.loadDefault(),
                    selectionFile);

            assertNull(service.activeModelId());
            assertFalse(service.isReady(service.activeModelId()));
        } finally {
            Files.deleteIfExists(selectionFile);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    @Test
    void sendsImagesOnlyWhenGivenAndDetectsVisionCapability() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> generationRequest = new AtomicReference<>();
        server.createContext("/api/show", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = (body.contains("vision-model")
                    ? "{\"capabilities\":[\"completion\",\"vision\",\"thinking\"]}"
                    : "{\"capabilities\":[\"completion\"]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.createContext("/api/generate", exchange -> {
            generationRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"response\":\"ok\",\"done\":true}\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            var service = new OllamaGenerationService(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertTrue(service.supportsVision("vision-model"));
            assertFalse(service.supportsVision("text-model"));
            service.streamText("vision-model", "describe", List.of("QUJD"), ignored -> { });
            assertTrue(generationRequest.get().contains("\"images\":[\"QUJD\"]"));
            assertTrue(service.supportsThinking("vision-model"));
            assertFalse(service.supportsThinking("text-model"));
            assertTrue(generationRequest.get().contains("\"think\":false"));
            service.streamText("vision-model", "think hard", List.of(), true, ignored -> { });
            assertTrue(generationRequest.get().contains("\"think\":true"));
            service.streamText("text-model", "plain", List.of(), true, ignored -> { });
            assertFalse(generationRequest.get().contains("\"think\""));
        } finally {
            server.stop(0);
        }
    }
}