package com.localai.apihost;

import com.localai.contracts.ConversationCreateRequest;
import com.localai.contracts.TurnRequest;
import com.localai.model.ModelGenerationException;
import com.localai.model.ModelGenerationService;
import com.localai.model.AvailableModel;
import com.localai.model.ModelCatalogProvider;
import com.localai.model.ModelLifecycleService;
import com.localai.model.ModelSelectionService;
import com.localai.agentcore.ContextAssembler;
import com.localai.storage.ConversationStore;
import com.localai.storage.sqlite.SqliteConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConversationControllerTest {

    @Test
    void deletesConversationsAndRejectsDeletionWhileATurnIsActive() throws Exception {
        Path dbPath = Path.of("target/test-data/delete-conversation-api-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        CountDownLatch generationStarted = new CountDownLatch(1);
        CountDownLatch allowGenerationToFinish = new CountDownLatch(1);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                generationStarted.countDown();
                try {
                    if (!allowGenerationToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test generation timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test generation interrupted", e);
                }
                return "finished";
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var activeConversation = controller.createConversation(new ConversationCreateRequest("Active"));

        var turnResponse = controller.streamTurn(
                activeConversation.id(), new TurnRequest("wait for model", UUID.randomUUID().toString()));
        assertEquals(HttpStatus.OK, turnResponse.getStatusCode());
        assertTrue(generationStarted.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.CONFLICT, controller.deleteConversation(activeConversation.id()).getStatusCode());
        allowGenerationToFinish.countDown();

        var completed = store.listTurns(activeConversation.id()).getFirst();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.findTurn(completed.id()).orElseThrow().status()
                != com.localai.contracts.TurnStatus.COMPLETED && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteConversation(activeConversation.id()).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                controller.deleteConversation(activeConversation.id()).getStatusCode());
    }

    @Test
    void importsUserSuppliedWebUrlsAsConversationContext() throws Exception {
        Path dbPath = Path.of("target/test-data/web-context-test-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return prompt;
            }
        };
        var pageServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        pageServer.createContext("/article", exchange -> {
            byte[] page = "<html><head><title>Source</title></head>"
                    .concat("<body><p>Keep the database local.</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, page.length);
            try (var output = exchange.getResponseBody()) {
                output.write(page);
            }
        });
        pageServer.start();

        try {
            WebContextFetcher fetcher = new WebContextFetcher(
                    java.net.http.HttpClient.newHttpClient(),
                    host -> new java.net.InetAddress[] {
                            java.net.InetAddress.getByAddress(new byte[] {8, 8, 8, 8})});
            ConversationController controller = new ConversationController(
                    store,
                    generationService,
                    "test-model",
                    new TurnEventBroker(),
                    new ContextAssembler(),
                    4096,
                    fetcher);
            var conversation = controller.createConversation(new ConversationCreateRequest("Web research"));
            String url = "http://localhost:" + pageServer.getAddress().getPort() + "/article";

            var response = controller.addWebContext(
                    conversation.id(), new com.localai.contracts.WebContextRequest(List.of(url)));

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            var imported = (List<com.localai.contracts.MessageRecord>) response.getBody();
            assertEquals(1, imported.size());
            assertTrue(imported.getFirst().content().contains("Title: Source"));
            assertTrue(imported.getFirst().content().contains("Keep the database local."));
            assertEquals(imported, store.listMessages(conversation.id()));
        } finally {
            pageServer.stop(0);
        }
    }

    @Test
    void modelCatalogEndpointMapsProviderDetailsToPublicContract() throws Exception {
        Path dbPath = Path.of("target/test-data/model-catalog-api-test-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        class CatalogService implements ModelGenerationService, ModelCatalogProvider {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "reply";
            }

            @Override
            public List<AvailableModel> listAvailableModels() {
                return List.of(new AvailableModel(
                        "example:latest",
                        "sha256:abc",
                        1024,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        "llama",
                        List.of("llama"),
                        "8B",
                        "Q4_K_M"));
            }
        }
        ConversationController controller = new ConversationController(store, new CatalogService(), "example");

        var response = controller.listAvailableModels();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(new com.localai.contracts.ModelCatalogEntry(
                "ollama",
                "example:latest",
                "sha256:abc",
                1024,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                "llama",
                List.of("llama"),
                "8B",
                "Q4_K_M")), response.getBody());
    }

    @Test
    void modelLifecycleEndpointsOnlyManageConfiguredModelsAndProtectTheActiveModel() throws Exception {
        Path dbPath = Path.of("target/test-data/model-lifecycle-api-test-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        class LifecycleService implements ModelGenerationService, ModelSelectionService, ModelLifecycleService {
            private final List<com.localai.contracts.ModelOption> options = List.of(
                    new com.localai.contracts.ModelOption("qwen3:14b", 1, 1, 1),
                    new com.localai.contracts.ModelOption("qwen3:30b", 1, 1, 1));
            private String activeModelId = "qwen3:30b";
            private String installedModelId;
            private String uninstalledModelId;

            @Override
            public boolean isReady(String modelId) {
                return modelId != null && modelId.equals(activeModelId);
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return prompt;
            }

            @Override
            public String activeModelId() {
                return activeModelId;
            }

            @Override
            public List<com.localai.contracts.ModelOption> modelOptions() {
                return options;
            }

            @Override
            public void selectModel(String modelId) {
                activeModelId = modelId;
            }

            @Override
            public void installModel(String modelId) {
                installedModelId = modelId;
            }

            @Override
            public void uninstallModel(String modelId) {
                if (modelId.equals(activeModelId)) {
                    activeModelId = null;
                }
                uninstalledModelId = modelId;
            }

            @Override
            public com.localai.contracts.ModelDownloadProgress downloadProgress(String modelId) {
                return new com.localai.contracts.ModelDownloadProgress(
                        modelId, "downloading", 50, 100, "Downloading layer");
            }
        }
        LifecycleService service = new LifecycleService();
        ConversationController controller = new ConversationController(store, service, "qwen3:30b");

        var installed = controller.installModel(new com.localai.contracts.ModelSelectionRequest("qwen3:14b"));
        assertEquals(HttpStatus.OK, installed.getStatusCode());
        assertEquals("qwen3:14b", service.installedModelId);

        var uninstalled = controller.uninstallModel(new com.localai.contracts.ModelSelectionRequest("qwen3:14b"));
        assertEquals(HttpStatus.OK, uninstalled.getStatusCode());
        assertEquals("qwen3:14b", service.uninstalledModelId);

        assertEquals(HttpStatus.OK, controller.uninstallModel(
                new com.localai.contracts.ModelSelectionRequest("qwen3:30b")).getStatusCode());
        assertEquals("qwen3:30b", service.uninstalledModelId);
        assertNull(service.activeModelId());
        assertEquals(new com.localai.contracts.ModelSelectionResponse(null, false),
                controller.getModelSelection());
        var conversation = controller.createConversation(new ConversationCreateRequest("No model"));
        var noModelTurn = controller.streamTurn(conversation.id(),
                new TurnRequest("prompt", UUID.randomUUID().toString()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, noModelTurn.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.installModel(
                new com.localai.contracts.ModelSelectionRequest("qwen3:235b")).getStatusCode());
        var progress = controller.modelDownloadProgress("qwen3:14b");
        assertEquals(HttpStatus.OK, progress.getStatusCode());
        assertEquals(50L, ((com.localai.contracts.ModelDownloadProgress) progress.getBody()).completedBytes());
    }

    @Test
    void createConversationAndSubmitTurn() throws Exception {
        Path dbPath = Path.of("target/test-data/loopback-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);

        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "Generated: " + prompt;
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Loopback chat"));
        String requestId = UUID.randomUUID().toString();
        var request = new TurnRequest("hello from test", requestId);
        var response = controller.submitTurn(conversation.id(), request);
        var duplicateResponse = controller.submitTurn(conversation.id(), request);
        var conflictingResponse = controller.submitTurn(
                conversation.id(), new TurnRequest("different content", requestId));

        assertNotNull(response);
        assertEquals(HttpStatus.CONFLICT, duplicateResponse.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, conflictingResponse.getStatusCode());
        assertTrue(((com.localai.contracts.TurnResponse) response.getBody()).assistantReply()
                .contains("User: hello from test"));
        assertEquals(2, store.listMessages(conversation.id()).size());
        var turn = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), requestId, "hello from test");
        var manifest = store.findContextManifest(turn.turn().id()).orElseThrow();
        assertEquals("test-model", manifest.modelId());
        assertEquals(1, manifest.selectedMessageIds().size());
        var otherConversation = controller.createConversation(new ConversationCreateRequest("Other chat"));
        store.appendMessage(otherConversation.id(), com.localai.contracts.MessageRole.USER, "private other chat");
        var nextResponse = controller.submitTurn(
                conversation.id(), new TurnRequest("follow-up question", UUID.randomUUID().toString()));
        String assembledPrompt = ((com.localai.contracts.TurnResponse) nextResponse.getBody()).assistantReply();
        assertTrue(assembledPrompt.contains("User: hello from test"));
        assertFalse(assembledPrompt.contains("private other chat"));
    }

    @Test
    void oversizedCurrentMessageFailsBeforeModelGeneration() throws Exception {
        Path dbPath = Path.of("target/test-data/context-budget-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                throw new AssertionError("Model generation must not start for an over-budget message");
            }
        };
        ConversationController controller = new ConversationController(
                store, generationService, "test-model", new TurnEventBroker(), 8);
        var conversation = controller.createConversation(new ConversationCreateRequest("Budget test"));

        var response = controller.submitTurn(
                conversation.id(), new TurnRequest("this message cannot fit", UUID.randomUUID().toString()));

        assertEquals(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(1, store.listMessages(conversation.id()).size());
    }

    @Test
    void sourcedSummaryCanBeManagedAndUsedWithoutCrossConversationLeakage() throws Exception {
        Path dbPath = Path.of("target/test-data/context-summary-api-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return prompt.startsWith("Summarize the supplied conversation")
                        ? "Project decision: keep data local."
                        : prompt;
            }
        };
        ConversationController controller = new ConversationController(
                store, generationService, "test-model", new TurnEventBroker(), 160);
        var conversation = controller.createConversation(new ConversationCreateRequest("Summary source"));
        var otherConversation = controller.createConversation(new ConversationCreateRequest("Other source"));
        var sourceOne = store.appendMessage(
                conversation.id(), com.localai.contracts.MessageRole.USER, "earlier request " + "x".repeat(80));
        var sourceTwo = store.appendMessage(
                conversation.id(), com.localai.contracts.MessageRole.ASSISTANT, "earlier response " + "y".repeat(80));
        var foreignMessage = store.appendMessage(
                otherConversation.id(), com.localai.contracts.MessageRole.USER, "private conversation");

        var created = controller.createContextSummary(conversation.id(),
                new com.localai.contracts.ContextSummaryRequest(List.of(sourceOne.id(), sourceTwo.id())));

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        var savedSummary = (com.localai.contracts.ContextSummary) created.getBody();
        assertNotNull(savedSummary);
        assertEquals(List.of(sourceOne.id(), sourceTwo.id()), savedSummary.sourceMessageIds());
        assertEquals(HttpStatus.BAD_REQUEST, controller.createContextSummary(conversation.id(),
                new com.localai.contracts.ContextSummaryRequest(List.of(sourceOne.id(), foreignMessage.id())))
                .getStatusCode());

        var response = controller.submitTurn(conversation.id(),
                new TurnRequest("now", UUID.randomUUID().toString()));
        String assembledPrompt = ((com.localai.contracts.TurnResponse) response.getBody()).assistantReply();
        assertTrue(assembledPrompt.contains("Project decision: keep data local."));
        assertTrue(assembledPrompt.contains("[Untrusted conversation summary"));
        assertTrue(!assembledPrompt.contains("private conversation"));
        var turnStatuses = controller.listConversationTurns(conversation.id());
        assertEquals(HttpStatus.OK, turnStatuses.getStatusCode());
        assertEquals(1, turnStatuses.getBody().size());
        assertEquals(com.localai.contracts.TurnStatus.COMPLETED, turnStatuses.getBody().getFirst().status());

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteContextSummary(
                conversation.id(), savedSummary.id()).getStatusCode());
        assertTrue(store.listContextSummaries(conversation.id()).isEmpty());
    }

    @Test
    void oversizedGeneratedSummaryIsRejectedWithoutPersistence() throws Exception {
        Path dbPath = Path.of("target/test-data/oversized-summary-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "summary".repeat(2_000);
            }
        };
        ConversationController controller = new ConversationController(
                store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Output bound"));
        var first = store.appendMessage(conversation.id(), com.localai.contracts.MessageRole.USER, "first");
        var second = store.appendMessage(conversation.id(), com.localai.contracts.MessageRole.ASSISTANT, "second");

        var response = controller.createContextSummary(conversation.id(),
                new com.localai.contracts.ContextSummaryRequest(List.of(first.id(), second.id())));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertTrue(store.listContextSummaries(conversation.id()).isEmpty());
    }

    @Test
    void conversationTurnStatusResponseDoesNotExposeIdempotencyDetails() throws Exception {
        Path dbPath = Path.of("target/test-data/safe-turn-status-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "done";
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Safe status"));
        controller.submitTurn(conversation.id(),
                new TurnRequest("safe", "private-client-request-key"));

        var response = controller.listConversationTurns(conversation.id());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals(com.localai.contracts.TurnStatus.COMPLETED, response.getBody().getFirst().status());
        assertFalse(response.getBody().getFirst().toString().contains("private-client-request-key"));
        assertFalse(response.getBody().getFirst().toString().contains("requestHash"));
    }

    @Test
    void reviewRequiredTurnExposesSafeInvocationSummaryWithoutIdempotencyKey() throws Exception {
        Path dbPath = Path.of("target/test-data/tool-review-status-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore firstStore = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "unused";
            }
        };
        var conversation = firstStore.createConversation(new ConversationCreateRequest("Review status"));
        var turn = firstStore.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "write file");
        var timestamp = java.time.Instant.now();
        var intent = firstStore.recordToolIntent(new com.localai.contracts.ToolInvocationRecord(
                UUID.randomUUID(),
                turn.turn().id(),
                "private-idempotency-key",
                "workspace.write",
                List.of("workspace.write:/approved/project"),
                true,
                com.localai.contracts.ToolInvocationStatus.INTENT_RECORDED,
                timestamp,
                timestamp));
        firstStore.authorizeToolInvocation(intent.id(), true);
        firstStore.beginToolInvocation(intent.id());

        ConversationStore recoveredStore = new SqliteConversationStore(dbPath);
        ConversationController controller = new ConversationController(
                recoveredStore, generationService, "test-model");
        var response = controller.listToolInvocationStatuses(turn.turn().id());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        var status = response.getBody().getFirst();
        assertEquals("workspace.write", status.operation());
        assertEquals(List.of("workspace.write:/approved/project"), status.requiredPermissions());
        assertEquals(com.localai.contracts.ToolInvocationStatus.NEEDS_REVIEW, status.status());
        assertFalse(status.toString().contains("private-idempotency-key"));
    }

    @Test
    void readinessReportsUnavailableModel() throws Exception {
        Path dbPath = Path.of("target/test-data/readiness-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return false;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                throw new ModelGenerationException("runtime unavailable");
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");

        var response = controller.ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOWN", response.getBody().status());
        assertEquals("model_unavailable", response.getBody().details());
    }

    @Test
    void messageSearchIsConversationScopedAndRejectsInvalidLimits() throws Exception {
        Path dbPath = Path.of("target/test-data/message-search-test-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return prompt;
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var visible = controller.createConversation(new ConversationCreateRequest("Visible"));
        var isolated = controller.createConversation(new ConversationCreateRequest("Isolated"));
        store.appendMessage(visible.id(), com.localai.contracts.MessageRole.USER, "shared search phrase");
        store.appendMessage(isolated.id(), com.localai.contracts.MessageRole.USER, "shared search phrase");

        var results = controller.searchMessages(visible.id(), "search phrase", 20);
        var invalidLimit = controller.searchMessages(visible.id(), "search", 101);
        var missingConversation = controller.searchMessages(UUID.randomUUID(), "search", 20);

        assertEquals(200, results.getStatusCode().value());
        assertEquals(1, ((List<?>) results.getBody()).size());
        assertEquals(400, invalidLimit.getStatusCode().value());
        assertEquals(404, missingConversation.getStatusCode().value());
    }

    @Test
    void modelFailureReturnsServiceUnavailableWithoutPersistingPartialTurn() throws Exception {
        Path dbPath = Path.of("target/test-data/generation-failure-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return false;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                throw new ModelGenerationException("runtime unavailable");
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Unavailable model"));
        String requestId = UUID.randomUUID().toString();

        var response = controller.submitTurn(conversation.id(), new TurnRequest("hello", requestId));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(ProblemDetail.class, response.getBody().getClass());
        assertEquals(1, store.listMessages(conversation.id()).size());
        var persistedTurn = store.reserveTurn(UUID.randomUUID(), conversation.id(), requestId, "hello").turn();
        assertEquals(com.localai.contracts.TurnStatus.FAILED, persistedTurn.status());
    }

    @Test
    void streamedTurnEmitsVersionedEventsAndPersistsCompletedMessages() throws Exception {
        Path dbPath = Path.of("target/test-data/streamed-turn-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "hello world";
            }

            @Override
            public void streamText(String modelId, String prompt, java.util.function.Consumer<String> tokenConsumer) {
                tokenConsumer.accept("hello ");
                tokenConsumer.accept("world");
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Streamed chat"));

        var response = controller.streamTurn(conversation.id(), new TurnRequest("prompt", UUID.randomUUID().toString()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(output);
        String events = output.toString(StandardCharsets.UTF_8);

        assertTrue(events.contains("event: turn.started"));
        assertTrue(events.contains("event: token.delta"));
        assertTrue(events.contains("\"text\":\"hello \""));
        assertTrue(events.contains("event: turn.completed"));
        List<com.localai.contracts.MessageRecord> messages = store.listMessages(conversation.id());
        assertEquals(2, messages.size());
        assertEquals("prompt", messages.get(0).content());
        assertEquals("hello world", messages.get(1).content());
    }

    @Test
    void duplicateStreamSubmissionReplaysExistingTurnWithoutRegenerating() throws Exception {
        Path dbPath = Path.of("target/test-data/idempotent-stream-test-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        AtomicInteger generationCount = new AtomicInteger();
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "reply";
            }

            @Override
            public void streamText(String modelId, String prompt, java.util.function.Consumer<String> tokenConsumer) {
                generationCount.incrementAndGet();
                tokenConsumer.accept("reply");
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Idempotent stream"));
        var request = new TurnRequest("prompt", UUID.randomUUID().toString());

        var firstResponse = controller.streamTurn(conversation.id(), request);
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        ((StreamingResponseBody) firstResponse.getBody()).writeTo(firstOutput);
        var duplicateResponse = controller.streamTurn(conversation.id(), request);
        ByteArrayOutputStream duplicateOutput = new ByteArrayOutputStream();
        ((StreamingResponseBody) duplicateResponse.getBody()).writeTo(duplicateOutput);

        assertEquals(HttpStatus.OK, duplicateResponse.getStatusCode());
        assertTrue(duplicateOutput.toString(StandardCharsets.UTF_8).contains("event: turn.completed"));
        assertEquals(1, generationCount.get());
        assertEquals(2, store.listMessages(conversation.id()).size());
    }

    @Test
    void turnStatusEndpointReturnsDurableStateAndNotFoundForUnknownTurn() throws Exception {
        Path dbPath = Path.of("target/test-data/turn-status-test-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "reply";
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Status lookup"));
        var reservation = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "another prompt");
        var statusResponse = controller.getTurnStatus(reservation.turn().id());
        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        assertEquals(com.localai.contracts.TurnStatus.RUNNING, statusResponse.getBody().status());
        assertEquals(conversation.id(), statusResponse.getBody().conversationId());
        assertEquals(HttpStatus.NOT_FOUND, controller.getTurnStatus(UUID.randomUUID()).getStatusCode());
    }

    @Test
    void durableLifecycleEventsReplayAfterApiRestart() throws Exception {
        Path dbPath = Path.of("target/test-data/durable-replay-test-" + UUID.randomUUID() + ".db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore firstStore = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "reply";
            }

            @Override
            public void streamText(String modelId, String prompt, java.util.function.Consumer<String> tokenConsumer) {
                tokenConsumer.accept("reply");
            }
        };
        ConversationController firstController = new ConversationController(
                firstStore, generationService, "test-model");
        var conversation = firstController.createConversation(new ConversationCreateRequest("Durable replay"));
        var response = firstController.streamTurn(
                conversation.id(), new TurnRequest("prompt", UUID.randomUUID().toString()));
        ByteArrayOutputStream initialOutput = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(initialOutput);
        String firstEventId = initialOutput.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("id: "))
                .findFirst()
                .orElseThrow()
                .substring(4);
        UUID turnId = UUID.fromString(firstEventId.substring(0, firstEventId.lastIndexOf(':')));

        ConversationStore restartedStore = new SqliteConversationStore(dbPath);
        ConversationController restartedController = new ConversationController(
                restartedStore, generationService, "test-model");
        var replayResponse = restartedController.replayTurnEvents(turnId, firstEventId);
        ByteArrayOutputStream replayOutput = new ByteArrayOutputStream();
        ((StreamingResponseBody) replayResponse.getBody()).writeTo(replayOutput);
        String replayed = replayOutput.toString(StandardCharsets.UTF_8);

        assertEquals(HttpStatus.OK, replayResponse.getStatusCode());
        assertTrue(replayed.contains("event: turn.completed"));
        assertTrue(replayed.contains("\"assistantMessageId\""));
        assertTrue(!replayed.contains("event: token.delta"));
    }

    @Test
    void replayEndpointReturnsEventsAfterLastEventId() throws Exception {
        Path dbPath = Path.of("target/test-data/replay-turn-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                return "answer";
            }

            @Override
            public void streamText(String modelId, String prompt, java.util.function.Consumer<String> tokenConsumer) {
                tokenConsumer.accept("answer");
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Replay turn"));
        var initialResponse = controller.streamTurn(
                conversation.id(), new TurnRequest("prompt", UUID.randomUUID().toString()));
        ByteArrayOutputStream initialOutput = new ByteArrayOutputStream();
        ((StreamingResponseBody) initialResponse.getBody()).writeTo(initialOutput);
        String initialEvents = initialOutput.toString(StandardCharsets.UTF_8);
        String firstEventId = initialEvents.lines()
                .filter(line -> line.startsWith("id: "))
                .findFirst()
                .orElseThrow()
                .substring(4);
        UUID turnId = UUID.fromString(firstEventId.substring(0, firstEventId.lastIndexOf(':')));

        var replayResponse = controller.replayTurnEvents(turnId, firstEventId);
        ByteArrayOutputStream replayOutput = new ByteArrayOutputStream();
        ((StreamingResponseBody) replayResponse.getBody()).writeTo(replayOutput);
        String replayedEvents = replayOutput.toString(StandardCharsets.UTF_8);

        assertEquals(HttpStatus.OK, replayResponse.getStatusCode());
        assertTrue(!replayedEvents.contains("event: turn.started"));
        assertTrue(replayedEvents.contains("event: token.delta"));
        assertTrue(replayedEvents.contains("event: turn.completed"));
    }

    @Test
    void failedStreamEmitsFailureEventWithoutPersistingPartialTurn() throws Exception {
        Path dbPath = Path.of("target/test-data/streamed-failure-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return false;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                throw new ModelGenerationException("runtime unavailable");
            }

            @Override
            public void streamText(String modelId, String prompt, java.util.function.Consumer<String> tokenConsumer) {
                tokenConsumer.accept("partial");
                throw new ModelGenerationException("runtime failed");
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Failed stream"));

        var response = controller.streamTurn(conversation.id(), new TurnRequest("prompt", UUID.randomUUID().toString()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(output);
        String events = output.toString(StandardCharsets.UTF_8);

        assertTrue(events.contains("event: token.delta"));
        assertTrue(events.contains("event: turn.failed"));
        assertTrue(events.contains("runtime failed"));
        assertEquals(1, store.listMessages(conversation.id()).size());
        UUID turnId = UUID.fromString(response.getHeaders().getFirst("X-Turn-ID"));
        assertEquals("partial",
                controller.getTurnCheckpoint(turnId).getBody().partialAssistantText());
    }

    @Test
    void cancellationInterruptsActiveStreamAndDoesNotPersistPartialTurn() throws Exception {
        Path dbPath = Path.of("target/test-data/cancelled-turn-test.db");
        Files.createDirectories(dbPath.getParent());
        ConversationStore store = new SqliteConversationStore(dbPath);
        CountDownLatch generationStarted = new CountDownLatch(1);
        ModelGenerationService generationService = new ModelGenerationService() {
            @Override
            public boolean isReady(String modelId) {
                return true;
            }

            @Override
            public String generateText(String modelId, String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void streamText(String modelId, String prompt, java.util.function.Consumer<String> tokenConsumer) {
                tokenConsumer.accept("partial");
                generationStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ModelGenerationException("interrupted", e);
                }
            }
        };
        ConversationController controller = new ConversationController(store, generationService, "test-model");
        var conversation = controller.createConversation(new ConversationCreateRequest("Cancelled turn"));
        var response = controller.streamTurn(
                conversation.id(), new TurnRequest("prompt", UUID.randomUUID().toString()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        Thread streamThread = new Thread(() -> {
            try {
                ((StreamingResponseBody) response.getBody()).writeTo(output);
            } catch (Throwable e) {
                streamFailure.set(e);
            }
        });
        streamThread.start();

        assertTrue(generationStarted.await(2, TimeUnit.SECONDS));
        long eventDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!output.toString(StandardCharsets.UTF_8).contains("event: turn.started")
                && System.nanoTime() < eventDeadline) {
            Thread.sleep(5);
        }
        String partialEvents = output.toString(StandardCharsets.UTF_8);
        String turnIdText = partialEvents.lines()
                .filter(line -> line.startsWith("id: "))
                .findFirst()
                .orElseThrow()
                .substring(4);
        UUID turnId = UUID.fromString(turnIdText.substring(0, turnIdText.lastIndexOf(':')));
        var cancelResponse = controller.cancelTurn(turnId);
        streamThread.join(2_000);

        assertEquals(HttpStatus.ACCEPTED, cancelResponse.getStatusCode());
        assertTrue(!streamThread.isAlive(), "stream worker should stop after cancellation");
        assertEquals(null, streamFailure.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: turn.cancelled"));
        assertEquals(1, store.listMessages(conversation.id()).size());
        assertEquals(com.localai.contracts.TurnStatus.CANCELLED,
                store.findTurn(turnId).orElseThrow().status());
        assertEquals("partial",
                store.findTurnCheckpoint(turnId).orElseThrow().partialAssistantText());
    }
}
