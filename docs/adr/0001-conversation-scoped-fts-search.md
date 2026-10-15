# ADR 0001: Conversation-scoped lexical message search

**Status:** Accepted

**Context:** M2 needs local retrieval without introducing a vector database or allowing one
conversation's history to leak into another. SQLite is already the local persistence layer.

**Decision:** Index message content with SQLite FTS5, maintain the index using database triggers,
and expose parameterized phrase search scoped to a conversation with a bounded result count.
Queries are treated as quoted text rather than raw FTS syntax. Existing messages are indexed by
the forward-only V2 migration.

**Consequences:** Search is local, lexical, and provenance-preserving because results are original
message records. It does not yet perform context budgeting, summarization, ranking calibration,
or retrieval authorization across workspaces. FTS5 availability is required from the bundled SQLite
driver; migration and search tests verify that runtime capability.
