-- statement
CREATE TABLE context_summaries (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    content TEXT NOT NULL,
    source_message_ids_json TEXT NOT NULL,
    model_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);
-- statement
CREATE INDEX idx_context_summaries_conversation_created
    ON context_summaries (conversation_id, created_at);
