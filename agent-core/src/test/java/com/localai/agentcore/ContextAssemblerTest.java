package com.localai.agentcore;

import com.localai.contracts.MessageRecord;
import com.localai.contracts.MessageRole;
import com.localai.contracts.ContextSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAssemblerTest {
    private final ContextAssembler assembler = new ContextAssembler();

    @Test
    void includesNewestMessagesWithinBudgetAndRecordsTheirProvenance() {
        UUID conversationId = UUID.randomUUID();
        MessageRecord old = message(conversationId, MessageRole.USER, "older context that should be excluded");
        MessageRecord assistant = message(conversationId, MessageRole.ASSISTANT, "recent answer");
        MessageRecord current = message(conversationId, MessageRole.USER, "current question");

        ContextAssembly result = assembler.assemble(
                UUID.randomUUID(), "model-x", List.of(old, assistant, current), current.id(), 150);

        assertTrue(result.prompt().startsWith("System: You are a general-purpose assistant."));
        assertTrue(result.prompt().contains("in concise, easy-to-understand language"));
        assertTrue(!result.prompt().contains("older context"));
        assertTrue(result.prompt().contains("Assistant: recent answer"));
        assertTrue(result.prompt().contains("User: current question"));
        assertEquals(List.of(assistant.id(), current.id()), result.manifest().selectedMessageIds());
        assertEquals("conversation-v2", result.manifest().templateVersion());
        assertTrue(result.manifest().estimatedTokens() <= result.manifest().tokenBudget());
    }

    @Test
    void rejectsCurrentMessageWhenItCannotFitInsteadOfSilentlyTruncating() {
        UUID conversationId = UUID.randomUUID();
        MessageRecord current = message(conversationId, MessageRole.USER, "a message too large");

        assertThrows(ContextBudgetExceededException.class,
                () -> assembler.assemble(UUID.randomUUID(), "model-x", List.of(current), current.id(), 1));
    }

    @Test
    void includesOlderSourcedSummaryOnlyWhenItsProvenancePrecedesSelectedMessages() {
        UUID conversationId = UUID.randomUUID();
        MessageRecord sourceOne = message(conversationId, MessageRole.USER, "older source content " + "x".repeat(80));
        MessageRecord sourceTwo = message(conversationId, MessageRole.ASSISTANT, "older response content " + "y".repeat(80));
        MessageRecord current = message(conversationId, MessageRole.USER, "now");
        ContextSummary summary = new ContextSummary(
                UUID.randomUUID(),
                conversationId,
                "The user is working on a local assistant.",
                List.of(sourceOne.id(), sourceTwo.id()),
                "model-x",
                Instant.now());

        ContextAssembly result = assembler.assemble(
                UUID.randomUUID(),
                "model-x",
                List.of(sourceOne, sourceTwo, current),
                List.of(summary),
                current.id(),
                160);

        assertTrue(result.prompt().contains("[Untrusted conversation summary"));
        assertTrue(result.prompt().contains("The user is working on a local assistant."));
        assertEquals(List.of(summary.id()), result.manifest().selectedSummaryIds());
        assertEquals(List.of(current.id()), result.manifest().selectedMessageIds());
        assertTrue(result.manifest().estimatedTokens() <= result.manifest().tokenBudget());
    }

    private MessageRecord message(UUID conversationId, MessageRole role, String content) {
        return new MessageRecord(UUID.randomUUID(), conversationId, role, content, Instant.now());
    }
}
