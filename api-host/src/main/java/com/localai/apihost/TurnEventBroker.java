package com.localai.apihost;

import com.localai.contracts.StreamEvent;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class TurnEventBroker {
    private static final int MAX_RETAINED_TURNS = 100;
    private static final int MAX_EVENTS_PER_TURN = 2_048;
    private static final int MAX_RETAINED_PAYLOAD_CHARACTERS_PER_TURN = 1_048_576;

    private final Map<UUID, TurnState> turns = new LinkedHashMap<>();

    public synchronized void create(UUID turnId) {
        if (turns.containsKey(turnId)) {
            throw new IllegalArgumentException("Turn already exists: " + turnId);
        }
        pruneCompletedTurns();
        if (turns.size() >= MAX_RETAINED_TURNS) {
            throw new CapacityExceededException("Too many active or retained turns");
        }
        turns.put(turnId, new TurnState());
    }

    public void publish(StreamEvent event) {
        TurnState state = stateFor(event.turnId());
        synchronized (state) {
            if (state.completed) {
                throw new IllegalStateException("Cannot publish an event after turn completion");
            }
            long lastSequence = state.events.isEmpty()
                    ? -1
                    : state.events.getLast().sequence();
            if (event.sequence() <= lastSequence) {
                throw new IllegalArgumentException("Event sequence must increase monotonically");
            }
            int payloadCharacters = payloadCharacters(event);
            if (!isTerminal(event.type())
                    && state.retainedPayloadCharacters + payloadCharacters
                    > MAX_RETAINED_PAYLOAD_CHARACTERS_PER_TURN) {
                throw new EventCapacityExceededException("Turn event history exceeded its memory budget");
            }
            state.events.addLast(event);
            state.retainedPayloadCharacters += payloadCharacters;
            state.nextSequence = event.sequence() + 1;
            while (state.events.size() > MAX_EVENTS_PER_TURN) {
                state.retainedPayloadCharacters -= payloadCharacters(state.events.removeFirst());
            }
            state.completed = isTerminal(event.type());
            state.notifyAll();
        }
    }

    public Subscription subscribe(UUID turnId, String lastEventId) {
        TurnState state = stateFor(turnId);
        synchronized (state) {
            long lastSequence = parseLastSequence(turnId, lastEventId);
            long earliestSequence = state.events.isEmpty()
                    ? state.nextSequence
                    : state.events.getFirst().sequence();
            if (lastSequence < earliestSequence - 1) {
                throw new EventHistoryExpiredException("Requested event history is no longer retained");
            }
            if (lastSequence >= state.nextSequence) {
                throw new IllegalArgumentException("Last-Event-ID is ahead of the turn event stream");
            }
            return new Subscription(state, lastSequence);
        }
    }

    private synchronized TurnState stateFor(UUID turnId) {
        TurnState state = turns.get(turnId);
        if (state == null) {
            throw new TurnNotFoundException(turnId);
        }
        return state;
    }

    private synchronized void pruneCompletedTurns() {
        if (turns.size() < MAX_RETAINED_TURNS) {
            return;
        }
        Iterator<Map.Entry<UUID, TurnState>> iterator = turns.entrySet().iterator();
        while (turns.size() >= MAX_RETAINED_TURNS && iterator.hasNext()) {
            Map.Entry<UUID, TurnState> entry = iterator.next();
            synchronized (entry.getValue()) {
                if (entry.getValue().completed) {
                    iterator.remove();
                }
            }
        }
    }

    private long parseLastSequence(UUID turnId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1;
        }
        String prefix = turnId + ":";
        if (!lastEventId.startsWith(prefix)) {
            throw new IllegalArgumentException("Last-Event-ID does not belong to this turn");
        }
        try {
            long sequence = Long.parseLong(lastEventId.substring(prefix.length()));
            if (sequence < 0) {
                throw new NumberFormatException("negative sequence");
            }
            return sequence;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Last-Event-ID has an invalid sequence", e);
        }
    }

    private boolean isTerminal(String type) {
        return "turn.completed".equals(type)
                || "turn.failed".equals(type)
                || "turn.cancelled".equals(type)
                || "turn.interrupted".equals(type)
                || "turn.needs_review".equals(type);
    }

    private int payloadCharacters(StreamEvent event) {
        return event.payload().entrySet().stream()
                .mapToInt(entry -> entry.getKey().length() + entry.getValue().length())
                .sum();
    }

    private static final class TurnState {
        private final ArrayDeque<StreamEvent> events = new ArrayDeque<>();
        private long nextSequence;
        private int retainedPayloadCharacters;
        private boolean completed;
    }

    public static final class Subscription {
        private final TurnState state;
        private long lastSequence;

        private Subscription(TurnState state, long lastSequence) {
            this.state = state;
            this.lastSequence = lastSequence;
        }

        public StreamEvent next(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (state) {
                while (true) {
                    long earliestSequence = state.events.isEmpty()
                            ? state.nextSequence
                            : state.events.getFirst().sequence();
                    if (lastSequence < earliestSequence - 1) {
                        throw new EventHistoryExpiredException("Requested event history is no longer retained");
                    }
                    for (StreamEvent event : state.events) {
                        if (event.sequence() > lastSequence) {
                            lastSequence = event.sequence();
                            return event;
                        }
                    }
                    if (state.completed) {
                        return null;
                    }
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return null;
                    }
                    long millis = Math.max(1, remainingNanos / 1_000_000);
                    state.wait(millis);
                }
            }
        }

        public boolean isCompleted() {
            synchronized (state) {
                return state.completed && lastSequence >= state.nextSequence - 1;
            }
        }
    }

    public static final class TurnNotFoundException extends RuntimeException {
        public TurnNotFoundException(UUID turnId) {
            super("Turn not found: " + turnId);
        }
    }

    public static final class EventHistoryExpiredException extends RuntimeException {
        public EventHistoryExpiredException(String message) {
            super(message);
        }
    }

    public static final class CapacityExceededException extends RuntimeException {
        public CapacityExceededException(String message) {
            super(message);
        }
    }

    public static final class EventCapacityExceededException extends RuntimeException {
        public EventCapacityExceededException(String message) {
            super(message);
        }
    }
}
