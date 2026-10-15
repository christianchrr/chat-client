package com.localai.desktop;

import com.sun.net.httpserver.HttpServer;
import com.localai.contracts.StreamEvent;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalApiClientTest {

    @Test
    void deletesConversationUsingAuthenticatedDeleteRequest() throws Exception {
        UUID conversationId = UUID.randomUUID();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations/" + conversationId, exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "delete-token");

            client.deleteConversation(conversationId);

            assertEquals("DELETE", requestMethod.get());
            assertEquals("delete-token", authHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createsConversationAndSubmitsAuthenticatedTurn() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            byte[] response;
            if ("POST".equals(exchange.getRequestMethod())) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                response = ("{\"id\":\"" + conversationId
                        + "\",\"title\":\"Test\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
            } else {
                response = ("[{\"id\":\"" + conversationId
                        + "\",\"title\":\"Test\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}]").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/api/v1/conversations/" + conversationId + "/messages", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/api/v1/conversations/" + conversationId + "/turns", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            byte[] response = ("{\"conversationId\":\"" + conversationId + "\",\"userMessageId\":\""
                    + userMessageId + "\",\"assistantMessageId\":\"" + assistantMessageId
                    + "\",\"assistantReply\":\"hello back\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-token");

            var conversation = client.createConversation("Test");
            assertEquals(conversationId, client.listConversations().get(0).id());
            assertEquals(0, client.listMessages(conversationId).size());
            var turn = client.submitTurn(conversation.id(), "hello");

            assertEquals(conversationId, conversation.id());
            assertEquals("test-token", authHeader.get());
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"title\":\"Test\""));
            assertEquals(conversationId, turn.conversationId());
            assertEquals("hello back", turn.assistantReply());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonLoopbackApiHosts() {
        assertThrows(IllegalArgumentException.class,
                () -> new LocalApiClient(URI.create("http://example.com"), "test-token"));
    }

    @Test
    void includesSafeRequestPathAndApiErrorBodyWhenRequestFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations", exchange -> {
            byte[] response = "{\"error\":\"conversation_store_unavailable\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "error-token");

            LocalApiClient.LocalApiException error = assertThrows(
                    LocalApiClient.LocalApiException.class, client::listConversations);

            org.junit.jupiter.api.Assertions.assertTrue(
                    error.getMessage().contains("GET /api/v1/conversations returned HTTP 500"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    error.getMessage().contains("conversation_store_unavailable"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listsConversationsAndLoadsMessagesForSelectedConversation() throws Exception {
        UUID firstConversationId = UUID.randomUUID();
        UUID selectedConversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> requestedMessagesPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            byte[] response = ("[{\"id\":\"" + firstConversationId
                    + "\",\"title\":\"First\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                    + "\"updatedAt\":\"2026-01-01T00:00:00Z\"},{\"id\":\"" + selectedConversationId
                    + "\",\"title\":\"Selected\",\"createdAt\":\"2026-01-02T00:00:00Z\","
                    + "\"updatedAt\":\"2026-01-02T00:00:00Z\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/api/v1/conversations/" + selectedConversationId + "/messages", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            requestedMessagesPath.set(exchange.getRequestURI().getPath());
            byte[] response = ("[{\"id\":\"" + messageId + "\",\"conversationId\":\""
                    + selectedConversationId
                    + "\",\"role\":\"USER\",\"content\":\"saved text\","
                    + "\"createdAt\":\"2026-01-02T00:00:00Z\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "selection-token");

            var conversations = client.listConversations();
            var messages = client.listMessages(conversations.get(1).id());

            assertEquals(2, conversations.size());
            assertEquals("Selected", conversations.get(1).title());
            assertEquals("selection-token", authHeader.get());
            assertEquals("/api/v1/conversations/" + selectedConversationId + "/messages",
                    requestedMessagesPath.get());
            assertEquals(messageId, messages.get(0).id());
            assertEquals("saved text", messages.get(0).content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managesAuthenticatedSourcedConversationSummaries() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageOne = UUID.randomUUID();
        UUID messageTwo = UUID.randomUUID();
        UUID summaryId = UUID.randomUUID();
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations/" + conversationId + "/summaries", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            byte[] response;
            if ("POST".equals(exchange.getRequestMethod())) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                response = summaryJson(conversationId, summaryId, messageOne, messageTwo)
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, response.length);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                response = ("[" + summaryJson(conversationId, summaryId, messageOne, messageTwo) + "]")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
            } else {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "summary-token");
            var created = client.createContextSummary(conversationId, java.util.List.of(messageOne, messageTwo));
            var listed = client.listContextSummaries(conversationId);
            client.deleteContextSummary(conversationId, summaryId);

            assertEquals(summaryId, created.id());
            assertEquals(1, listed.size());
            assertEquals(java.util.List.of(messageOne, messageTwo), listed.getFirst().sourceMessageIds());
            assertEquals("summary-token", authHeader.get());
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains(messageOne.toString()));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains(messageTwo.toString()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsSafeToolInvocationRecoveryStatus() throws Exception {
        UUID turnId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        AtomicReference<String> authHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/turns/" + turnId + "/tool-invocations", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            byte[] response = ("[{\"invocationId\":\"" + invocationId
                    + "\",\"operation\":\"workspace.write\",\"requiredPermissions\":["
                    + "\"workspace.write:/approved/project\"],\"sideEffecting\":true,"
                    + "\"status\":\"NEEDS_REVIEW\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                    + "\"updatedAt\":\"2026-01-01T00:00:01Z\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "review-token");
            var statuses = client.listToolInvocations(turnId);

            assertEquals("review-token", authHeader.get());
            assertEquals(1, statuses.size());
            assertEquals(invocationId, statuses.getFirst().invocationId());
            assertEquals("workspace.write", statuses.getFirst().operation());
            assertEquals("NEEDS_REVIEW", statuses.getFirst().status().name());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsAuthenticatedInstalledModelCatalog() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            byte[] response = """
                    [{"providerId":"ollama","modelId":"llama:latest","digest":"sha256:model",
                      "sizeBytes":2048,"modifiedAt":"2026-01-01T00:00:00Z","family":"llama",
                      "families":["llama"],"parameterSize":"8B","quantizationLevel":"Q4_K_M"}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "catalog-token");
            var models = client.listAvailableModels();

            assertEquals("catalog-token", authHeader.get());
            assertEquals(1, models.size());
            assertEquals("llama:latest", models.getFirst().modelId());
            assertEquals(2048, models.getFirst().sizeBytes());
            assertEquals("Q4_K_M", models.getFirst().quantizationLevel());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsAuthenticatedSseEventsToConsumer() throws Exception {
        UUID turnId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AtomicReference<String> authHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations/" + conversationId + "/turns/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                String data = """
                        {"eventId":"%s:0","turnId":"%s","timestamp":"%s","type":"turn.started","sequence":0,"payload":{},"schemaVersion":1}
                        """.formatted(turnId, turnId, Instant.now()).trim();
                output.write(("id: " + turnId + ":0\nevent: turn.started\ndata: " + data + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                data = """
                        {"eventId":"%s:1","turnId":"%s","timestamp":"%s","type":"token.delta","sequence":1,"payload":{"text":"hello"},"schemaVersion":1}
                        """.formatted(turnId, turnId, Instant.now()).trim();
                output.write(("id: " + turnId + ":1\nevent: token.delta\ndata: " + data + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                data = """
                        {"eventId":"%s:2","turnId":"%s","timestamp":"%s","type":"turn.completed","sequence":2,"payload":{},"schemaVersion":1}
                        """.formatted(turnId, turnId, Instant.now()).trim();
                output.write(("id: " + turnId + ":2\nevent: turn.completed\ndata: " + data + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "stream-token");
            var events = new java.util.ArrayList<StreamEvent>();
            client.streamTurn(conversationId, "prompt", events::add);

            assertEquals("stream-token", authHeader.get());
            assertEquals(3, events.size());
            assertEquals("turn.started", events.get(0).type());
            assertEquals("hello", events.get(1).payload().get("text"));
            assertEquals("turn.completed", events.get(2).type());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsNeedsReviewAsTerminalWithoutReplayingOrRetrying() throws Exception {
        UUID turnId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AtomicInteger streamRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations/" + conversationId + "/turns/stream", exchange -> {
            streamRequests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("X-Turn-ID", turnId.toString());
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                String event = """
                        {"eventId":"%s:1","turnId":"%s","timestamp":"%s","type":"turn.needs_review","sequence":1,"payload":{},"schemaVersion":1}
                        """.formatted(turnId, turnId, Instant.now()).trim();
                output.write(("id: " + turnId + ":1\nevent: turn.needs_review\ndata: " + event + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "review-token");
            var events = new java.util.ArrayList<StreamEvent>();
            client.streamTurn(conversationId, "prompt", events::add);

            assertEquals(1, streamRequests.get());
            assertEquals(1, events.size());
            assertEquals("turn.needs_review", events.getFirst().type());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reconnectsAfterDisconnectUsingLastEventId() throws Exception {
        UUID turnId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AtomicReference<String> replayCursor = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/conversations/" + conversationId + "/turns/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                String started = """
                        {"eventId":"%s:0","turnId":"%s","timestamp":"%s","type":"turn.started","sequence":0,"payload":{},"schemaVersion":1}
                        """.formatted(turnId, turnId, Instant.now()).trim();
                output.write(("id: " + turnId + ":0\nevent: turn.started\ndata: " + started + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/api/v1/turns/" + turnId + "/events", exchange -> {
            replayCursor.set(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
            authHeader.set(exchange.getRequestHeaders().getFirst("X-Local-Auth"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                String delta = """
                        {"eventId":"%s:1","turnId":"%s","timestamp":"%s","type":"token.delta","sequence":1,"payload":{"text":"continued"},"schemaVersion":1}
                        """.formatted(turnId, turnId, Instant.now()).trim();
                output.write(("id: " + turnId + ":1\nevent: token.delta\ndata: " + delta + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                String completed = """
                        {"eventId":"%s:2","turnId":"%s","timestamp":"%s","type":"turn.completed","sequence":2,"payload":{},"schemaVersion":1}
                        """.formatted(turnId, turnId, Instant.now()).trim();
                output.write(("id: " + turnId + ":2\nevent: turn.completed\ndata: " + completed + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        try {
            LocalApiClient client = new LocalApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "reconnect-token");
            var events = new java.util.ArrayList<StreamEvent>();
            client.streamTurn(conversationId, "prompt", events::add);

            assertEquals("reconnect-token", authHeader.get());
            assertEquals(turnId + ":0", replayCursor.get());
            assertEquals(3, events.size());
            assertEquals("turn.started", events.get(0).type());
            assertEquals("continued", events.get(1).payload().get("text"));
            assertEquals("turn.completed", events.get(2).type());
        } finally {
            server.stop(0);
        }
    }

    private String summaryJson(UUID conversationId, UUID summaryId, UUID messageOne, UUID messageTwo) {
        return "{\"id\":\"" + summaryId + "\",\"conversationId\":\"" + conversationId
                + "\",\"content\":\"saved summary\",\"sourceMessageIds\":[\""
                + messageOne + "\",\"" + messageTwo
                + "\"],\"modelId\":\"test-model\",\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    }
}
