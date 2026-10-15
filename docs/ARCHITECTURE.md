# Local-First AI Assistant — Complete Engineering Specification v3.4

**Status:** Authoritative architecture and Copilot implementation guide; unresolved decisions and validation gates are explicitly marked.  
**Updated:** October 12, 2026 · Revision 3.4  
**Audience:** IntelliJ GitHub Copilot, engineering, QA, security, and release teams.

> **Copilot priority:** v3.3 sections 28–37 extend and supersede conflicting prior guidance. The latest release gates and milestones control. Treat sections 1–27 as retained baseline unless superseded. Inspect the repository before changing code.

## Executive summary

This is the implementation source of truth for the Local-First AI Assistant v3.2. Build a native Windows desktop product with one user-facing installer and one launcher-managed application. JavaFX is the UI; a local Spring Boot API host wraps a framework-independent Java agent core; native Ollama is the initial replaceable inference runtime. Docker and WSL2 are NOT required. Model discovery is open, not limited to Qwen or a curated whitelist. Every model must pass capability, hardware, resource, disk, and runtime checks before installation and execution. The product is privacy-first, quality-first, security-conscious, and designed for future alternate providers, clients, and server deployments.

DECISION STATUS: architectural baseline, not proof of implementation. Windows sandboxing, redistributable runtime licenses, GPU dependencies, and packaging must be validated experimentally before production release.

COPILOT RULE: preserve these boundaries; implement one milestone at a time; do not replace native deployment with Docker or move domain logic into controllers.

PERSISTENCE IS MANDATORY: SQLite stores durable conversations, turns, agent runs, checkpoints, approval records, model metadata, and operational events. File-backed artifact storage retains large attachments and generated outputs. Context assembly is budgeted, summarized, and traceable. Recovery is state-based and must not blindly replay side effects.

## 1. Product requirements and non-goals

MUST: Windows 11 initial target; one signed Setup.exe; no user-managed Java, Python, Docker Desktop, WSL2, Ollama installation or terminal commands; bundle application/JVM/native runtime where redistribution permits; automatically provision model files or offer an offline package; first-run setup with visible progress and recovery; dedicated terminal-style JavaFX window; streamed responses; local-only inference by default; model choice among any supported catalog/imported models within hardware and runtime limits; explicit approvals for privileged tools; no telemetry by default; durable conversation and model data; clean uninstall and updates.

SHOULD: resumable downloads; recommended quality-first models; import GGUF/custom compatible models; accessible progress and error screens; graceful CPU fallback; model swap without restart; cross-platform and remote-backend readiness.

NON-GOALS FOR V1: training models, building a new inference engine, hosting arbitrary public internet endpoints, distributed microservices, guaranteed execution of every catalog model, perfect GPU VRAM quotas, and automatically granting unrestricted shell/file access.

ONE EXE CLARIFICATION: one installer and one launcher for the customer, not one operating-system process. Models may require tens of gigabytes; online first-run download is acceptable if automated, while a fully offline package is a separate large distribution.

## 2. Reference architecture and trust boundaries

INSTALLER -> native launcher (process supervisor) -> JavaFX desktop + Spring Boot API host + native Ollama runtime. JavaFX communicates only with a versioned API over authenticated loopback. API host calls framework-independent agent-core interfaces. Agent core depends on inference, tool, storage, policy, and model-management ports; adapters implement those ports. Inference runtime is separate from tool workers. The launcher owns lifecycle; the agent core must never own Windows processes or installers.

Trust boundaries: [untrusted model output / retrieved documents / MCP server responses] -> policy broker -> [restricted tool worker]; [desktop UI] -> authenticated API -> [agent]; [agent] -> provider adapter -> [local inference]; [updater / download source] -> signature/hash verification -> installed artifacts. Treat tool outputs and model output as untrusted. Localhost does not equal authenticated.

Initial processes: JavaFX launcher/UI (may be same process), Spring Boot backend process, native Ollama process; add isolated worker processes when tool execution is enabled. Process count can vary without changing user-facing application count.

## 3. Repository and module map

Use one Maven reactor repository with enforceable dependency direction. Suggested layout:

ai-assistant/

  pom.xml

  contracts/          API DTOs, event schemas, versioning (no Spring)

  agent-core/         state machine, conversation orchestration, policy-facing ports (plain Java)

  inference-spi/      ModelProvider, capabilities, inference requests/events

  inference-ollama/   Ollama HTTP adapter, pull/list/show, runtime mapping

  model-management/   catalog, profiler, estimator, admission, downloader, lifecycle

  tool-spi/           tool contracts, permissions, broker interfaces

  tool-worker/        sandboxed process entrypoint and OS adapters

  storage-spi/        repository interfaces, migrations contract

  storage-sqlite/     local database adapter

  api-host/           Spring Boot REST/SSE, auth, DI, health

  desktop/            JavaFX UI and API client

  launcher/           install discovery, process supervision, health, updates

  packaging/          jpackage, signing, installer resources, manifests

  architecture-tests/ ArchUnit and contract tests

For MVP, some small SPI modules may be merged to reduce overhead, but logical dependency rules must remain. Dependency rule: agent-core -> interfaces/contracts only; adapters -> SPIs; api-host -> agent-core + adapters via composition; desktop -> contracts/API client only; launcher -> OS/process infrastructure only. No desktop dependency on Spring or Ollama. No agent-core dependency on Spring AI, JavaFX, HTTP clients, SQLite, or Ollama DTOs.

Spring AI is optional inside adapters/host; domain logic must not require it. Use constructor injection and explicit immutable DTOs.

## 4. API and streaming contracts

API is versioned /api/v1 and bound by default to 127.0.0.1 with a randomly chosen available port. Launcher creates a short-lived local credential, passes it securely to client/backend (avoid command-line args and world-readable files), verifies backend readiness, and rejects unauthenticated requests. Add Origin/Host validation, CSRF strategy appropriate to transport, request-size limits, and never expose wildcard CORS. Plan secure credential rotation and multi-instance collision handling.

Endpoints (illustrative contract, freeze in milestone M1): GET /health/live, GET /health/ready, GET /api/v1/capabilities; POST /api/v1/conversations; POST /api/v1/conversations/{id}/turns; GET /api/v1/turns/{id}/events (SSE with event IDs/reconnect); POST /api/v1/turns/{id}/cancel; GET /api/v1/models; GET /api/v1/models/{id}/compatibility; POST /api/v1/models/{id}/install; GET /api/v1/downloads/{id}/events; POST /api/v1/models/{id}/load; GET /api/v1/hardware; POST /api/v1/tool-approvals/{id}/decision.

Streaming event envelope: eventId, turnId, timestamp, type, sequence, payload, schemaVersion. Types: turn.started, token.delta, tool.approval_requested, tool.started, tool.finished, turn.completed, turn.failed, turn.cancelled, usage.updated. SSE stream ordering and replay are per turn. Never confuse a generated text token with a tool execution instruction. Cancellation propagates through agent -> provider -> runtime where supported; cancellation may be best effort.

Use stable model identifiers: providerId + registry namespace + artifact digest/version + quantization, not only a mutable display tag.

## 5. Agent core behavior and quality

Define a turn state machine: CREATED -> RUNNING -> WAITING_FOR_APPROVAL -> RUNNING -> COMPLETED / FAILED / CANCELLED. Persist transitions and idempotency keys. Support prompt assembly, context budgeting, tool request validation, retries with bounds, streaming, cancellation, error classification, and structured tool results. Forbid automatic execution of commands embedded in retrieved text or model output.

Quality-first is the default selection policy. Evaluate models on representative tasks (coding, reasoning, tool calling, structured output, long-context, multilingual where applicable), not parameter count alone. Provide Best Quality, Balanced, Fast profiles, with hardware and privacy constraints enforced in every profile. User-selected models take precedence when safe and capable. When capability is missing, explain limitations rather than silently inventing tool support.

Keep orchestration deterministic where possible; record provider/model identity and relevant settings with each turn for reproducibility. Avoid promising perfect determinism.

## 6. Provider abstraction and open model catalog

ModelProvider capabilities should cover: enumerate installed models, inspect metadata, install/pull (when supported), delete/unload, load, stream inference, cancel, token/context limits, tool calling, structured output, vision/audio/embedding flags, quantization, concurrent sessions, hardware/backend requirements, and usage metrics. Not every provider implements every operation; return explicit unsupported capabilities. Separate InferenceProvider from ModelArtifactSource and ModelRuntimeController to avoid an Ollama-shaped universal interface.

Catalog tiers: (A) curated and benchmarked recommended entries; (B) broader provider-discovered registry entries; (C) user-supplied model identifiers or compatible local imports. Do not hardcode a 5-10 model limit or restrict to Qwen. Catalog discovery can include any provider-supported model, but listing is not a promise of installability. Verify model licensing, provenance, formats, architectures, tokenizer compatibility, supported runtime versions, and metadata. Some models require authentication or acceptance of licenses; the application must not silently bypass those steps.

Support multiple quantizations/variants as separate installable artifacts. Make exact version/digest visible; avoid silently replacing installed weights on mutable tag updates. Future adapters may include llama.cpp, vLLM (often server/Linux oriented), and optional cloud providers. Remote inference requires explicit opt-in and privacy notice.

## 7. Hardware-aware model manager: required components

ModelCatalog: merges curated metadata, provider registry, and user imports; deduplicates identities.

HardwareProfiler: GPU vendor/model, supported acceleration backends, dedicated VRAM, free VRAM where available, CPU features, total/available RAM, disk free space, OS, driver/runtime compatibility, battery/thermal signals where available. Use reliable OS/vendor APIs and runtime probing; unsupported metrics must be marked unknown rather than guessed.

CompatibilityEvaluator: checks architecture/format/runtime support, quantization, context and capability requirements, licenses, disk, expected RAM/VRAM. Produces evidence and explanations, not a single unqualified boolean.

ResourceBudgetManager: applies configurable safety headroom and admission control across all running models, context caches, parallel requests, and background workloads.

ModelDownloader: streams progress, supports resume where upstream permits, verifies checksums/digests, writes atomically, handles corruption/low disk and cancellations.

ModelLifecycleManager: install, load, unload, swap, delete, repair, cleanup, and restart; does not erase models during app updates.

RuntimeMetricsCollector: samples measured resident memory, GPU use when supported, runtime OOM/error events, throughput, load time, and actual context allocation. Feed measurements back into estimates; do not assume fixed overhead.

## 8. Compatibility policy and resource safety

Four outcomes: RECOMMENDED (adequate verified headroom), COMPATIBLE_SLOWER (CPU offload or reduced context with expected performance impact), RESOURCE_CONSTRAINED (near limits; require user-visible remediation and conservative admission), UNSUPPORTED (missing required runtime/hardware or unacceptably high estimated memory; block launch). Unknown metadata is NOT equivalent to safe.

Before download: check trusted source, license, storage estimate including temporary extraction/download files, runtime support, and disk headroom. Downloading a large but un-runnable model can be allowed as an advanced user choice only with a clear warning; launching must still pass admission checks.

Before load: estimate weights resident on CPU/GPU, KV cache from context and concurrency, runtime scratch/graph buffers, existing loaded models, Windows and user workload reserve. Prefer runtime-reported memory planning and measured telemetry over simplistic weight-size formulas. Use conservative configurable budgets, not universal GB thresholds. Re-evaluate at load time, not only at catalog browsing.

During execution: cap parallelism and context, serialize heavy model loads where needed, monitor pressure, unload idle models, propagate cancellation, and handle out-of-memory as recoverable failure with suggestions. Where supported, use Windows Job Objects for CPU/process memory controls and process lifecycle; do not claim they impose a hard GPU VRAM cap. Windows and third-party GPU drivers may still OOM or reset. Never promise absolute protection of system stability.

Allow any model supported by a provider that passes checks, including non-Qwen families and user-imported models. Provide actionable alternatives: smaller quantization, shorter context, CPU offload, unload other models, or choose a different model.

## 9. Model downloads and storage

V1 native Ollama adapter calls Ollama's local HTTP API for model list/show/pull/delete, with streamed progress translated to application download events. Pin and validate supported Ollama version; verify upstream API behavior during implementation. Ollama's internal blob storage is runtime-owned; do not modify it manually. Configure a per-user persistent model root via supported runtime settings (for Ollama, OLLAMA_MODELS) and keep it outside the installation directory.

Suggested paths: %LOCALAPPDATA%\LocalAIAssistant\data\assistant.db; %LOCALAPPDATA%\LocalAIAssistant\models\ollama; %LOCALAPPDATA%\LocalAIAssistant\logs; %LOCALAPPDATA%\LocalAIAssistant\config. Use Windows ACLs, atomic writes, encryption for secrets using OS facilities, and explicit migration/backup policy. Avoid logging prompts/secrets by default.

Installer may bundle executable runtimes and a bundled JVM; model weights download automatically after first launch unless included in a separate offline distribution. Validate runtime redistribution rights, model licenses, signing, GPU DLL distribution terms, and export/compliance issues before shipping. On download failure, show resume/retry, disk space needed, and a non-destructive rollback. User can change model storage location with a supported migration flow.

## 10. Native installer and launcher lifecycle

Use a Windows installer generated via jpackage and/or an appropriate native installer tool; jpackage alone may not implement every multi-process/update requirement. Package JavaFX client, backend JAR, a compatible Java runtime, launcher, native inference runtime where licensed, manifests, and required assets. The EXE installer may expand to multiple installed files/processes; user sees one product.

Launcher responsibilities: single-instance lock; runtime integrity check; choose secure ports; initialize credentials; start native inference server with loopback-only binding and controlled data directory; wait for health; start backend with explicit config; wait for authenticated readiness; open JavaFX; supervise restarts with exponential backoff and crash-loop limits; cleanly terminate owned processes without killing unrelated user Ollama instances; release locks; preserve models and conversations.

Detect already-running third-party Ollama and avoid attaching to it blindly. Prefer managed private runtime instance with distinct port/data directory and process identity. Do not rely on system-wide PATH or administrator rights for normal use. If elevation or driver changes are unavoidable, request consent and explain why; never silently modify firmware/drivers.

Updates: signed manifests/artifacts, pinned dependency versions, staged downloads, integrity checks, atomic replacement/rollback, data schema migrations and backups, compatible backend/client protocol versions. Offline use must remain functional after installation when no external integrations are enabled.

## 11. Security model and OS-enforced tool isolation

Threats: malicious prompts, prompt injection in repository files/web/MCP output, exfiltration via tools, unauthorized file edits, arbitrary command execution, localhost API abuse, malicious model artifacts, compromised update endpoints, path traversal, symlink/junction escape, resource exhaustion, credential leakage, and untrusted plugins.

Rules: deny by default; user-approved workspaces and operations; separate read/write/execute/network grants; require confirmation for destructive commands, external network calls, secret access, and changes outside workspace; show exact tool arguments and target paths; record auditable approvals. Never treat model-generated instructions as authorization. Protect against TOCTOU, symlink/junction escapes, path canonicalization errors, and command argument injection.

A separate worker process is not a sandbox. Prototype and test Windows restricted tokens/AppContainer or another OS-enforced isolation approach; verify filesystem/network restrictions, child process escape resistance, and Java/MCP compatibility. Job Objects help with process trees/resource controls but do not replace access-control sandboxing. Inference engine needs GPU access and must NOT share the untrusted tool worker sandbox. If OS sandboxing is not demonstrably enforceable, ship tool execution disabled or require narrow explicit permissions rather than claiming strong isolation.

Bind services to loopback, authenticate all privileged endpoints, use OS-protected secrets, verify updater signatures, restrict runtime executable paths, and maintain dependency vulnerability scanning. MCP servers are untrusted by default; require per-server permissions, transport authentication, and strict tool schemas.

## 12. Data persistence, privacy and observability

SQLite is the default local store for conversations, turns, model metadata, task states, and approval audit records; use transactional migrations, WAL as appropriate, retention/deletion controls, backup/export, and crash recovery. Do not store model weights in SQLite. Keep binary models in managed runtime storage.

Privacy policy: prompts and outputs stay local during local inference; model download/update checks and explicitly enabled web/MCP/cloud tools can contact external services. Make those egress points visible. Default telemetry OFF; diagnostics opt-in and scrubbed. No assumption that local = offline during first-run model download. Credentials stored with Windows DPAPI/Credential Manager or equivalent, never plaintext in logs.

Observability: structured local logs with request/turn IDs, model/version, lifecycle transitions, timings, and sanitized error codes. Include user-visible diagnostics export with consent and redaction. Define data deletion/uninstall options separately from application binary removal.

## 13. Deployment growth and future scalability

Deployment A (V1): native JavaFX + local API host + local Ollama, one installer, one user account. Deployment B: desktop + authenticated remote API host. Deployment C: headless backend + dedicated inference server. Deployment D: multiple providers/local-cloud hybrid with explicit opt-in. Deployment E: multi-user service with independent identity, quotas, distributed scheduling, and durable work queues.

To enable growth: keep versioned API and provider contracts; separate per-user state from agent core; use cancellation and idempotency; represent jobs with durable IDs; avoid global singleton model state; allow model runtime discovery via configuration; treat remote auth/transport as a separate adapter; maintain schema migrations and backward compatibility. Do NOT prematurely add Kubernetes, message brokers, or microservices. Local MVP does not magically scale to thousands of users; concurrency, tenancy, billing, scheduling, and security require later engineering.

## 14. Testing, performance and release gates

Architecture: ArchUnit or equivalent enforces no agent-core dependency on Spring/JavaFX/Ollama/SQLite, and no desktop direct provider access. Contract: versioned JSON DTO fixtures, SSE reconnect/order, auth failure, cancellation, capability negotiation, API compatibility. Integration: native Ollama with multiple non-Qwen model families and quantizations; install/pull/delete/restart; corrupted and interrupted downloads; model registry metadata mismatch.

Hardware matrix: NVIDIA GPU, AMD/Intel where supported, CPU-only, 8/16/32/64 GB RAM tiers, limited disk, driver mismatches, low-memory pressure, multi-model load, large context, concurrency. Validate quality and performance on representative tasks. Security: sandbox escape attempts, junction/symlink traversal, malicious MCP, prompt injection, unauthorized network egress, localhost API misuse, tampered updates, malicious model manifests.

Installer: clean Windows VM with no Java/Ollama/Docker/WSL2; non-admin install where feasible; install->first-run->download->chat->relaunch->update->rollback->uninstall; offline relaunch; crash recovery; no accidental deletion of model data. Release blocked until native sandbox security claims and license/redistribution checks are verified. Define SLOs for startup, download recovery, memory headroom, crash rate, and model load reliability from measured benchmarks, not guessed targets.

## 15. Milestones with exit criteria

M0 — Greenfield foundation: initialize an empty Git repository and Maven reactor, scaffold JavaFX and Spring Boot modules, create agent-core and SPI, add dependency tests. EXIT: full build succeeds and agent-core compiles without Spring/JavaFX/Ollama.

M1 — End-to-end local chat: versioned loopback API with auth, JavaFX streaming, turn cancellation, native Ollama integration. EXIT: clean dev launch, stream/cancel/recover, no Docker dependency.

M2 — Model management: catalog discovery beyond Qwen, hardware profiler, compatibility statuses, conservative admission, pull progress, persistent model storage, load/unload. EXIT: install/switch at least two distinct model families; block known-unsafe loads with explanations; handle unknown telemetry safely.

M3 — Native packaging: launcher supervision, bundled JVM/runtime, first-run setup, signed installer, update and rollback. EXIT: fresh Windows VM without manual prerequisites installs and runs.

M4 — Secure tools: permission broker, workspace policy, OS-enforced restricted worker, MCP integration, adversarial tests. EXIT: demonstrate actual OS-denied unauthorized file/network/child-process actions; otherwise keep risky tools disabled.

M5 — Quality/reliability: benchmark suite, migration tests, hardware matrix, diagnostics, recovery, release gates. EXIT: documented evidence for compatibility, licensing, stability and security.

Every milestone should include tests, a brief architecture decision record, and an updated README; do not implement all milestones in one Copilot prompt.

## 16. Explicit decisions, open questions and risks

DECIDED: Java-first; JavaFX desktop; Spring Boot API host; framework-independent agent core; native inference; Ollama initially; no Docker/WSL2 requirement; one installer; modular monolith; provider-agnostic model catalog; quality-first selection; conservative resource admission; tool isolation as security boundary; persistent user data.

OPEN: exact Java/JDK/JavaFX/Spring versions; chosen installer/updater; supported Ollama native distribution and license terms; signing certificates; native Windows sandbox implementation; GPU profiling libraries and driver requirements; catalog metadata provenance; model-specific benchmark thresholds; fallback behavior on insufficient hardware; offline package size; product name and legal notices. Do not silently assume these are settled.

RISKS: runtime redistribution rights, 20+ GB model downloads, variable GPU/CPU performance, Windows AV false positives, driver support, API compatibility, user model license restrictions, inference runtime security, failure to sandbox arbitrary tools, GPU memory OOM. Each risk requires an owner, experiment, evidence, and mitigation before release.

## 17. Copilot implementation operating instructions

Treat this document as product requirements and architectural constraints for a NEW project with no existing source code. Initialize the repository from scratch. Do not assume a working frontend, backend, model adapter, or database exists. Do not introduce Docker, require WSL2, or switch languages without explicit approval. Propose the smallest next change with files touched, interfaces, tests, and verification commands. Implement one milestone/vertical slice at a time; ask before broad refactors.

Never couple agent-core to Spring Boot or Ollama DTOs. Do not let JavaFX call Ollama directly. Never claim Windows process separation is a sandbox. Do not hardcode model names or fixed model-count limits. Use provider capabilities and hardware admission for every load. Unknown resource metrics require conservative handling. Do not run untrusted shell commands without an enforceable worker policy. Do not hardcode ports, tokens, absolute user paths, or secret values.

For every change: compile with Maven, run unit and architecture tests, show test results, describe security implications, and update API/ADR documentation if contracts changed. Use mocks for domain tests and opt-in native runtime integration tests. Avoid speculative abstractions beyond a clearly identified port. Preserve local-first defaults and quality-first model recommendations.

## 18. First Copilot prompt (copy into IntelliJ)

Read docs/ARCHITECTURE.md as the authoritative specification for a greenfield project. No JavaFX frontend, Spring Boot backend, or Maven project exists yet. Do not implement changes yet. Produce (1) an initial repository layout, (2) the Maven module dependency graph, (3) a minimal M0 scaffolding plan with ordered tasks and tests, (4) exact verification commands, and (5) any decisions you need me to make. Constraints: native Windows, no Docker/WSL2, one installer eventually, framework-independent agent-core, provider-agnostic inference, hardware-aware unrestricted model discovery with safe admission, and OS-enforced isolation for future tool execution. Do not assume every model fits every machine.

## Appendix A. Illustrative Java interfaces

interface InferenceProvider { String id(); ProviderCapabilities capabilities(); Flow.Publisher<InferenceEvent> stream(InferenceRequest request); CompletionStage<Void> cancel(String requestId); }

interface ModelCatalog { Stream<ModelDescriptor> discover(ModelQuery query); }

interface CompatibilityEvaluator { CompatibilityReport evaluate(ModelDescriptor model, HardwareSnapshot hardware, ResourceSnapshot resources, InferenceSettings settings); }

interface ModelLifecycleManager { CompletionStage<LoadResult> load(ModelIdentity id, InferenceSettings settings); CompletionStage<Void> unload(ModelIdentity id); }

interface PermissionBroker { CompletionStage<ApprovalDecision> authorize(ToolInvocation invocation, WorkspacePolicy policy); }

These are illustrative boundaries, NOT frozen production signatures. Add install/pull interfaces, typed failures, backpressure, security context, cancellation tokens, model capabilities, and telemetry as tests demand.

## Appendix B. Acceptance scenarios

Scenario 1: Fresh Windows 11 machine without Java/Ollama/Docker/WSL2 installs one EXE; launcher provisions native runtime, downloads selected model automatically, and opens JavaFX chat.

Scenario 2: User searches for a non-Qwen provider-supported model; application shows metadata, license, size, capabilities and compatibility, downloads it, and switches without rewriting agent core.

Scenario 3: User requests model exceeding memory; app explains estimates and alternatives, blocks unsafe load; a smaller quantization can proceed if verified compatible.

Scenario 4: Runtime crashes or download interrupts; UI reports failure and offers recovery without corrupting model store or deleting conversations.

Scenario 5: Malicious document requests shell command or network exfiltration; model output cannot bypass approval; OS sandbox denies unauthorized operations even if worker code attempts them.

Scenario 6: Offline relaunch after successful installation works with local model and no outbound network activity except explicitly enabled features.

Scenario 7: Backend can later run headless or remote by replacing host/deployment adapters, without copying or rewriting agent-core.

## 19. Persistence strategy and database decision (MANDATORY)

DECISION: SQLite is the default embedded, per-user database for the native desktop edition. It is bundled through a Java JDBC driver; the customer does not install or operate a database server. Store under %LOCALAPPDATA%\<Product>\data\assistant.db (product name configurable), not Program Files or the model directory. Use SQLite WAL mode, foreign keys, a busy timeout, short transactions, a single-writer strategy or serialized write executor, and prepared statements. Avoid sharing a live SQLite file over network shares. Use Flyway or equivalent versioned, tested migrations; never rely on Hibernate auto-DDL in production.

SEPARATE STORES: SQLite stores structured state, indexes, metadata, references, and small text. A content-addressed or UUID-keyed artifact directory stores large files, attachments, tool outputs, transcript exports, and optional embeddings indexes. Model weights remain managed by the inference runtime/model manager, outside SQLite. Secrets belong in Windows Credential Manager/DPAPI-protected storage, NOT plaintext DB. Logs are separately rotated and redacted.

MINIMUM TABLES: schema_migrations; conversations; messages; turns; runs; run_events; tool_invocations; tool_approvals; execution_checkpoints; model_installations; model_downloads; artifacts; workspace_grants; context_summaries; settings. Add provider/runtime metadata and a task lease table when headless or multi-worker execution is introduced. Use UUID/ULID IDs and UTC timestamps, explicit schema versions, indexed foreign keys, retention flags, and immutable event IDs. Avoid storing credentials, raw tokens, or unrestricted full environment snapshots.

SAMPLE RELATIONSHIPS: conversation 1:N messages; conversation 1:N turns; turn 1:N runs; run 1:N run_events and tool_invocations; tool_invocation 0..1 approval and 0..1 durable result; conversation 1:N context_summaries; artifacts linked by ID and reference counts. Record model identity, provider, settings, prompt-assembly version, context selection references, and tool-policy version per turn. Separate user text, assistant text, tool request, tool result, and system metadata by role/type.

WRITE DURABILITY: persist a user message and turn CREATED before acknowledging submission; persist a run event before exposing a durable completion state; tool invocation intent must be recorded before executing side effects; persist final outcome and artifact references in an idempotent transaction. Streamed tokens can be buffered and checkpointed in bounded batches; do not fsync every token. On crash, partial assistant text remains marked incomplete. Persist only authorized, redacted diagnostic information. Migrations must be forward-only with tested backup/rollback procedures and application/schema compatibility checks.

PRIVACY: local database and artifacts are user-controlled; define retention, deletion, export, and secure cleanup policies. OS-level disk encryption (e.g. BitLocker) and Windows account ACLs are recommended; SQLite alone is NOT encrypted at rest. If product promises encryption at rest, select and validate an encrypted SQLite distribution and key-management strategy rather than claiming built-in encryption. Avoid exposing local DB through the API; all reads pass authorization and workspace scope checks.

## 20. Conversation memory, context budgeting and retrieval

DECISION: A conversation is durable history in SQLite; the model context window is a temporary assembled prompt. These are NOT the same. The model does not remember prior turns automatically across requests or process restarts. The ContextAssembler reconstructs each request from a system/developer policy, current user turn, bounded recent dialogue, relevant summaries, explicit workspace/project facts, and approved retrieval results.

PIPELINE: (1) load current conversation and run state; (2) read model capabilities and actual context limit; (3) reserve output tokens, tool schemas, system instructions, and safety margin; (4) select recent messages; (5) retrieve relevant older material and summaries; (6) enforce provenance, token budget, permission scopes, and deduplication; (7) construct a traceable context manifest; (8) submit to provider. If the context budget is insufficient, summarize/truncate or request user intervention, never silently exceed model capacity.

MEMORY TIERS: short-term = recent message window; medium-term = rolling summaries and open task state; long-term = explicitly retained project facts, indexed documents, and user-approved preferences. Start with SQLite FTS5 for lexical retrieval if available, plus summaries and metadata. Vector embeddings are optional later, behind a RetrievalIndex port; do not require a vector database in MVP. Index only authorized content; respect deletion and workspace boundaries. Store summary source message ranges, generation model/version, timestamp, and invalidation status; do not let summaries overwrite original messages.

SUMMARIZATION: trigger on a measured token budget threshold or explicit checkpoint, not every message. Preserve outstanding tasks, user constraints, tool outcomes, file paths/revisions, and uncertainties. Treat summaries as lossy and potentially wrong; link back to source messages, prefer original evidence when available, and allow regeneration. Prompt-injection text in stored documents, summaries, or tool outputs remains untrusted data and never becomes system policy.

CONTEXT SAFETY: no secret retrieval without permission; no cross-conversation or cross-workspace leakage; retrieval results include provenance and access scope. Provide UI controls for viewing/deleting conversations, opting into persistent preferences, clearing project memory, exporting transcripts, and disabling long-term memory. Do not advertise unlimited context; stored history can be unlimited subject to storage quotas, but active model context is bounded.

## 21. Durable execution, crash recovery and exactly-once limitations

RUN STATE MACHINE: CREATED -> QUEUED -> RUNNING -> WAITING_FOR_APPROVAL -> RUNNING -> COMPLETED | FAILED | CANCELLED | INTERRUPTED | NEEDS_REVIEW. State transitions are validated and appended to run_events with monotonic per-run sequence and timestamps. Persist an execution checkpoint at safe boundaries (before tool execution, after tool result, after completed assistant turn). Model generation itself is not automatically resumable at exact token position unless a provider explicitly supports it.

RESTART RECONCILIATION: launcher restarts backend/runtime with bounded backoff; backend runs DB integrity/schema checks; locate runs left RUNNING/QUEUED; mark stale RUNNING as INTERRUPTED, reconcile known in-flight operations and artifacts, and offer safe resume/retry. Pending approvals remain pending but must be revalidated against current workspace, model request, and policy version. On reconnect JavaFX queries durable run state and replays SSE events from a persisted cursor, then resumes live updates. Event replay may be at-least-once: client deduplicates by event ID and sequence.

SIDE-EFFECT SAFETY: every tool invocation has a stable invocation ID, idempotency key, declared permissions, requested operation, authorization decision, and result status. For reads or pure calculations, retry may be safe. For writes, command execution, network sends, deletion, purchases, and other external side effects, do NOT blindly rerun after an ambiguous crash. If an action may have happened but result is unknown, mark NEEDS_REVIEW and ask the user to inspect or explicitly retry. Idempotency keys do not guarantee exactly-once effects in external systems unless the target system supports them. Where possible, stage file writes to a temp file, verify expected revision/hash, and atomically replace; keep backups/undo metadata and avoid following symlinks/junctions outside approved roots.

CONSISTENCY: SQLite transactions atomically commit DB rows, not filesystem or external tool effects. Use a transactional outbox/inbox pattern when adding remote services; for local MVP persist intent, execute, then persist result, with recovery reconciliation. Do not claim distributed transactions or guaranteed rollback of arbitrary shell commands. A run can be safely resumed only from a checkpoint whose subsequent effects are known or explicitly approved again.

FAILURE CASES TO TEST: power loss during token stream, runtime process crash, backend crash after file write but before result commit, installer update mid-run, interrupted model download, DB disk full, corrupted WAL, duplicate SSE reconnect, expired tool approval, user cancellation during inference, unavailable GPU. Ensure conversations remain readable and failures are visible, not silently lost.

## 22. Capability broker, sandboxing and workspace safety

MANDATORY TRUST RULE: model text and external content are untrusted. The model can propose a tool invocation, but cannot execute commands or access files directly. Agent core requests authorization from a CapabilityBroker with explicit actor, run, workspace, operation, target, arguments, network scope, and expiry. Deny by default. Approval is scoped to the exact operation or narrowly defined policy, audited in SQLite, and revocable. A confirmation dialog alone is not an OS security boundary.

WINDOWS ENFORCEMENT: prove restricted worker execution on target Windows builds using restricted access tokens/AppContainer where compatible, low-integrity execution, job objects for process tree termination and limits, filesystem ACLs, and explicit network restrictions. Each has limits; job objects are not filesystem sandboxes and Windows Firewall rules alone are not comprehensive application sandboxing. Never imply a Java SecurityManager provides modern OS sandboxing. Prefer a small audited native helper only if required, with signed binaries and tightly constrained IPC. If isolation cannot be demonstrated, disable arbitrary command execution and third-party executable plugins in V1.

PATH/PROCESS DEFENSES: canonicalize paths after resolving reparse points, junctions, symlinks and case normalization; protect against TOCTOU races and alternate data streams; prevent writes to startup folders, credential stores and sensitive OS paths. Restrict child process spawning and environment inheritance; strip secrets from worker environments; limit CPU, memory, wall-clock time, stdout/stderr size, and disk output. Use a network-deny default and explicit per-destination grants. MCP servers run with least privilege; remote MCP and web tools require separate disclosure and permissions.

SECURITY TEST GATE: create adversarial tests that attempt unauthorized file reads, path traversal, junction escapes, shell injection, credential exfiltration, child-process escape, network egress, prompt-injection induced tool calls, and bypass of approval prompts. A blocked attempt must be demonstrably denied by enforcement, not merely hidden by the UI. Threat model and test evidence are release artifacts.

## 23. Hardware-aware scheduler and model acquisition reliability

OPEN CATALOG: users may discover any model supported by an available inference provider, including non-Qwen models, imported compatible artifacts and variants. Catalog visibility is not a compatibility guarantee. Curated entries are recommendations, not a whitelist. Every install/load requires architecture, runtime, license, disk, and resource checks. Distinguish unsupported, unsafe, unknown, resource-constrained, slow-but-possible, and recommended. Explain each decision and alternatives.

RESOURCE BUDGET: hardware profiler measures GPU vendor/driver, total and currently available VRAM where supported, system RAM and committed usage, disk free space, CPU, OS, and concurrent workloads. Estimator includes weights, quantization, KV cache, context length, batch size, runtime overhead, parallel sessions, CPU offload, and safety headroom. Admission controller decides whether to load; resource scheduler serializes expensive model switches and bounds concurrent inference. Monitor pressure and unload idle models, cancel requests, or lower concurrency when supported. VRAM hard caps may be impossible for some runtimes; do not promise zero OOM risk. Unknown measurements lead to conservative fallback, not guessed safe status.

DOWNLOAD LIFECYCLE: DISCOVERED -> CHECKED -> QUEUED -> DOWNLOADING -> VERIFYING -> INSTALLED | FAILED | PAUSED | CANCELLED. Store download records in SQLite and model files in runtime-managed storage. Use Ollama pull/list/show APIs initially; display real progress and allow retry/recovery. Verify pinned digests/signatures and provenance when available; distinguish registry metadata from independently trusted signatures. Never mark partial artifacts installed. Reserve disk space for temporary layers, account for cache and updates, and safely garbage-collect unused artifacts. Honor model license restrictions and authentication requirements; avoid redistributing unlicensed weights. A future non-Ollama provider uses the same management ports with provider-specific semantics.

RELEASE COMPATIBILITY: pin and test native Ollama version, supported Windows drivers and runtime dependencies; assess redistribution rights, code signing and AV behavior. Bundle runtime when permitted; automatic first-run model download is normal. An offline all-in-one installer is optional and can be tens of GB. No Docker, WSL2 or user-run terminal commands.

## 24. API contracts, database transactions and SSE recovery examples

SUGGESTED API ADDITIONS (freeze after tests): GET /api/v1/conversations/{id}/messages?cursor=; GET /api/v1/runs/{id}; GET /api/v1/runs/{id}/events?after=; POST /api/v1/runs/{id}/resume; POST /api/v1/runs/{id}/cancel; GET /api/v1/runs/{id}/checkpoints; GET /api/v1/memory/summary?conversationId=; DELETE /api/v1/conversations/{id}; GET /api/v1/storage/status. POST /api/v1/conversations/{id}/turns accepts clientRequestId for idempotency. Resume endpoint must reject unsafe runs in NEEDS_REVIEW until explicitly reconciled. Authentication and authorization apply to every endpoint including loopback.

TURN TRANSACTION (conceptual): begin SQLite transaction -> INSERT message(role=user) -> INSERT turn(CREATED, clientRequestId unique) -> INSERT run(CREATED) -> INSERT run_event(turn.created) -> commit -> return IDs -> queue execution. For model tokens, write bounded batches or complete message chunks; on completion, atomically finalize message/turn/run and event. For a tool call: persist tool_invocation(PENDING) -> commit -> broker authorization -> persist decision -> execute in worker -> persist result + checkpoint; if interrupted, reconcile rather than repeat automatically.

SSE EVENT SHAPE: {schemaVersion, eventId, conversationId, turnId, runId, sequence, timestamp, type, payload}. Client stores last seen event ID and deduplicates on reconnect; server supports bounded replay of persisted events. Persist coarse events and periodic token checkpoints; do not require one database write per token. Specify retention and backpressure. For future remote deployments, use per-user auth and tenant isolation rather than reusing local launcher credentials.

## 25. Quality evaluation, observability and operational readiness

EVALUATION HARNESS: fixed, versioned tasks for coding correctness, reasoning, tool-call validity, multi-step task completion, long-context retrieval, hallucination rate, resource footprint, and error recovery. Record model digest, provider/runtime version, quantization, context, hardware, prompt template, tool permissions, and outcome. Quality-first defaults are based on repeatable tests, not parameter counts. Do not upload user prompts to evaluation services without explicit consent.

OBSERVABILITY: local structured logs with correlation IDs (conversation/turn/run/tool), health metrics, model load time, token throughput, memory pressure, download state, DB migration and integrity status, and worker sandbox denials. Redact secrets and user file contents; telemetry opt-in. UI offers diagnostic export with preview/redaction and a clear privacy explanation.

BACKUP/RESTORE: use SQLite backup API or VACUUM INTO/consistent snapshot, not naive copying of a live WAL DB. Snapshot DB and referenced artifacts together with manifest and checksums; test restore and migrations. Preserve data on uninstall unless user chooses deletion; make backup/export and retention policy clear. For future sync, treat conflict resolution and encryption as separate designs, not implicit properties of SQLite.

BUILD/RELEASE: deterministic Maven dependency locking where feasible, SBOM, vulnerability scans, signed installer/updater, signed native helpers, integrity-checked runtime downloads, versioned migrations, staged rollout, rollback compatibility and safe shutdown before updates. Installer must not delete model data or live DB. Test fresh install without Docker/WSL2/Java/Ollama preinstalled, plus upgrade, offline relaunch, driver incompatibility, and failed update recovery.

## 26. Copilot delivery checklist and prioritized milestones

SOURCE OF TRUTH: docs/ARCHITECTURE.md (this document). This is a brand-new greenfield repository; no prior JavaFX or Spring Boot projects are to be reused. Scaffold incrementally rather than generating every future module and feature at once. For each change: identify milestone, touched modules, assumptions, tests, security impact, migration impact, and verification commands; request approval for broad refactors.

M0 Foundation: create Git repository and Maven reactor; scaffold JavaFX and Spring Boot entry points; add ArchUnit dependency checks; framework-independent agent-core; versioned contracts; SQLite adapter and first migration; minimal conversation/message/run persistence; no Spring dependencies in core. EXIT: restart retains chat and module rules pass.

M1 End-to-end: native Ollama adapter, loopback auth, JavaFX streaming/cancel, persisted turns and bounded SSE replay. EXIT: streamed chat survives backend restart with durable history; incomplete runs are marked INTERRUPTED.

M2 Context/recovery: token-budgeted ContextAssembler, FTS5/summaries, run event log, checkpoints, idempotent request submission, tool-intent records, recovery reconciliation and NEEDS_REVIEW state. EXIT: no duplicate destructive side effects after simulated crash.

M3 Model ecosystem: open discovery/import, hardware profiling, estimator/admission/scheduler, download state and safe load/unload. EXIT: two unrelated model families installed/switched; known-unsafe load blocked; unknown metrics handled conservatively.

M4 Packaging: native launcher/supervisor, bundled JRE and runtime, first-run model download, signed installer, updates and data preservation. EXIT: fresh Windows VM without prerequisites installs and chats.

M5 Restricted tools: broker, workspace policy, OS sandbox worker, MCP boundary, red-team suite. EXIT: demonstrate denied filesystem/process/network escapes; if not enforceable, disable risky tools.

M6 Production readiness: quality benchmarks, backups/restore, diagnostics, schema migration tests, licensing audit, hardware compatibility matrix, release gates. EXIT: reproducible evidence, user-visible recovery, no silent data loss.

CROSS-CUTTING: do not build Kubernetes, remote sync, distributed queues, or a custom inference engine in V1. Design ports for future remote/multi-user deployments. Prefer small vertical slices, working tests, and measured assumptions over speculative complexity.

## 27. Copilot starting prompt — paste into IntelliJ

Read docs/ARCHITECTURE.md fully and treat it as the authoritative engineering specification (v3.2). Inspect my existing JavaFX frontend, Spring Boot backend, and Maven build. Do NOT modify code yet. Report current architecture, existing persistence (if any), existing API/model integration, and gaps against the target design. Then propose the smallest M0 vertical slice: Maven dependency boundaries + SQLite schema/migrations + persisted conversation/turn/run records + restart test. Include exact files, dependency graph, test plan, migration risks, and Windows verification commands. Ask me to approve before implementing. Do not introduce Docker/WSL2, do not hardcode Qwen-only selection, and do not promise security isolation without an OS-enforced test. Work one step at a time.

## 28. Contract-first API, events and compatibility (REQUIRED)

DECISION: Maintain a machine-readable `contracts/openapi.yaml` as the source of truth for HTTP APIs, including error schemas, pagination, idempotency, authentication, and streaming endpoint discovery. Maintain `contracts/events/*.schema.json` for versioned SSE envelopes and payloads, because OpenAPI alone does not adequately define event-stream behavior. Generate or validate DTOs without letting generated classes leak into `agent-core`. Define stable domain DTO mapping in `api-host`.

REQUIRED ENDPOINT CONTRACTS: conversation creation/list/read/delete; turn submission with `clientRequestId`; run status/replay/cancel/resume; model catalog/compatibility/install/load/unload; download status; hardware capabilities; approval decisions; health/readiness. Document JSON error codes and safe user messages. Require client/server capability negotiation and explicit API compatibility window; reject unsupported versions with actionable errors.

SSE SEMANTICS: Every event has `schemaVersion`, `eventId`, `runId`, monotonically increasing per-run `sequence`, `type`, `timestamp`, and `payload`. Persist durable state transitions and replayable event records; token deltas may be ephemeral if reconstructable from bounded checkpoints. Client reconnects with cursor, deduplicates, reconciles against persisted run status, and handles event gaps/retention expiry by fetching a snapshot. Specify stream backpressure and cancellation behavior. Contract tests validate fixture compatibility and forbid silently renaming fields.

## 29. End-to-end cancellation and interruption semantics (REQUIRED)

CANCELLATION STATES: `CANCEL_REQUESTED`, `CANCELLING`, `CANCELLED`, `COMPLETED`, `FAILED`, and `NEEDS_REVIEW` where applicable. A cancellation request is not proof the operation stopped. Persist request intent before acknowledging it, revoke queued work, propagate cancellation tokens through JavaFX -> API -> agent core -> inference provider / tool broker -> worker. Use bounded termination deadlines and report when a provider/tool cannot be interrupted safely.

SIDE EFFECTS: For an in-flight write, shell command, network action, or file operation, verify outcome or mark `NEEDS_REVIEW`; never report `CANCELLED` as a guarantee that effects did not occur. Worker process termination must target only owned processes and descendants, with a safe cleanup policy. Preserve partial assistant output as incomplete. Test cancellation during generation, model load, download, approval wait, file write, and backend restart.

## 30. Prompt-injection threat model and untrusted-content handling (REQUIRED)

TRUST BOUNDARIES: System policy and user-authorized grants are authoritative; model-generated text, retrieved repository content, web pages, README files, tool results, summaries, model metadata, and MCP responses are untrusted data. Do not promote untrusted text to developer/system instructions, grant permissions based on it, or permit it to override broker decisions. Treat generated tool arguments as requests, not authorization.

ENFORCEMENT: Capability Broker validates the requesting run, approved workspace, canonicalized paths (including symlinks/junctions), operation, network destination, executable identity, timeout, quotas, and policy version. Enforce permissions at the OS boundary where possible; otherwise disable risky tool classes until isolation is demonstrated. Apply least privilege to MCP servers and never forward credentials or private files without scoped user approval. Sanitize display/logging separately from authorization. No regex or prompt-only filter is sufficient.

TESTS: malicious repository instructions, forged tool outputs, hidden instructions in search results, exfiltration attempts, path traversal, junction escapes, malicious MCP manifests, local-network SSRF, shell argument injection, and approval-confusion attacks. Red-team tests must verify DENIAL by the broker/sandbox, not just that the model declines a request.

## 31. Context, memory lifecycle and provenance (REQUIRED)

MEMORY TYPES: immutable conversation history; resumable run working state; derived summaries; explicit project knowledge; optional user-approved preferences. Store each with source references, workspace scope, author/producer, creation/update times, retention status, and deletion provenance. A derived summary must not replace its original evidence or become a higher-priority instruction.

USER CONTROL: UI must allow listing, inspecting, correcting (via superseding records), exporting, and deleting retained memories. Deletion must remove or invalidate derived summaries, FTS entries, caches, and eligible artifacts while respecting backup retention rules; document what may remain in backups and for how long. Project isolation is mandatory. Do not silently share facts between workspaces or users. Persistent preference extraction is opt-in, not automatic indiscriminate profiling.

CONTEXT MANIFEST: For each inference request, persist model digest, provider/runtime, context budget, prompt template version, selected message IDs, summary IDs, retrieved artifact IDs, and relevant tool policy version. Context windows are finite even if SQLite retains long histories. Use FTS5 first; embeddings only after quality/privacy evaluation. Support source inspection and memory correction without pretending model-generated summaries are infallible.

## 32. Local observability, privacy and diagnostics (REQUIRED)

Structured logs and local metrics must include correlation IDs for conversation/turn/run/tool, error categories, latency, context size, memory pressure, download and database health. Exclude prompts, source files, credentials, full tool payloads, and environment variables by default. Use log rotation, retention limits, access-controlled directories, and redaction tests. Telemetry and crash upload are OFF by default and separately opt-in. Diagnostic export requires preview, redaction, and explicit user action.

Support user-visible health information: runtime status, model loading, disk usage, active tasks, download progress, and recovery issues. A failure must yield a stable error code and remediation instructions without exposing secrets. Test disk-full logging, corrupt DB, unresponsive runtime, and restart loops.

## 33. Performance, reliability budgets and release evidence (REQUIRED)

Define measurable, hardware-tier-specific targets for installer success, cold UI startup, backend readiness, model load success, first-token latency, cancellation acknowledgment, run recovery, download resume, crash-free sessions, DB integrity, and resource headroom. Do not invent universal numerical guarantees before baselines are measured. Each target must specify test hardware, model quantization, context, workload, percentile, sample size, and failure threshold. Quality-first mode may be slower; show progress and user control rather than silently timing out.

Release gates require reproducible test reports and failure-injection evidence: abrupt process termination, lost network during model download, low disk, OOM, model switch under pressure, corrupt artifact, schema upgrade failure, API mismatch, and denied tool escapes. Record open exceptions with owner, risk, workaround, and expiration.

## 34. Feature flags and staged delivery (REQUIRED)

Use typed, versioned, local feature flags for experimental model adapters, agent strategies, indexing, MCP integrations, and optional network features. Default risky features OFF. Security checks, authentication, permission enforcement, and integrity verification are NEVER bypassable by flags. Persist flag changes with audit provenance. Avoid a remote feature-flag service in V1. Keep migrations compatible when toggling experimental features off. Expose developer flags only in an explicit advanced/debug channel.

## 35. Documentation audit: additional gaps and recommended mitigations

AUDIT STATUS: This section is a review of the combined v3.2 + v3.3 specification, not a claim that any code is implemented. The following issues merit explicit requirements before public release.

A. **Single-instance ownership and local API impersonation (PUBLIC RELEASE):** Launcher must authenticate the exact backend it started, protect IPC credentials, prevent a malicious local process from winning a fixed port, and handle multiple Windows sessions. Loopback alone is not an authorization boundary. Prefer OS-protected IPC/handshake where practical and test hostile-local-process scenarios. Define behavior if an unrelated Ollama is already running.

B. **Supply-chain trust and model artifact safety (PUBLIC RELEASE):** Model registries and custom GGUF/model imports are untrusted supply-chain inputs. Validate signed runtime packages where available, immutable hashes, provenance, path traversal during extraction, disk quotas, and licensing. Never execute model-repository scripts automatically. Quarantine failed downloads and do not treat a hash alone as proof of benign content.

C. **Filesystem integrity and agent changes (MVP for write tools):** Before agent edits, record file hash/revision and expected write scope; use atomic replacement where feasible; detect concurrent edits; preserve undo/backup or Git diff preview; require explicit approval for destructive/bulk edits. Never overwrite changed files silently. Provide a transaction-like change set but do not claim arbitrary shell commands can be rolled back.

D. **Process boundary and sandbox feasibility (VALIDATION GATE):** Decide whether a Windows AppContainer/restricted-token worker can run required Git/Maven/shell/MCP workloads while enforcing network and filesystem restrictions. Test with real tools and adversarial fixtures. If not, constrain supported tools or investigate an alternative isolation mechanism. Do not market 'sandboxed' until OS-enforced tests pass.

E. **Database and artifact consistency (MVP):** SQLite transactions cannot atomically commit arbitrary filesystem changes. Use staged artifact writes, content hashes, durable references, and orphan cleanup/reconciliation. Verify consistent backups of DB plus artifacts. Test power-loss and partial writes.

F. **Accessibility and UX resilience (PUBLIC RELEASE):** Keyboard-only navigation, screen-reader labels, high-DPI/multi-monitor support, text scaling, error states, download pause/retry, and a clear first-run hardware capability explanation. UI must remain responsive when model loads or inference stalls.

G. **Multi-user/cloud migration path (FUTURE):** SQLite and per-user local secrets are appropriate for single-user desktop. Remote tenancy needs identity, isolation, durable queues, server-grade storage, rate limits, billing, and audit retention. Define adapters, but do not prematurely build cloud infrastructure.

H. **Dependency/licensing governance (PUBLIC RELEASE):** Maintain SBOM, runtime and model license inventory, notices, model terms/usage restrictions, redistribution rights, signing and vulnerability response policy. A model being downloadable is not proof that it can legally be bundled or commercially offered.

I. **Resource estimator calibration (MVP):** Estimate KV cache, context, concurrency, quantization and runtime overhead; monitor real load results; update per-hardware calibration. Conservative unknown-status behavior, user override only for non-safety-critical warnings, and recoverable OOM handling. Never promise hard VRAM caps on Windows.

J. **Documentation maintainability (MVP):** This document has inherited overlapping milestone lists and duplicated guidance. Latest sections 36–37 are normative when older sections conflict. Future changes should refactor into separate ADRs, API schemas, threat models, and implementation checklists; avoid endlessly appending contradictory requirements.

## 36. Consolidated delivery priorities and acceptance gates (SUPERSEDES EARLIER MILESTONES)

**M0 — Greenfield foundation and persistence:** Initialize a new Git repository and Maven reactor; scaffold JavaFX and Spring Boot applications; establish dependency rules, pure agent core, SQLite migrations, contracts and CI tests. Demonstrate a persisted conversation survives restart.

**M1 — Native end-to-end chat:** JavaFX -> authenticated local API -> Spring Boot host -> agent core -> native Ollama adapter. Persist turns; stream SSE with replay/snapshot; support cancellation and safe interrupted state. No Docker or WSL2.

**M2 — Memory and durable execution:** Context budget/manifest, FTS5, sourced summaries, task events, checkpoints, idempotent turn submission, recovery and `NEEDS_REVIEW` for ambiguous side effects. Demonstrate crash simulation without duplicate writes.

**M3 — Open model ecosystem and safety:** Catalog discovery/import across model families, hardware profiler, calibrated compatibility estimator, scheduler, verified resumable model acquisition, safe load/unload and OOM recovery. Model choice not limited to Qwen.

**M4 — Tool security:** Capability Broker, approvals, workspace/file change safety, prompt-injection tests, sandboxed workers and MCP boundaries. Do not enable unrestricted shell/file/network tools if OS isolation is unverified.

**M5 — Installer and updates:** One signed Windows installer, bundled JRE/backend/desktop/native inference runtime where legally allowed, automatic model setup, secure supervisor, updates/rollback, fresh VM tests without Java/Docker/WSL2/Ollama. Offline package optional.

**M6 — Release hardening:** Contract fixtures, architecture tests, threat-model evidence, model benchmarks, backups/restore, accessibility, observability, performance budgets, supply-chain/license audit and release gates.

**Classification:** MVP baseline = M0–M3 plus tool broker safety for any enabled tool and minimal installer proof; public release = M4–M6 requirements relevant to advertised capabilities; future = remote multi-user, cloud provider integrations, cross-device sync, additional operating systems, custom inference engine, advanced plugin marketplace. Unresolved Windows sandbox and redistribution assumptions are blockers for features that depend on them.

## 37. Copilot instructions and review protocol (LATEST AUTHORITATIVE)

1. Read this entire `docs/ARCHITECTURE.md`, especially sections 28–37. Treat M0–M6 in section 36 as the current milestone plan; older milestone lists are historical.
2. Assume an EMPTY repository. Before coding, verify the working directory and available JDK/Maven tooling; then propose initial scaffolding. Do not claim any application modules or capabilities already exist.
3. Before coding, produce a minimal greenfield vertical slice, dependency direction, files to create, tests, migrations, security implications, and Windows verification commands. Request approval before generating a large scaffold.
4. Work in small changes; run tests after each step; never silently add Docker, WSL2, a database server, Python services, or cloud telemetry.
5. Preserve the framework-independent agent core; version API and events; use SQLite locally; retain model/provider neutrality; apply conservative hardware admission and permission brokering.
6. If a requirement depends on unproven OS sandboxing, runtime redistribution, driver behavior, or provider capabilities, mark it **VALIDATION REQUIRED**, propose a reproducible spike, and do not claim success without test evidence.
7. Never auto-retry ambiguous destructive side effects. Do not execute untrusted content as commands. No hardcoded Qwen-only model restriction.
8. For every completed change report: requirement IDs/sections satisfied, tests run and results, known limitations, changed files, migration effects, and next smallest step.

**Starter prompt:** "Read docs/ARCHITECTURE.md v3.4 in full. We are starting from scratch with an empty IntelliJ repository: no JavaFX, Spring Boot, Maven modules, or database exist. Do not write code yet. Propose the first small M0 setup step, exact files, dependencies, tests, and Windows verification commands. Wait for my approval."


## 38. Greenfield project mandate (V3.4 — OVERRIDES ALL LEGACY PROJECT ASSUMPTIONS)

**AUTHORITATIVE STARTING STATE:** This is a NEW, EMPTY project. There is NO existing JavaFX app, Spring Boot backend, Maven reactor, database, launcher, Ollama integration, or source code to preserve, migrate, inspect, or refactor. Any earlier instructions to reuse an existing project, inventory its implementation, extract existing classes, or preserve old working code are SUPERSEDED. The previous experimental Docker/Ollama setup is not a required product dependency or starting codebase.

**DEVELOPMENT ENVIRONMENT:** Windows 11 and IntelliJ IDEA; Java-first, Maven multi-module repository. Choose and record a supported LTS JDK and compatible JavaFX/Spring Boot/Spring AI versions after checking their current compatibility; pin versions in parent dependency management. The developer may use installed development tools, but the shipped product must not require customers to install Java, Docker Desktop, WSL2, Ollama, Python, or a database server manually.

**REPOSITORY LAYOUT (TARGET; CREATE MODULES WHEN NEEDED):**

```text
local-ai-assistant/
  pom.xml                       # parent reactor / dependency management
  README.md                     # local development, build and run
  .gitignore
  .github/workflows/            # CI, security and release checks when enabled
  docs/ARCHITECTURE.md          # this document, canonical
  docs/adr/                     # architecture decision records
  contracts/openapi.yaml        # versioned HTTP API
  contracts/events/             # streaming event JSON schemas
  agent-core/                   # plain Java domain/orchestration; no Spring or JavaFX
  model-spi/                    # model/provider and capability contracts
  storage-spi/                  # persistence ports
  tool-spi/                     # tool requests and broker contracts
  storage-sqlite/               # SQLite JDBC, schema migrations and FTS5
  model-ollama/                 # initial native Ollama adapter
  api-host/                     # Spring Boot API adapter / composition root
  desktop/                      # JavaFX application
  launcher/                     # native process supervision and health
  platform-windows/             # hardware probing, secrets, isolation adapters
  worker/                       # restricted tool execution (after sandbox spike)
  packaging/                    # jpackage/installer/runtime distribution
  tests/                        # contract, end-to-end and fault injection fixtures
```

**DEPENDENCY DIRECTION:** `agent-core` depends only on Java and narrow SPI/contracts modules; it must not depend on Spring Boot, Spring AI, JavaFX, Ollama, JDBC, Windows APIs, or launcher. `api-host` composes the core with adapters. `desktop` depends on API contracts/transport, not backend implementation. Provider, storage, tool and Windows adapters implement ports rather than becoming core dependencies. Avoid circular Maven dependencies; enforce with ArchUnit and Maven checks. A module's presence in this target tree does not require creating an empty module prematurely.

**BOOTSTRAP ORDER — IMPLEMENT ONE VERTICAL SLICE AT A TIME:**

1. **M0a: Repository and build.** Initialize Git, parent `pom.xml`, `.gitignore`, README, `docs/ARCHITECTURE.md`; select/pin compatible JDK and dependency versions; create the minimum Maven modules needed to compile `agent-core`, `api-host`, and `desktop`; add JUnit and architecture tests. Exit: `mvn -B verify` succeeds from a clean checkout, with no Docker/WSL2 requirement.
2. **M0b: Contract and persistence.** Define minimal conversation/turn DTOs and OpenAPI endpoints; add `storage-spi` and `storage-sqlite`, versioned migrations, WAL/foreign-key/busy-timeout policy and tests. Exit: a stored conversation survives backend process restart.
3. **M1a: Native model proof.** Add `model-spi` and `model-ollama`; prove a managed native Ollama instance can start on Windows, use a private model directory and loopback endpoint, load a compatible test model and stop without affecting unrelated processes. Verify redistribution/licensing before bundling.
4. **M1b: Working desktop chat.** Build JavaFX chat UI; connect to authenticated Spring Boot local API; persist messages, stream model output, support cancellation and reconnect. Exit: first message -> persisted turn -> streamed answer -> restart -> retained history.
5. **Later milestones:** Add durable execution/context, model catalog/hardware scheduling, secure tool workers, installer/update pipeline and release hardening according to section 36. Do not implement all future capabilities in the first scaffold.

**FIRST-RUN PRODUCT EXPERIENCE:** User downloads one installer EXE. The installer bundles an application JRE and approved native inference runtime where lawful, installs backend/desktop/launcher and starts the application. On first run, the app detects hardware, selects or offers compatible models, downloads verified model weights automatically with progress/retry, stores weights and SQLite data under user-controlled local application directories, then opens chat. Online model download is automatic, not a manual dependency install; optional future offline bundle includes weights. If hardware cannot run a suitable model, show a clear limitation instead of forcing a risky load.

**INITIAL TEST MATRIX:** clean Windows VM without Java/Ollama/Docker/WSL2; unit and architecture tests; SQLite migration/restart tests; authenticated loopback tests; native runtime ownership tests; model-download interruption; constrained RAM/VRAM admission; cancellation; startup failure and crash recovery. Validate Windows tool sandbox independently before enabling arbitrary shell/file/network operations. Never claim an OS-enforced sandbox exists merely because processes are separate.

**NON-GOALS FOR THE FIRST COMMIT:** distributed services, Kubernetes, Redis/PostgreSQL, vector database, cloud account requirement, full MCP marketplace, model training, custom inference engine, production installer, or an unrestricted agent shell. These remain later capabilities or validation gates, not prerequisites for compiling the initial Java modules.

## 39. Copilot greenfield start instructions (FINAL OVERRIDE)

**Use this instead of any earlier Copilot starter prompt.** We are starting with NOTHING. Do not ask to inspect or reuse pre-existing JavaFX/Spring Boot projects. First read the entire specification and check the empty repository and local development tooling. Propose a minimal parent Maven setup and first compile/test vertical slice, listing exact files and commands. Ask for approval before generating code. After approval, implement one small step, run tests, report results, and stop for the next instruction. The target is one native Windows product, a modular Java codebase, SQLite persistence, provider-agnostic model selection, safe hardware admission, and secure local-first execution. No Docker/WSL2 requirement.

**Starter prompt:** "Read `docs/ARCHITECTURE.md` v3.4 fully. This is a completely new greenfield project with no existing code or modules. Do not inspect or preserve old JavaFX or Spring Boot projects. Do not code yet. Propose only M0a step 1: repository structure, JDK/Maven versions, initial `pom.xml`, minimum modules, tests, and exact PowerShell verification commands. Identify any version/licensing assumptions requiring validation. Wait for my approval." 
