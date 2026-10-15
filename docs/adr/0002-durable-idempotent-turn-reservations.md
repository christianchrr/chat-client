# ADR 0002: Durable idempotent turn reservations

**Status:** Accepted

**Context:** A client retry must not create a second persisted user turn or trigger model generation
again. A process restart must leave enough durable state to distinguish a completed turn from work
that was interrupted, and cancellation must be recorded before the API acknowledges it.

**Decision:** SQLite migration V3 adds a turn table with a unique `(conversation_id, client_request_id)`
key, content hash, user-message reference, status and timestamps. Reserving a turn atomically stores its
user message and `RUNNING` status before inference. Completion atomically stores the assistant message
and terminal status. Duplicate submissions with the same key and content attach to retained in-memory
events when possible; otherwise they receive a conflict containing the existing turn ID and status.
Reusing a key with different content is rejected. On backend initialization, turns left `RUNNING` are
marked `INTERRUPTED` and are never automatically regenerated.
Cancellation first changes `RUNNING` to `CANCEL_REQUESTED` in SQLite; only then does the API
acknowledge and interrupt the worker. A request still pending at startup is reconciled to `INTERRUPTED`.
The authenticated `GET /api/v1/turns/{turnId}` endpoint exposes the durable status without exposing
the request hash or idempotency key.

**Consequences:** Completed and incomplete turn identity survives restart, and repeated submissions
cannot silently execute the inference twice. Cancellation acknowledgment represents a persisted
request, not proof the provider stopped instantly. `turn.started`, terminal lifecycle events and
recovery's `turn.interrupted` event are durable and replayable. Token deltas remain bounded and
in-memory; a restart can therefore replay lifecycle state but not reproduce already-streamed partial
text. Sequence numbers are reserved in blocks of 256 to avoid a database write for each token while
allowing recovery events to follow all possibly-issued IDs. Interrupted runs are not automatically
resumed. Context checkpoints and ambiguous external side-effect reconciliation remain subsequent M2 work.
