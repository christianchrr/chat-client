package com.localai.apihost;

import com.localai.contracts.StreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnEventBrokerTest {

    @Test
    void rejectsReplayWhenRequestedEventsHaveBeenEvicted() throws Exception {
        TurnEventBroker broker = new TurnEventBroker();
        UUID turnId = UUID.randomUUID();
        broker.create(turnId);
        for (int sequence = 0; sequence < 2_048; sequence++) {
            broker.publish(new StreamEvent(turnId + ":" + sequence, turnId, Instant.now(),
                    "token.delta", sequence, Map.of("text", "token"), 1));
        }
        broker.publish(new StreamEvent(turnId + ":2048", turnId, Instant.now(),
                "turn.completed", 2_048, Map.of(), 1));

        assertThrows(TurnEventBroker.EventHistoryExpiredException.class,
                () -> broker.subscribe(turnId, null));
        var replay = broker.subscribe(turnId, turnId + ":0");
        org.junit.jupiter.api.Assertions.assertEquals(1, replay.next(java.time.Duration.ZERO).sequence());
    }

    @Test
    void rejectsCursorThatBelongsToAnotherTurn() {
        TurnEventBroker broker = new TurnEventBroker();
        UUID turnId = UUID.randomUUID();
        broker.create(turnId);

        assertThrows(IllegalArgumentException.class,
                () -> broker.subscribe(turnId, UUID.randomUUID() + ":0"));
    }

    @Test
    void needsReviewIsATerminalTurnEvent() throws Exception {
        TurnEventBroker broker = new TurnEventBroker();
        UUID turnId = UUID.randomUUID();
        broker.create(turnId);
        broker.publish(new StreamEvent(
                turnId + ":0", turnId, Instant.now(), "turn.needs_review", 0,
                Map.of("message", "Review before retry"), 1));

        var subscription = broker.subscribe(turnId, null);

        org.junit.jupiter.api.Assertions.assertEquals(
                "turn.needs_review", subscription.next(java.time.Duration.ZERO).type());
        org.junit.jupiter.api.Assertions.assertTrue(subscription.isCompleted());
    }
}
