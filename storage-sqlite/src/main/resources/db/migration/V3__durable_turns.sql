-- statement
CREATE TABLE turns (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    user_message_id TEXT NOT NULL,
    client_request_id TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (conversation_id, client_request_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    FOREIGN KEY (user_message_id) REFERENCES messages (id) ON DELETE CASCADE
);

-- statement
CREATE INDEX idx_turns_status ON turns (status, updated_at);
