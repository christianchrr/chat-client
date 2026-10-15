# ADR 0003: Sourced context summaries

**Status:** Accepted

**Context:** Conversation history remains immutable source material, while a model context is bounded.
Older messages may need a derived summary, but summaries can be lossy or reproduce untrusted instructions.
Every summary used for inference therefore needs same-conversation provenance and explicit budget accounting.

**Decision:** Summary generation is explicit and accepts between two and 200 message IDs from one
conversation. The API serializes source messages as JSON, bounds total source text, asks the configured
local model for factual context, and stores the resulting text with its model ID, timestamp, and exact
source message IDs. SQLite verifies all source IDs belong to the summary conversation. Summaries can be
listed or deleted without changing original messages. During assembly, a summary is eligible only when
all its source messages precede the selected recent-message window and it fits the remaining estimated
budget. Its ID is included in the durable context manifest, and its text is labeled as untrusted context.

**Consequences:** Summaries are opt-in derived records and are not silently substituted for conversation
history. Deleted summaries are no longer available to future context assembly; existing turn manifests
continue to document the IDs used at inference time. The 4-code-point-per-token estimator remains
provisional, and prompt labels do not enforce authorization or prevent prompt injection; privileged tools
remain disabled until the capability broker and OS enforcement gates are implemented.
