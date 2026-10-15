package com.localai.storage;

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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationStore {
    ConversationRecord createConversation(ConversationCreateRequest request);

    Optional<ConversationRecord> findConversation(UUID conversationId);

    List<ConversationRecord> listConversations();

    boolean deleteConversation(UUID conversationId);

    MessageRecord appendMessage(UUID conversationId, MessageRole role, String content);

    List<MessageRecord> listMessages(UUID conversationId);

    List<MessageRecord> searchMessages(UUID conversationId, String query, int limit);

    TurnReservation reserveTurn(
            UUID turnId,
            UUID conversationId,
            String clientRequestId,
            String content);

    Optional<TurnRecord> findTurn(UUID turnId);

    List<TurnRecord> listTurns(UUID conversationId);

    MessageRecord completeTurn(UUID turnId, String assistantContent);

    MessageRecord completeTurn(UUID turnId, String assistantContent, StreamEvent terminalEvent);

    MessageRecord completeTurn(
            UUID turnId,
            String assistantContent,
            UUID assistantMessageId,
            StreamEvent terminalEvent);

    boolean requestTurnCancellation(UUID turnId);

    void updateTurnStatus(UUID turnId, TurnStatus status);

    void updateTurnStatus(UUID turnId, TurnStatus status, StreamEvent terminalEvent);

    long reserveEventSequenceBlock(UUID turnId);

    void appendTurnEvent(StreamEvent event);

    List<StreamEvent> listDurableTurnEvents(UUID turnId, long afterSequence);

    void saveContextManifest(ContextManifest manifest);

    Optional<ContextManifest> findContextManifest(UUID turnId);

    void checkpointTurn(UUID turnId, String partialAssistantText);

    Optional<TurnCheckpoint> findTurnCheckpoint(UUID turnId);

    ContextSummary saveContextSummary(ContextSummary summary);

    List<ContextSummary> listContextSummaries(UUID conversationId);

    boolean deleteContextSummary(UUID conversationId, UUID summaryId);

    ToolInvocationRecord recordToolIntent(ToolInvocationRecord intent);

    ToolInvocationRecord authorizeToolInvocation(UUID invocationId, boolean approved);

    ToolInvocationRecord beginToolInvocation(UUID invocationId);

    ToolInvocationRecord finishToolInvocation(UUID invocationId, boolean succeeded);

    List<ToolInvocationRecord> listToolInvocations(UUID turnId);
}
