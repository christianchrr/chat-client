-- statement
ALTER TABLE turns ADD COLUMN sequence_ceiling INTEGER NOT NULL DEFAULT 0;

-- statement
CREATE TABLE run_events (
    turn_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    event_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (turn_id, sequence),
    FOREIGN KEY (turn_id) REFERENCES turns (id) ON DELETE CASCADE
);

-- statement
CREATE INDEX idx_run_events_created_at ON run_events (created_at);
