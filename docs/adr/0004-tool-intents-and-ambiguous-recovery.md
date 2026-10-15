# ADR 0004: Durable tool intents and ambiguous recovery

**Status:** Accepted

**Context:** A future tool operation may cause a side effect that SQLite cannot atomically commit with
its result. If the process crashes after execution begins, automatically retrying could duplicate an
external effect. Tool intent and progress must therefore be durable before any executor is introduced.

**Decision:** SQLite migration V9 adds a turn-scoped tool invocation table. An intent stores a stable
invocation ID, per-turn idempotency key, bounded operation identifier, bounded required-permission names,
side-effect classification, status, and timestamps. It deliberately does not store raw tool arguments.
Reusing the idempotency key with the same operation/policy returns the existing invocation; changing the
intent under the same key is rejected. The state path is `INTENT_RECORDED` -> `AUTHORIZED` or `DENIED`;
only an authorized invocation belonging to a still-running turn can transition to `EXECUTING`, followed
by `SUCCEEDED` or `FAILED`.

At startup, authorized or unstarted intents are marked `INTERRUPTED`. An in-flight read-only operation is
also marked `INTERRUPTED`. An in-flight side-effecting operation becomes `NEEDS_REVIEW`, and its turn is
atomically reconciled to `NEEDS_REVIEW` with a durable `turn.needs_review` event. The ordinary interruption
event remains for turns without ambiguous side effects. The UI surfaces the review status and does not
retry the operation.

**Consequences:** This is a storage and recovery boundary only, not a Capability Broker or executor.
No tool invocation endpoint or execution path is exposed. OS-enforced authorization, approval records,
argument scoping, result artifacts, and a user-directed reconciliation workflow remain prerequisites for
enabling tools. Recovery status reports uncertainty and cannot prove whether an external effect happened.
