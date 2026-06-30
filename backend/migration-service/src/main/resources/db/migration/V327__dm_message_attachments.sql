-- DM messages can now carry file attachments. Files are uploaded through the existing
-- chat attachment store (conversation-service → storage, owned by the SENDER's tenant);
-- a message references them as a JSONB array of {storageId, type, fileName, mimeType}.
-- Download by the OTHER participant goes through the DM-scoped endpoint which checks
-- thread participation + that the storageId is referenced by a message of the thread.
ALTER TABLE conversation.dm_messages
    ADD COLUMN IF NOT EXISTS attachments JSONB NULL;
