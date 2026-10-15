-- statement
CREATE TABLE tool_invocations (
    id TEXT PRIMARY KEY,
    turn_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    operation TEXT NOT NULL,
    required_permissions_json TEXT NOT NULL,
    side_effecting INTEGER NOT NULL CHECK (side_effecting IN (0, 1)),
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (turn_id, idempotency_key),
    FOREIGN KEY (turn_id) REFERENCES turns (id) ON DELETE CASCADE
);
-- statement
CREATE INDEX idx_tool_invocations_status ON tool_invocations (status, updated_at);
