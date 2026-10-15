package com.localai.storage.sqlite;

import com.localai.contracts.ConversationCreateRequest;
import com.localai.contracts.ConversationRecord;
import com.localai.contracts.MessageRecord;
import com.localai.contracts.MessageRole;
import com.localai.contracts.TurnRecord;
import com.localai.contracts.TurnReservation;
import com.localai.contracts.TurnStatus;
import com.localai.contracts.StreamEvent;
import com.localai.contracts.ContextManifest;
import com.localai.contracts.TurnCheckpoint;
import com.localai.contracts.ContextSummary;
import com.localai.contracts.ToolInvocationRecord;
import com.localai.contracts.ToolInvocationStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localai.storage.ConversationStore;
import com.localai.storage.IdempotencyConflictException;
import com.localai.storage.ToolInvocationConflictException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class SqliteConversationStore implements ConversationStore {
    private static final String EMPTY_CONVERSATION_TITLE = "Empty conversation";
    private static final String LEGACY_EMPTY_CONVERSATION_TITLE = "New conversation";
    private static final int MAX_CONVERSATION_TITLE_WORDS = 8;
    private static final int EVENT_SEQUENCE_BLOCK_SIZE = 256;
    private static final ObjectMapper EVENT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Path databasePath;

    public SqliteConversationStore(Path databasePath) {
        if (databasePath == null) {
            throw new IllegalArgumentException("Database path must not be null");
        }
        this.databasePath = databasePath.toAbsolutePath().normalize();
        initializeParentDirectory();
        initializeSchema();
    }

    private void applyToolInvocationsMigration(Connection connection) throws SQLException {
        if (isMigrationApplied(connection, "V9__tool_invocations")) {
            return;
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : readMigration("db/migration/V9__tool_invocations.sql")
                    .split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            recordMigration(connection, "V9__tool_invocations");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void applyCheckpointSegmentsMigration(Connection connection) throws SQLException {
        if (isMigrationApplied(connection, "V8__checkpoint_segments")) {
            return;
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : readMigration("db/migration/V8__checkpoint_segments.sql")
                    .split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            recordMigration(connection, "V8__checkpoint_segments");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean hasAmbiguousSideEffect(Connection connection, UUID turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM tool_invocations
                WHERE turn_id = ? AND status = ? AND side_effecting = 1 LIMIT 1
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, ToolInvocationStatus.EXECUTING.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void reconcileStaleToolInvocations(Connection connection, UUID turnId) throws SQLException {
        try (PreparedStatement updateAmbiguous = connection.prepareStatement("""
                UPDATE tool_invocations SET status = ?, updated_at = ?
                WHERE turn_id = ? AND status = ? AND side_effecting = 1
                """);
             PreparedStatement updateUnstarted = connection.prepareStatement("""
                UPDATE tool_invocations SET status = ?, updated_at = ?
                WHERE turn_id = ? AND status IN (?, ?)
                """);
             PreparedStatement updateReadOnlyExecution = connection.prepareStatement("""
                UPDATE tool_invocations SET status = ?, updated_at = ?
                WHERE turn_id = ? AND status = ? AND side_effecting = 0
                """)) {
            String now = Instant.now().toString();
            updateAmbiguous.setString(1, ToolInvocationStatus.NEEDS_REVIEW.name());
            updateAmbiguous.setString(2, now);
            updateAmbiguous.setString(3, turnId.toString());
            updateAmbiguous.setString(4, ToolInvocationStatus.EXECUTING.name());
            updateAmbiguous.executeUpdate();

            updateUnstarted.setString(1, ToolInvocationStatus.INTERRUPTED.name());
            updateUnstarted.setString(2, now);
            updateUnstarted.setString(3, turnId.toString());
            updateUnstarted.setString(4, ToolInvocationStatus.INTENT_RECORDED.name());
            updateUnstarted.setString(5, ToolInvocationStatus.AUTHORIZED.name());
            updateUnstarted.executeUpdate();

            updateReadOnlyExecution.setString(1, ToolInvocationStatus.INTERRUPTED.name());
            updateReadOnlyExecution.setString(2, now);
            updateReadOnlyExecution.setString(3, turnId.toString());
            updateReadOnlyExecution.setString(4, ToolInvocationStatus.EXECUTING.name());
            updateReadOnlyExecution.executeUpdate();
        }
    }

    @Override
    public ConversationRecord createConversation(ConversationCreateRequest request) {
        String title = request.title();
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.now();

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, conversationId.toString());
            statement.setString(2, title);
            statement.setString(3, now.toString());
            statement.setString(4, now.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create conversation", e);
        }

        return new ConversationRecord(conversationId, title, now, now);
    }

    @Override
    public Optional<ConversationRecord> findConversation(UUID conversationId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     conversationSelect() + " WHERE c.id = ?")) {
            statement.setString(1, conversationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapConversation(resultSet));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read conversation", e);
        }
    }

    @Override
    public List<ConversationRecord> listConversations() {
        List<ConversationRecord> conversations = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     conversationSelect() + " ORDER BY c.created_at ASC, c.id ASC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    conversations.add(mapConversation(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list conversations", e);
        }
        return conversations;
    }

    @Override
    public boolean deleteConversation(UUID conversationId) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM conversations WHERE id = ?")) {
                statement.setString(1, conversationId.toString());
                boolean deleted = statement.executeUpdate() == 1;
                connection.commit();
                return deleted;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete conversation", e);
        }
    }

    @Override
    public MessageRecord appendMessage(UUID conversationId, MessageRole role, String content) {
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO messages (id, conversation_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, messageId.toString());
            statement.setString(2, conversationId.toString());
            statement.setString(3, role.name());
            statement.setString(4, content);
            statement.setString(5, now.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append message", e);
        }

        return new MessageRecord(messageId, conversationId, role, content, now);
    }

    @Override
    public List<MessageRecord> listMessages(UUID conversationId) {
        List<MessageRecord> messages = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, conversation_id, role, content, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at ASC")) {
            statement.setString(1, conversationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(mapMessage(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list messages", e);
        }
        return messages;
    }

    @Override
    public List<MessageRecord> searchMessages(UUID conversationId, String query, int limit) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation id must not be null");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Search limit must be between 1 and 100");
        }

        List<MessageRecord> messages = new ArrayList<>();
        String ftsQuery = "\"" + query.trim().replace("\"", "\"\"") + "\"";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.id, m.conversation_id, m.role, m.content, m.created_at
                     FROM message_search
                     JOIN messages m ON m.id = message_search.message_id
                     WHERE message_search MATCH ? AND message_search.conversation_id = ?
                     ORDER BY rank
                     LIMIT ?
                     """)) {
            statement.setString(1, ftsQuery);
            statement.setString(2, conversationId.toString());
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(mapMessage(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to search conversation messages", e);
        }
        return messages;
    }

    @Override
    public synchronized TurnReservation reserveTurn(
            UUID turnId,
            UUID conversationId,
            String clientRequestId,
            String content) {
        if (turnId == null || conversationId == null || clientRequestId == null || clientRequestId.isBlank()
                || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Turn identity, conversation, request ID and content are required");
        }
        String requestHash = sha256(content);
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT id, conversation_id, user_message_id, client_request_id, request_hash,
                            status, created_at, updated_at
                        FROM turns WHERE conversation_id = ? AND client_request_id = ?
                        """)) {
                    find.setString(1, conversationId.toString());
                    find.setString(2, clientRequestId);
                    try (ResultSet resultSet = find.executeQuery()) {
                        if (resultSet.next()) {
                            TurnRecord existing = mapTurn(resultSet);
                            if (!existing.requestHash().equals(requestHash)) {
                                throw new IdempotencyConflictException(
                                        "clientRequestId was already used with different turn content");
                            }
                            connection.commit();
                            return new TurnReservation(existing, false);
                        }
                    }
                }

                if (findConversation(conversationId).isEmpty()) {
                    throw new IllegalArgumentException("Conversation not found: " + conversationId);
                }
                try (PreparedStatement updateTitle = connection.prepareStatement("""
                        UPDATE conversations SET title = ?, updated_at = ?
                        WHERE id = ? AND title IN (?, ?)
                        """)) {
                    updateTitle.setString(1, conversationTitle(content));
                    updateTitle.setString(2, now.toString());
                    updateTitle.setString(3, conversationId.toString());
                    updateTitle.setString(4, EMPTY_CONVERSATION_TITLE);
                    updateTitle.setString(5, LEGACY_EMPTY_CONVERSATION_TITLE);
                    updateTitle.executeUpdate();
                }
                UUID userMessageId = UUID.randomUUID();
                try (PreparedStatement insertMessage = connection.prepareStatement("""
                        INSERT INTO messages (id, conversation_id, role, content, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """);
                     PreparedStatement insertTurn = connection.prepareStatement("""
                        INSERT INTO turns
                            (id, conversation_id, user_message_id, client_request_id, request_hash,
                             status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insertMessage.setString(1, userMessageId.toString());
                    insertMessage.setString(2, conversationId.toString());
                    insertMessage.setString(3, MessageRole.USER.name());
                    insertMessage.setString(4, content);
                    insertMessage.setString(5, now.toString());
                    insertMessage.executeUpdate();
                    insertTurn.setString(1, turnId.toString());
                    insertTurn.setString(2, conversationId.toString());
                    insertTurn.setString(3, userMessageId.toString());
                    insertTurn.setString(4, clientRequestId);
                    insertTurn.setString(5, requestHash);
                    insertTurn.setString(6, TurnStatus.RUNNING.name());
                    insertTurn.setString(7, now.toString());
                    insertTurn.setString(8, now.toString());
                    insertTurn.executeUpdate();
                }
                connection.commit();
                return new TurnReservation(new TurnRecord(
                        turnId, conversationId, userMessageId, clientRequestId,
                        requestHash, TurnStatus.RUNNING, now, now), true);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == 19) {
                TurnRecord existing = findTurnByRequestId(conversationId, clientRequestId);
                if (existing != null) {
                    if (!existing.requestHash().equals(requestHash)) {
                        throw new IdempotencyConflictException(
                                "clientRequestId was already used with different turn content");
                    }
                    return new TurnReservation(existing, false);
                }
            }
            throw new IllegalStateException("Failed to reserve turn", e);
        }
    }

    @Override
    public Optional<TurnRecord> findTurn(UUID turnId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, conversation_id, user_message_id, client_request_id, request_hash,
                         status, created_at, updated_at
                     FROM turns WHERE id = ?
                     """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapTurn(resultSet));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read turn", e);
        }
    }

    @Override
    public List<TurnRecord> listTurns(UUID conversationId) {
        List<TurnRecord> turns = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, conversation_id, user_message_id, client_request_id, request_hash,
                         status, created_at, updated_at
                     FROM turns WHERE conversation_id = ? ORDER BY created_at ASC, id ASC
                     """)) {
            statement.setString(1, conversationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    turns.add(mapTurn(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list conversation turns", e);
        }
        return List.copyOf(turns);
    }

    @Override
    public MessageRecord completeTurn(UUID turnId, String assistantContent) {
        return completeTurn(turnId, assistantContent, null);
    }

    @Override
    public MessageRecord completeTurn(UUID turnId, String assistantContent, StreamEvent terminalEvent) {
        return completeTurn(turnId, assistantContent, UUID.randomUUID(), terminalEvent);
    }

    @Override
    public MessageRecord completeTurn(
            UUID turnId,
            String assistantContent,
            UUID assistantMessageId,
            StreamEvent terminalEvent) {
        if (assistantContent == null) {
            throw new IllegalArgumentException("Assistant content must not be null");
        }
        if (assistantMessageId == null) {
            throw new IllegalArgumentException("Assistant message id must not be null");
        }
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID conversationId;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE turns SET status = ?, updated_at = ?,
                            assistant_checkpoint = NULL, checkpoint_updated_at = NULL
                        WHERE id = ? AND status = ?
                        """)) {
                    update.setString(1, TurnStatus.COMPLETED.name());
                    update.setString(2, now.toString());
                    update.setString(3, turnId.toString());
                    update.setString(4, TurnStatus.RUNNING.name());
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("Turn is missing or no longer running: " + turnId);
                    }
                }
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT conversation_id FROM turns WHERE id = ?")) {
                    select.setString(1, turnId.toString());
                    try (ResultSet resultSet = select.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("Turn disappeared during completion: " + turnId);
                        }
                        conversationId = UUID.fromString(resultSet.getString(1));
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO messages (id, conversation_id, role, content, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, assistantMessageId.toString());
                    insert.setString(2, conversationId.toString());
                    insert.setString(3, MessageRole.ASSISTANT.name());
                    insert.setString(4, assistantContent);
                    insert.setString(5, now.toString());
                    insert.executeUpdate();
                }
                if (terminalEvent != null) {
                    insertTurnEvent(connection, terminalEvent);
                }
                try (PreparedStatement deleteCheckpoint = connection.prepareStatement(
                        "DELETE FROM turn_checkpoint_segments WHERE turn_id = ?")) {
                    deleteCheckpoint.setString(1, turnId.toString());
                    deleteCheckpoint.executeUpdate();
                }
                connection.commit();
                return new MessageRecord(
                        assistantMessageId, conversationId, MessageRole.ASSISTANT, assistantContent, now);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to complete turn", e);
        }
    }

    @Override
    public void updateTurnStatus(UUID turnId, TurnStatus status) {
        updateTurnStatus(turnId, status, null);
    }

    @Override
    public void updateTurnStatus(UUID turnId, TurnStatus status, StreamEvent terminalEvent) {
        if (status == null || status == TurnStatus.RUNNING || status == TurnStatus.CANCEL_REQUESTED) {
            throw new IllegalArgumentException("A terminal turn status is required");
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                     UPDATE turns SET status = ?, updated_at = ?
                     WHERE id = ? AND status IN (?, ?)
                     """)) {
                statement.setString(1, status.name());
                statement.setString(2, Instant.now().toString());
                statement.setString(3, turnId.toString());
                statement.setString(4, TurnStatus.RUNNING.name());
                statement.setString(5, TurnStatus.CANCEL_REQUESTED.name());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Turn is missing or no longer running: " + turnId);
                }
            }
            if (terminalEvent != null) {
                insertTurnEvent(connection, terminalEvent);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Failed to update turn status and event", e);
        }
    }

    @Override
    public long reserveEventSequenceBlock(UUID turnId) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            long start;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT sequence_ceiling FROM turns WHERE id = ?")) {
                select.setString(1, turnId.toString());
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("Turn not found: " + turnId);
                    }
                    start = resultSet.getLong(1);
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE turns SET sequence_ceiling = ? WHERE id = ? AND sequence_ceiling = ?")) {
                update.setLong(1, start + EVENT_SEQUENCE_BLOCK_SIZE);
                update.setString(2, turnId.toString());
                update.setLong(3, start);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Turn event sequence block was concurrently reserved");
                }
            }
            connection.commit();
            return start;
        } catch (RuntimeException e) {
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reserve event sequence block", e);
        }
    }

    @Override
    public void appendTurnEvent(StreamEvent event) {
        try (Connection connection = openConnection()) {
            insertTurnEvent(connection, event);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist turn event", e);
        }
    }

    @Override
    public List<StreamEvent> listDurableTurnEvents(UUID turnId, long afterSequence) {
        List<StreamEvent> events = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event_json FROM run_events
                     WHERE turn_id = ? AND sequence > ?
                     ORDER BY sequence ASC
                     """)) {
            statement.setString(1, turnId.toString());
            statement.setLong(2, afterSequence);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(EVENT_MAPPER.readValue(resultSet.getString(1), StreamEvent.class));
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not decode durable turn event", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read durable turn events", e);
        }
        return List.copyOf(events);
    }

    @Override
    public void saveContextManifest(ContextManifest manifest) {
        String json;
        try {
            json = EVENT_MAPPER.writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode context manifest", e);
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE turns SET context_manifest_json = ? WHERE id = ?")) {
            statement.setString(1, json);
            statement.setString(2, manifest.turnId().toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Turn not found: " + manifest.turnId());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist context manifest", e);
        }
    }

    @Override
    public Optional<ContextManifest> findContextManifest(UUID turnId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT context_manifest_json FROM turns WHERE id = ?")) {
            statement.setString(1, turnId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getString(1) == null) {
                    return Optional.empty();
                }
                return Optional.of(EVENT_MAPPER.readValue(resultSet.getString(1), ContextManifest.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not decode context manifest", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read context manifest", e);
        }
    }

    @Override
    public void checkpointTurn(UUID turnId, String partialAssistantText) {
        if (turnId == null || partialAssistantText == null || partialAssistantText.isEmpty()) {
            throw new IllegalArgumentException("Turn id and non-empty checkpoint segment are required");
        }
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement("""
                     UPDATE turns SET assistant_checkpoint = ?, checkpoint_updated_at = ?
                     WHERE id = ? AND status IN (?, ?)
                     """)) {
                    update.setNull(1, java.sql.Types.VARCHAR);
                    update.setString(2, now.toString());
                    update.setString(3, turnId.toString());
                    update.setString(4, TurnStatus.RUNNING.name());
                    update.setString(5, TurnStatus.CANCEL_REQUESTED.name());
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("Turn is missing or cannot be checkpointed: " + turnId);
                    }
                }
                long nextSequence;
                try (PreparedStatement sequence = connection.prepareStatement("""
                    SELECT COALESCE(MAX(sequence), -1) + 1
                    FROM turn_checkpoint_segments WHERE turn_id = ?
                    """)) {
                    sequence.setString(1, turnId.toString());
                    try (ResultSet resultSet = sequence.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("Could not allocate checkpoint sequence");
                        }
                        nextSequence = resultSet.getLong(1);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO turn_checkpoint_segments (turn_id, sequence, content, created_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                    insert.setString(1, turnId.toString());
                    insert.setLong(2, nextSequence);
                    insert.setString(3, partialAssistantText);
                    insert.setString(4, now.toString());
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist assistant checkpoint segment", e);
        }
    }

    @Override
    public Optional<TurnCheckpoint> findTurnCheckpoint(UUID turnId) {
        StringBuilder partialText = new StringBuilder();
        Instant updatedAt = null;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT content, created_at FROM turn_checkpoint_segments
                     WHERE turn_id = ? ORDER BY sequence ASC
                     """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    partialText.append(resultSet.getString("content"));
                    updatedAt = Instant.parse(resultSet.getString("created_at"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read assistant checkpoint segments", e);
        }
        if (!partialText.isEmpty()) {
            return Optional.of(new TurnCheckpoint(turnId, partialText.toString(), updatedAt));
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT assistant_checkpoint, checkpoint_updated_at FROM turns WHERE id = ?
                     """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getString(1) == null) {
                    return Optional.empty();
                }
                return Optional.of(new TurnCheckpoint(
                        turnId,
                        resultSet.getString(1),
                        Instant.parse(resultSet.getString(2))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read assistant checkpoint", e);
        }
    }

    @Override
    public ContextSummary saveContextSummary(ContextSummary summary) {
        if (summary == null) {
            throw new IllegalArgumentException("Context summary must not be null");
        }
        String sourceIds;
        try {
            sourceIds = EVENT_MAPPER.writeValueAsString(summary.sourceMessageIds());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode context summary sources", e);
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int foundSources = 0;
                try (PreparedStatement sourceCheck = connection.prepareStatement("""
                        SELECT COUNT(DISTINCT id) FROM messages
                        WHERE conversation_id = ? AND id IN (
                        """ + String.join(",", java.util.Collections.nCopies(
                                summary.sourceMessageIds().size(), "?")) + ")")) {
                    sourceCheck.setString(1, summary.conversationId().toString());
                    for (int i = 0; i < summary.sourceMessageIds().size(); i++) {
                        sourceCheck.setString(i + 2, summary.sourceMessageIds().get(i).toString());
                    }
                    try (ResultSet resultSet = sourceCheck.executeQuery()) {
                        if (resultSet.next()) {
                            foundSources = resultSet.getInt(1);
                        }
                    }
                }
                if (foundSources != summary.sourceMessageIds().size()) {
                    throw new IllegalArgumentException(
                            "Every context summary source must belong to its conversation");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO context_summaries
                            (id, conversation_id, content, source_message_ids_json, model_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, summary.id().toString());
                    insert.setString(2, summary.conversationId().toString());
                    insert.setString(3, summary.content());
                    insert.setString(4, sourceIds);
                    insert.setString(5, summary.modelId());
                    insert.setString(6, summary.createdAt().toString());
                    insert.executeUpdate();
                }
                connection.commit();
                return summary;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist context summary", e);
        }
    }

    @Override
    public List<ContextSummary> listContextSummaries(UUID conversationId) {
        List<ContextSummary> summaries = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, conversation_id, content, source_message_ids_json, model_id, created_at
                     FROM context_summaries WHERE conversation_id = ? ORDER BY created_at ASC
                     """)) {
            statement.setString(1, conversationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    try {
                        List<UUID> sourceIds = EVENT_MAPPER.readValue(
                                resultSet.getString("source_message_ids_json"),
                                new TypeReference<>() { });
                        summaries.add(new ContextSummary(
                                UUID.fromString(resultSet.getString("id")),
                                UUID.fromString(resultSet.getString("conversation_id")),
                                resultSet.getString("content"),
                                sourceIds,
                                resultSet.getString("model_id"),
                                Instant.parse(resultSet.getString("created_at"))));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Could not decode context summary sources", e);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list context summaries", e);
        }
        return List.copyOf(summaries);
    }

    @Override
    public boolean deleteContextSummary(UUID conversationId, UUID summaryId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM context_summaries WHERE id = ? AND conversation_id = ?")) {
            statement.setString(1, summaryId.toString());
            statement.setString(2, conversationId.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete context summary", e);
        }
    }

    @Override
    public synchronized ToolInvocationRecord recordToolIntent(ToolInvocationRecord intent) {
        if (intent == null || intent.status() != ToolInvocationStatus.INTENT_RECORDED) {
            throw new IllegalArgumentException("A new tool invocation must be an intent-recorded request");
        }
        String permissions;
        try {
            permissions = EVENT_MAPPER.writeValueAsString(intent.requiredPermissions());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode tool invocation permissions", e);
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement existingQuery = connection.prepareStatement("""
                        SELECT id, turn_id, idempotency_key, operation, required_permissions_json,
                            side_effecting, status, created_at, updated_at
                        FROM tool_invocations WHERE turn_id = ? AND idempotency_key = ?
                        """)) {
                    existingQuery.setString(1, intent.turnId().toString());
                    existingQuery.setString(2, intent.idempotencyKey());
                    try (ResultSet resultSet = existingQuery.executeQuery()) {
                        if (resultSet.next()) {
                            ToolInvocationRecord existing = mapToolInvocation(resultSet);
                            if (!sameToolIntent(existing, intent)) {
                                throw new ToolInvocationConflictException(
                                        "Tool idempotency key was reused with different intent");
                            }
                            connection.commit();
                            return existing;
                        }
                    }
                }
                if (!turnHasStatus(connection, intent.turnId(), TurnStatus.RUNNING)) {
                    throw new IllegalStateException("Tool intent requires a running turn: " + intent.turnId());
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO tool_invocations
                            (id, turn_id, idempotency_key, operation, required_permissions_json,
                             side_effecting, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, intent.id().toString());
                    insert.setString(2, intent.turnId().toString());
                    insert.setString(3, intent.idempotencyKey());
                    insert.setString(4, intent.operation());
                    insert.setString(5, permissions);
                    insert.setInt(6, intent.sideEffecting() ? 1 : 0);
                    insert.setString(7, ToolInvocationStatus.INTENT_RECORDED.name());
                    insert.setString(8, intent.createdAt().toString());
                    insert.setString(9, intent.updatedAt().toString());
                    insert.executeUpdate();
                }
                connection.commit();
                return intent;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist tool intent", e);
        }
    }

    @Override
    public ToolInvocationRecord authorizeToolInvocation(UUID invocationId, boolean approved) {
        return transitionToolInvocation(
                invocationId,
                ToolInvocationStatus.INTENT_RECORDED,
                approved ? ToolInvocationStatus.AUTHORIZED : ToolInvocationStatus.DENIED);
    }

    @Override
    public ToolInvocationRecord beginToolInvocation(UUID invocationId) {
        return transitionToolInvocation(
                invocationId, ToolInvocationStatus.AUTHORIZED, ToolInvocationStatus.EXECUTING);
    }

    @Override
    public ToolInvocationRecord finishToolInvocation(UUID invocationId, boolean succeeded) {
        return transitionToolInvocation(
                invocationId,
                ToolInvocationStatus.EXECUTING,
                succeeded ? ToolInvocationStatus.SUCCEEDED : ToolInvocationStatus.FAILED);
    }

    @Override
    public List<ToolInvocationRecord> listToolInvocations(UUID turnId) {
        List<ToolInvocationRecord> invocations = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, turn_id, idempotency_key, operation, required_permissions_json,
                         side_effecting, status, created_at, updated_at
                     FROM tool_invocations WHERE turn_id = ? ORDER BY created_at ASC, id ASC
                     """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    invocations.add(mapToolInvocation(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list tool invocations", e);
        }
        return List.copyOf(invocations);
    }

    private ToolInvocationRecord transitionToolInvocation(
            UUID invocationId,
            ToolInvocationStatus expected,
            ToolInvocationStatus next) {
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                String updateSql = next == ToolInvocationStatus.EXECUTING
                        ? """
                          UPDATE tool_invocations SET status = ?, updated_at = ?
                          WHERE id = ? AND status = ?
                              AND EXISTS (
                                  SELECT 1 FROM turns
                                  WHERE turns.id = tool_invocations.turn_id AND turns.status = ?
                              )
                          """
                        : """
                          UPDATE tool_invocations SET status = ?, updated_at = ?
                          WHERE id = ? AND status = ?
                          """;
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setString(1, next.name());
                    update.setString(2, now.toString());
                    update.setString(3, invocationId.toString());
                    update.setString(4, expected.name());
                    if (next == ToolInvocationStatus.EXECUTING) {
                        update.setString(5, TurnStatus.RUNNING.name());
                    }
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "Tool invocation is missing or not in expected state " + expected + ": " + invocationId);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to transition tool invocation", e);
        }
        try (Connection connection = openConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT id, turn_id, idempotency_key, operation, required_permissions_json,
                         side_effecting, status, created_at, updated_at
                     FROM tool_invocations WHERE id = ?
                     """)) {
            select.setString(1, invocationId.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Tool invocation disappeared after transition: " + invocationId);
                }
                return mapToolInvocation(resultSet);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read transitioned tool invocation", e);
        }
    }

    private boolean turnHasStatus(Connection connection, UUID turnId, TurnStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM turns WHERE id = ? AND status = ?")) {
            statement.setString(1, turnId.toString());
            statement.setString(2, status.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean sameToolIntent(ToolInvocationRecord existing, ToolInvocationRecord requested) {
        return existing.operation().equals(requested.operation())
                && existing.requiredPermissions().equals(requested.requiredPermissions())
                && existing.sideEffecting() == requested.sideEffecting();
    }

    private void insertTurnEvent(Connection connection, StreamEvent event) throws SQLException {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        String json;
        try {
            json = EVENT_MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode durable turn event", e);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_events (turn_id, sequence, event_json, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, event.turnId().toString());
            statement.setLong(2, event.sequence());
            statement.setString(3, json);
            statement.setString(4, event.timestamp().toString());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean requestTurnCancellation(UUID turnId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE turns SET status = ?, updated_at = ?
                     WHERE id = ? AND status = ?
                     """)) {
            statement.setString(1, TurnStatus.CANCEL_REQUESTED.name());
            statement.setString(2, Instant.now().toString());
            statement.setString(3, turnId.toString());
            statement.setString(4, TurnStatus.RUNNING.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist turn cancellation request", e);
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private void initializeParentDirectory() {
        Path parent = databasePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create SQLite directory", e);
            }
        }
    }

    private void initializeSchema() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            String migration = """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version TEXT PRIMARY KEY,
                        applied_at TEXT NOT NULL
                    );

                    CREATE TABLE IF NOT EXISTS conversations (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    );

                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
                    );

                    CREATE INDEX IF NOT EXISTS idx_messages_conversation_id
                        ON messages (conversation_id, created_at);
                    """;
            statement.executeUpdate(migration);

            try (PreparedStatement migrationCheck = connection.prepareStatement(
                    "INSERT OR IGNORE INTO schema_migrations (version, applied_at) VALUES (?, ?)")) {
                migrationCheck.setString(1, "V1__initial_schema");
                migrationCheck.setString(2, Instant.now().toString());
                migrationCheck.executeUpdate();
            }
            applyMessageSearchMigration(connection);
            applyDurableTurnMigration(connection);
            applyDurableRunEventsMigration(connection);
            applyContextManifestMigration(connection);
            applyAssistantCheckpointMigration(connection);
            applyContextSummariesMigration(connection);
            applyCheckpointSegmentsMigration(connection);
            applyToolInvocationsMigration(connection);
            markStaleTurnsInterrupted(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite schema", e);
        }
    }

    private void applyMessageSearchMigration(Connection connection) throws SQLException {
        try (PreparedStatement migrationCheck = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            migrationCheck.setString(1, "V2__message_search");
            try (ResultSet resultSet = migrationCheck.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }

        String migration = readMigration("db/migration/V2__message_search.sql");
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : migration.split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            try (PreparedStatement recordMigration = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)")) {
                recordMigration.setString(1, "V2__message_search");
                recordMigration.setString(2, Instant.now().toString());
                recordMigration.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void applyDurableTurnMigration(Connection connection) throws SQLException {
        try (PreparedStatement migrationCheck = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            migrationCheck.setString(1, "V3__durable_turns");
            try (ResultSet resultSet = migrationCheck.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : readMigration("db/migration/V3__durable_turns.sql")
                    .split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            try (PreparedStatement recordMigration = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)")) {
                recordMigration.setString(1, "V3__durable_turns");
                recordMigration.setString(2, Instant.now().toString());
                recordMigration.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void applyDurableRunEventsMigration(Connection connection) throws SQLException {
        if (isMigrationApplied(connection, "V4__durable_turn_events")) {
            return;
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : readMigration("db/migration/V4__durable_turn_events.sql")
                    .split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            recordMigration(connection, "V4__durable_turn_events");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void applyContextManifestMigration(Connection connection) throws SQLException {
        if (isMigrationApplied(connection, "V5__context_manifests")) {
            return;
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : readMigration("db/migration/V5__context_manifests.sql")
                    .split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            recordMigration(connection, "V5__context_manifests");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void applyAssistantCheckpointMigration(Connection connection) throws SQLException {
        if (isMigrationApplied(connection, "V6__assistant_checkpoints")) {
            return;
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : readMigration("db/migration/V6__assistant_checkpoints.sql")
                    .split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            recordMigration(connection, "V6__assistant_checkpoints");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void applyContextSummariesMigration(Connection connection) throws SQLException {
        if (isMigrationApplied(connection, "V7__context_summaries")) {
            return;
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sqlStatement : readMigration("db/migration/V7__context_summaries.sql")
                    .split("(?m)^-- statement\\s*$")) {
                if (!sqlStatement.isBlank()) {
                    statement.execute(sqlStatement);
                }
            }
            recordMigration(connection, "V7__context_summaries");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean isMigrationApplied(Connection connection, String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void recordMigration(Connection connection, String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)")) {
            statement.setString(1, version);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void markStaleTurnsInterrupted(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            List<TurnRecord> staleTurns = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, conversation_id, user_message_id, client_request_id, request_hash,
                        status, created_at, updated_at
                    FROM turns WHERE status IN ('RUNNING', 'CANCEL_REQUESTED')
                    """);
                 ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    staleTurns.add(mapTurn(resultSet));
                }
            }
            for (TurnRecord turn : staleTurns) {
                boolean needsReview = hasAmbiguousSideEffect(connection, turn.id());
                reconcileStaleToolInvocations(connection, turn.id());
                long sequence;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT sequence_ceiling FROM turns WHERE id = ?")) {
                    select.setString(1, turn.id().toString());
                    try (ResultSet resultSet = select.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("Turn disappeared during recovery: " + turn.id());
                        }
                        sequence = resultSet.getLong(1) + EVENT_SEQUENCE_BLOCK_SIZE;
                    }
                }
                Instant now = Instant.now();
                TurnStatus recoveryStatus = needsReview ? TurnStatus.NEEDS_REVIEW : TurnStatus.INTERRUPTED;
                String eventType = needsReview ? "turn.needs_review" : "turn.interrupted";
                String message = needsReview
                        ? "A side-effecting operation may have run before the backend restarted. Review it before retrying."
                        : "The backend restarted before this turn completed.";
                StreamEvent event = new StreamEvent(
                        turn.id() + ":" + sequence, turn.id(), now, eventType, sequence,
                        java.util.Map.of("message", message), 1);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE turns SET status = ?, updated_at = ?, sequence_ceiling = ? WHERE id = ?")) {
                    update.setString(1, recoveryStatus.name());
                    update.setString(2, now.toString());
                    update.setLong(3, sequence);
                    update.setString(4, turn.id().toString());
                    update.executeUpdate();
                }
                insertTurnEvent(connection, event);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private String readMigration(String resourcePath) {
        try (InputStream input = SqliteConversationStore.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing SQLite migration resource: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SQLite migration: " + resourcePath, e);
        }
    }

    private ConversationRecord mapConversation(ResultSet resultSet) throws SQLException {
        UUID id = UUID.fromString(resultSet.getString("id"));
        String title = resultSet.getString("title");
        String firstUserContent = resultSet.getString("first_user_content");
        if (EMPTY_CONVERSATION_TITLE.equals(title) || LEGACY_EMPTY_CONVERSATION_TITLE.equals(title)) {
            title = firstUserContent == null || firstUserContent.isBlank()
                    ? EMPTY_CONVERSATION_TITLE
                    : conversationTitle(firstUserContent);
        }
        Instant createdAt = Instant.parse(resultSet.getString("created_at"));
        Instant updatedAt = Instant.parse(resultSet.getString("updated_at"));
        return new ConversationRecord(id, title, createdAt, updatedAt);
    }

    private String conversationSelect() {
        return """
                SELECT c.id, c.title, c.created_at, c.updated_at,
                    (SELECT m.content FROM messages m
                     WHERE m.conversation_id = c.id AND m.role = 'USER'
                     ORDER BY m.created_at ASC, m.id ASC LIMIT 1) AS first_user_content
                FROM conversations c
                """;
    }

    private static String conversationTitle(String firstMessage) {
        String normalized = firstMessage.replaceAll("\\s+", " ").strip();
        if (normalized.isEmpty()) {
            return EMPTY_CONVERSATION_TITLE;
        }
        String[] words = normalized.split(" ");
        int wordCount = Math.min(words.length, MAX_CONVERSATION_TITLE_WORDS);
        String title = String.join(" ", java.util.Arrays.copyOf(words, wordCount));
        return words.length > MAX_CONVERSATION_TITLE_WORDS ? title + "..." : title;
    }

    private MessageRecord mapMessage(ResultSet resultSet) throws SQLException {
        UUID id = UUID.fromString(resultSet.getString("id"));
        UUID conversationId = UUID.fromString(resultSet.getString("conversation_id"));
        MessageRole role = MessageRole.valueOf(resultSet.getString("role"));
        String content = resultSet.getString("content");
        Instant createdAt = Instant.parse(resultSet.getString("created_at"));
        return new MessageRecord(id, conversationId, role, content, createdAt);
    }

    private TurnRecord mapTurn(ResultSet resultSet) throws SQLException {
        return new TurnRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("conversation_id")),
                UUID.fromString(resultSet.getString("user_message_id")),
                resultSet.getString("client_request_id"),
                resultSet.getString("request_hash"),
                TurnStatus.valueOf(resultSet.getString("status")),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")));
    }

    private ToolInvocationRecord mapToolInvocation(ResultSet resultSet) throws SQLException {
        try {
            List<String> requiredPermissions = EVENT_MAPPER.readValue(
                    resultSet.getString("required_permissions_json"), new TypeReference<>() { });
            return new ToolInvocationRecord(
                    UUID.fromString(resultSet.getString("id")),
                    UUID.fromString(resultSet.getString("turn_id")),
                    resultSet.getString("idempotency_key"),
                    resultSet.getString("operation"),
                    requiredPermissions,
                    resultSet.getInt("side_effecting") != 0,
                    ToolInvocationStatus.valueOf(resultSet.getString("status")),
                    Instant.parse(resultSet.getString("created_at")),
                    Instant.parse(resultSet.getString("updated_at")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not decode tool invocation permissions", e);
        }
    }

    private TurnRecord findTurnByRequestId(UUID conversationId, String clientRequestId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, conversation_id, user_message_id, client_request_id, request_hash,
                         status, created_at, updated_at
                     FROM turns WHERE conversation_id = ? AND client_request_id = ?
                     """)) {
            statement.setString(1, conversationId.toString());
            statement.setString(2, clientRequestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapTurn(resultSet) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find turn by request ID", e);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

}
