-- statement
CREATE VIRTUAL TABLE message_search USING fts5(
    message_id UNINDEXED,
    conversation_id UNINDEXED,
    content,
    tokenize = 'unicode61'
);

-- statement
INSERT INTO message_search (message_id, conversation_id, content)
SELECT id, conversation_id, content FROM messages;

-- statement
CREATE TRIGGER messages_search_after_insert
AFTER INSERT ON messages
BEGIN
    INSERT INTO message_search (message_id, conversation_id, content)
    VALUES (NEW.id, NEW.conversation_id, NEW.content);
END;

-- statement
CREATE TRIGGER messages_search_after_delete
AFTER DELETE ON messages
BEGIN
    DELETE FROM message_search WHERE message_id = OLD.id;
END;

-- statement
CREATE TRIGGER messages_search_after_update
AFTER UPDATE OF conversation_id, content ON messages
BEGIN
    DELETE FROM message_search WHERE message_id = OLD.id;
    INSERT INTO message_search (message_id, conversation_id, content)
    VALUES (NEW.id, NEW.conversation_id, NEW.content);
END;
