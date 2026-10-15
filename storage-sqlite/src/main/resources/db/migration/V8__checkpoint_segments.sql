-- statement
CREATE TABLE turn_checkpoint_segments (
    turn_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (turn_id, sequence),
    FOREIGN KEY (turn_id) REFERENCES turns (id) ON DELETE CASCADE
);
