package com.localai.storage.sqlite;

import com.localai.contracts.ConversationCreateRequest;
import com.localai.contracts.ConversationRecord;
import com.localai.contracts.MessageRecord;
import com.localai.contracts.MessageRole;
import com.localai.contracts.StreamEvent;
import com.localai.contracts.ContextSummary;
import com.localai.contracts.ToolInvocationRecord;
import com.localai.contracts.ToolInvocationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteConversationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void conversationAndMessagesPersistAcrossStoreReload() {
        Path databasePath = tempDir.resolve("assistant.db");

        SqliteConversationStore firstStore = new SqliteConversationStore(databasePath);
        ConversationRecord conversation = firstStore.createConversation(new ConversationCreateRequest("Test chat"));

        firstStore.appendMessage(conversation.id(), MessageRole.USER, "Hello");
        firstStore.appendMessage(conversation.id(), MessageRole.ASSISTANT, "Hi there");

        List<MessageRecord> savedMessages = firstStore.listMessages(conversation.id());
        assertEquals(2, savedMessages.size());
        assertEquals("Hello", savedMessages.get(0).content());
        assertEquals("Hi there", savedMessages.get(1).content());

        SqliteConversationStore reopenedStore = new SqliteConversationStore(databasePath);
        ConversationRecord reloadedConversation = reopenedStore.findConversation(conversation.id()).orElseThrow();
        List<MessageRecord> reloadedMessages = reopenedStore.listMessages(conversation.id());

        assertEquals(conversation.id(), reloadedConversation.id());
        assertEquals("Test chat", reloadedConversation.title());
        assertEquals(2, reloadedMessages.size());
        assertTrue(reloadedMessages.stream().anyMatch(m -> m.role() == MessageRole.USER && m.content().equals("Hello")));
        assertTrue(reloadedMessages.stream().anyMatch(m -> m.role() == MessageRole.ASSISTANT && m.content().equals("Hi there")));
        assertNotNull(reloadedMessages.get(0).createdAt());
    }

    @Test
    void indexesExistingAndNewMessagesAndScopesSearchToConversation() {
        Path databasePath = tempDir.resolve("search.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord target = store.createConversation(new ConversationCreateRequest("Target"));
        ConversationRecord other = store.createConversation(new ConversationCreateRequest("Other"));
        store.appendMessage(target.id(), MessageRole.USER, "A rare phrase lives here");
        store.appendMessage(other.id(), MessageRole.USER, "A rare phrase must remain private");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER messages_search_after_insert");
            statement.execute("DROP TRIGGER messages_search_after_delete");
            statement.execute("DROP TRIGGER messages_search_after_update");
            statement.execute("DROP TABLE message_search");
            statement.execute("DELETE FROM schema_migrations WHERE version = 'V2__message_search'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to prepare pre-search migration fixture", e);
        }

        SqliteConversationStore reopenedStore = new SqliteConversationStore(databasePath);
        reopenedStore.appendMessage(target.id(), MessageRole.ASSISTANT, "The rare phrase appears again");
        List<MessageRecord> results = reopenedStore.searchMessages(target.id(), "rare phrase", 10);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(message -> message.conversationId().equals(target.id())));
        assertThrows(IllegalArgumentException.class, () -> reopenedStore.searchMessages(target.id(), " ", 10));
        assertThrows(IllegalArgumentException.class, () -> reopenedStore.searchMessages(target.id(), "phrase", 101));
    }

    @Test
    void reservesTurnAtomicallyAndPreventsDuplicateRequestAfterRestart() {
        Path databasePath = tempDir.resolve("turns.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Idempotent"));
        String requestId = UUID.randomUUID().toString();

        var original = store.reserveTurn(UUID.randomUUID(), conversation.id(), requestId, "persist this once");
        SqliteConversationStore reopenedStore = new SqliteConversationStore(databasePath);
        var duplicate = reopenedStore.reserveTurn(UUID.randomUUID(), conversation.id(), requestId, "persist this once");

        assertTrue(original.created());
        assertEquals(original.turn().id(), duplicate.turn().id());
        assertTrue(!duplicate.created());
        assertEquals(1, reopenedStore.listMessages(conversation.id()).size());
        assertEquals(1, reopenedStore.listTurns(conversation.id()).size());
        assertEquals("Idempotent", reopenedStore.findConversation(conversation.id()).orElseThrow().title());
        assertEquals(com.localai.contracts.TurnStatus.INTERRUPTED, duplicate.turn().status());
        assertThrows(com.localai.storage.IdempotencyConflictException.class,
                () -> reopenedStore.reserveTurn(UUID.randomUUID(), conversation.id(), requestId, "different request"));
    }

    @Test
    void derivesShortTitlesFromTheFirstUserMessageAndPreservesCustomTitles() {
        SqliteConversationStore store = new SqliteConversationStore(tempDir.resolve("conversation-titles.db"));
        ConversationRecord automatic = store.createConversation(new ConversationCreateRequest("Empty conversation"));
        ConversationRecord custom = store.createConversation(new ConversationCreateRequest("Project notes"));
        String longPrompt = "Please explain how local conversation storage remains private and secure";

        store.reserveTurn(UUID.randomUUID(), automatic.id(), UUID.randomUUID().toString(), longPrompt);
        store.reserveTurn(UUID.randomUUID(), custom.id(), UUID.randomUUID().toString(), longPrompt);
        ConversationRecord legacy = store.createConversation(new ConversationCreateRequest("New conversation"));
        store.appendMessage(legacy.id(), MessageRole.USER, longPrompt);
        ConversationRecord emptyLegacy =
                store.createConversation(new ConversationCreateRequest("New conversation"));

        String generatedTitle = store.findConversation(automatic.id()).orElseThrow().title();
        assertEquals("Please explain how local conversation storage remains private...", generatedTitle);
        assertTrue(generatedTitle.split("\\s+").length < 10);
        assertEquals(generatedTitle, store.findConversation(legacy.id()).orElseThrow().title());
        assertEquals("Empty conversation", store.findConversation(emptyLegacy.id()).orElseThrow().title());
        assertEquals("Project notes", store.findConversation(custom.id()).orElseThrow().title());
    }

    @Test
    void deletingConversationCascadesMessagesTurnsAndSearchIndexEntries() {
        SqliteConversationStore store = new SqliteConversationStore(tempDir.resolve("delete-conversation.db"));
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Delete me"));
        store.appendMessage(conversation.id(), MessageRole.USER, "a searchable phrase");
        var reservation = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "another user prompt");

        assertTrue(store.deleteConversation(conversation.id()));
        assertTrue(store.findConversation(conversation.id()).isEmpty());
        assertTrue(store.listMessages(conversation.id()).isEmpty());
        assertTrue(store.listTurns(conversation.id()).isEmpty());
        assertTrue(store.searchMessages(conversation.id(), "searchable phrase", 10).isEmpty());
        assertTrue(store.findTurn(reservation.turn().id()).isEmpty());
        assertTrue(!store.deleteConversation(conversation.id()));
    }

    @Test
    void completingTurnAtomicallyAddsAssistantMessageAndTerminalStatus() {
        SqliteConversationStore store = new SqliteConversationStore(tempDir.resolve("complete-turn.db"));
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Complete"));
        var reservation = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "user message");

        MessageRecord assistant = store.completeTurn(reservation.turn().id(), "assistant reply");

        assertEquals(com.localai.contracts.MessageRole.ASSISTANT, assistant.role());
        assertEquals(com.localai.contracts.TurnStatus.COMPLETED,
                store.findTurn(reservation.turn().id()).orElseThrow().status());
        assertEquals(2, store.listMessages(conversation.id()).size());
        assertThrows(IllegalStateException.class,
                () -> store.completeTurn(reservation.turn().id(), "duplicate reply"));
        assertEquals(2, store.listMessages(conversation.id()).size());
    }

    @Test
    void cancellationIntentIsDurableAndBecomesInterruptedAfterRestart() {
        Path databasePath = tempDir.resolve("cancel-intent.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Cancel"));
        var reservation = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "cancel me");

        assertTrue(store.requestTurnCancellation(reservation.turn().id()));
        assertEquals(com.localai.contracts.TurnStatus.CANCEL_REQUESTED,
                store.findTurn(reservation.turn().id()).orElseThrow().status());
        SqliteConversationStore reopenedStore = new SqliteConversationStore(databasePath);

        assertEquals(com.localai.contracts.TurnStatus.INTERRUPTED,
                reopenedStore.findTurn(reservation.turn().id()).orElseThrow().status());
        assertEquals(1, reopenedStore.listMessages(conversation.id()).size());
    }

    @Test
    void partialAssistantCheckpointSurvivesRestartAndIsClearedOnCompletion() {
        Path databasePath = tempDir.resolve("assistant-checkpoint.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Checkpoint"));
        var reservation = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "recover me");
        store.checkpointTurn(reservation.turn().id(), "partial ");
        store.checkpointTurn(reservation.turn().id(), "assistant text");

        SqliteConversationStore reopenedStore = new SqliteConversationStore(databasePath);

        assertEquals(com.localai.contracts.TurnStatus.INTERRUPTED,
                reopenedStore.findTurn(reservation.turn().id()).orElseThrow().status());
        assertEquals("partial assistant text",
                reopenedStore.findTurnCheckpoint(reservation.turn().id()).orElseThrow().partialAssistantText());

        var completedTurn = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "complete this");
        store.checkpointTurn(completedTurn.turn().id(), "partial completion");
        store.completeTurn(completedTurn.turn().id(), "complete assistant text");
        assertTrue(store.findTurnCheckpoint(completedTurn.turn().id()).isEmpty());
    }

    @Test
    void sourcedContextSummariesPersistAndCannotReferenceAnotherConversation() {
        Path databasePath = tempDir.resolve("context-summary.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord target = store.createConversation(new ConversationCreateRequest("Summary target"));
        ConversationRecord other = store.createConversation(new ConversationCreateRequest("Other"));
        MessageRecord sourceOne = store.appendMessage(target.id(), MessageRole.USER, "decision one");
        MessageRecord sourceTwo = store.appendMessage(target.id(), MessageRole.ASSISTANT, "decision two");
        MessageRecord foreignSource = store.appendMessage(other.id(), MessageRole.USER, "private");
        ContextSummary summary = new ContextSummary(
                UUID.randomUUID(),
                target.id(),
                "The selected decisions are retained.",
                List.of(sourceOne.id(), sourceTwo.id()),
                "model-x",
                Instant.now());

        store.saveContextSummary(summary);
        SqliteConversationStore reopened = new SqliteConversationStore(databasePath);

        assertEquals(List.of(summary), reopened.listContextSummaries(target.id()));
        ContextSummary foreignSummary = new ContextSummary(
                UUID.randomUUID(),
                target.id(),
                "Must not cross conversation boundaries.",
                List.of(sourceOne.id(), foreignSource.id()),
                "model-x",
                Instant.now());
        assertThrows(IllegalArgumentException.class, () -> reopened.saveContextSummary(foreignSummary));
        assertTrue(reopened.deleteContextSummary(target.id(), summary.id()));
        assertTrue(reopened.listContextSummaries(target.id()).isEmpty());
    }

    @Test
    void toolIntentIsIdempotentAndAmbiguousSideEffectRequiresReviewAfterRestart() {
        Path databasePath = tempDir.resolve("tool-recovery.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Tool recovery"));
        var turn = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "perform operation");
        Instant now = Instant.now();
        String invocationKey = UUID.randomUUID().toString();
        ToolInvocationRecord intent = new ToolInvocationRecord(
                UUID.randomUUID(),
                turn.turn().id(),
                invocationKey,
                "workspace.write",
                List.of("workspace.write:/approved/project"),
                true,
                ToolInvocationStatus.INTENT_RECORDED,
                now,
                now);

        var recorded = store.recordToolIntent(intent);
        var duplicate = store.recordToolIntent(new ToolInvocationRecord(
                UUID.randomUUID(),
                turn.turn().id(),
                invocationKey,
                intent.operation(),
                intent.requiredPermissions(),
                intent.sideEffecting(),
                ToolInvocationStatus.INTENT_RECORDED,
                now,
                now));
        assertEquals(recorded.id(), duplicate.id());
        assertEquals(ToolInvocationStatus.AUTHORIZED,
                store.authorizeToolInvocation(recorded.id(), true).status());
        assertEquals(ToolInvocationStatus.EXECUTING, store.beginToolInvocation(recorded.id()).status());

        SqliteConversationStore reopened = new SqliteConversationStore(databasePath);

        assertEquals(com.localai.contracts.TurnStatus.NEEDS_REVIEW,
                reopened.findTurn(turn.turn().id()).orElseThrow().status());
        assertEquals(ToolInvocationStatus.NEEDS_REVIEW,
                reopened.listToolInvocations(turn.turn().id()).getFirst().status());
        assertTrue(reopened.listDurableTurnEvents(turn.turn().id(), -1).stream()
                .anyMatch(event -> event.type().equals("turn.needs_review")));
    }

    @Test
    void toolIdempotencyConflictAndDenialNeverEnterExecution() {
        SqliteConversationStore store = new SqliteConversationStore(tempDir.resolve("tool-denial.db"));
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Tool denial"));
        var turn = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "do not execute");
        Instant now = Instant.now();
        ToolInvocationRecord intent = new ToolInvocationRecord(
                UUID.randomUUID(), turn.turn().id(), "stable-key", "workspace.delete",
                List.of("workspace.delete:/approved/project"), true,
                ToolInvocationStatus.INTENT_RECORDED, now, now);
        ToolInvocationRecord recorded = store.recordToolIntent(intent);
        assertThrows(com.localai.storage.ToolInvocationConflictException.class,
                () -> store.recordToolIntent(new ToolInvocationRecord(
                        UUID.randomUUID(), turn.turn().id(), "stable-key", "network.send",
                        List.of("network.send:example.invalid"), true,
                        ToolInvocationStatus.INTENT_RECORDED, now, now)));
        assertEquals(ToolInvocationStatus.DENIED, store.authorizeToolInvocation(recorded.id(), false).status());
        assertThrows(IllegalStateException.class, () -> store.beginToolInvocation(recorded.id()));
        assertEquals(ToolInvocationStatus.DENIED, store.listToolInvocations(turn.turn().id()).getFirst().status());
    }

    @Test
    void authorizedButNotStartedToolIntentIsInterruptedWithoutReviewAfterRestart() {
        Path databasePath = tempDir.resolve("tool-not-started.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Not started"));
        var turn = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "read data");
        Instant now = Instant.now();
        var intent = store.recordToolIntent(new ToolInvocationRecord(
                UUID.randomUUID(),
                turn.turn().id(),
                "read-key",
                "workspace.read",
                List.of("workspace.read:/approved/project"),
                false,
                ToolInvocationStatus.INTENT_RECORDED,
                now,
                now));
        store.authorizeToolInvocation(intent.id(), true);

        SqliteConversationStore reopened = new SqliteConversationStore(databasePath);

        assertEquals(com.localai.contracts.TurnStatus.INTERRUPTED,
                reopened.findTurn(turn.turn().id()).orElseThrow().status());
        assertEquals(ToolInvocationStatus.INTERRUPTED,
                reopened.listToolInvocations(turn.turn().id()).getFirst().status());
        assertTrue(reopened.listDurableTurnEvents(turn.turn().id(), -1).stream()
                .anyMatch(event -> event.type().equals("turn.interrupted")));
    }

    @Test
    void durableLifecycleEventsReplayAfterStoreRestartWithGapSafeSequence() {
        Path databasePath = tempDir.resolve("durable-events.db");
        SqliteConversationStore store = new SqliteConversationStore(databasePath);
        ConversationRecord conversation = store.createConversation(new ConversationCreateRequest("Event replay"));
        var reservation = store.reserveTurn(
                UUID.randomUUID(), conversation.id(), UUID.randomUUID().toString(), "prompt");
        long firstSequence = store.reserveEventSequenceBlock(reservation.turn().id());
        UUID turnId = reservation.turn().id();
        store.appendTurnEvent(new StreamEvent(
                turnId + ":" + firstSequence, turnId, Instant.now(), "turn.started",
                firstSequence, java.util.Map.of(), 1));

        SqliteConversationStore reopenedStore = new SqliteConversationStore(databasePath);
        List<StreamEvent> replayed = reopenedStore.listDurableTurnEvents(turnId, firstSequence);

        assertEquals(1, replayed.size());
        assertEquals("turn.interrupted", replayed.get(0).type());
        assertTrue(replayed.get(0).sequence() > firstSequence);
        assertEquals(reservation.turn().id(), replayed.get(0).turnId());
    }
}
