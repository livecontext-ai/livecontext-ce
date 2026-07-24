-- ============================================================================
-- V408: email_inbox delete/move become message-scoped; move surfaces newMessageUid.
-- Updates the agent-facing node_type_documentation (the LLM's source of truth).
--
-- Why: delete and move used the folder-wide IMAP expunge, which permanently
-- purges EVERY message flagged deleted in the folder, including mail the
-- mailbox owner deleted from their own client and had not expunged yet. The
-- node now expunges only the targeted message (UID EXPUNGE on UIDPLUS servers;
-- flag-only otherwise, and reads skip deleted-flagged messages so they are
-- never re-processed). move also returned the SOURCE uid, which is dead after
-- the move: on UIDPLUS/COPYUID servers the new uid in the target folder is now
-- surfaced as newMessageUid.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  outputs = outputs || '{
    "newMessageUid": {"type": "number", "description": "move only: UID of the message in the target folder, present only when the IMAP server supports UIDPLUS/COPYUID. Absent otherwise (re-read the target folder to find the message)"},
    "messageUid": {"type": "number", "description": "IMAP UID of the message acted on (ACTION mode). For move this is the SOURCE uid, which is invalid after the move; use newMessageUid when present"},
    "count": {"type": "number", "description": "Number of live messages returned (READ mode; messages flagged deleted and awaiting expunge are excluded), or folder count (list_folders)"}
  }'::jsonb,
  concepts = COALESCE(concepts, '[]'::jsonb)
             || '["delete and move remove ONLY the targeted message: other mail in the folder is never purged, even mail already marked deleted by the mailbox owner", "After a move the source messageUid is dead: chain a downstream action on output.newMessageUid (in targetFolder) when it is present; when it is absent the server could not report the new uid, so re-read the target folder (e.g. filter by subject or messageId) to find the message again", "When output.newMessageUid is absent (server without UIDPLUS), a move leaves the source copy flagged deleted but still present until the server expunges it, so re-running the same move could copy it again: guard a re-runnable move with a find/decision on the target folder instead of moving unconditionally", "READ mode never returns messages flagged deleted that are awaiting expunge; count covers live messages only"]'::jsonb,
  updated_at = NOW()
WHERE type = 'email_inbox';
