-- Follow a creator: a user subscribes to another user (the publisher of marketplace
-- workflows) and is notified in the bell whenever that creator's new listing goes live.

CREATE TABLE IF NOT EXISTS publication.creator_follows (
    follower_id  VARCHAR(255) NOT NULL,
    creator_id   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, creator_id),
    CONSTRAINT chk_creator_follows_not_self CHECK (follower_id <> creator_id)
);

-- The publish fan-out and the follower count both read by creator.
CREATE INDEX IF NOT EXISTS idx_creator_follows_creator
    ON publication.creator_follows (creator_id);

-- Set the first time a PUBLIC listing is approved and its creator's followers are told.
-- A republish or an info edit sends the listing back through review, so without this
-- marker every later approval of the same listing would notify the followers again.
ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS followers_notified_at TIMESTAMPTZ;

-- Listings that have ALREADY BEEN LIVE are not new: their next approval (a republish or an
-- edit goes back through review) must not announce them. A republish clears reviewed_at, so
-- "been live" is read from every trace a live listing leaves:
--   ACTIVE now; or reviewed before; or installed at least once (use_count, only possible
--   while live); or modified well after its first submission (a republish or edit of an
--   older row, never the fresh insert of a first submission).
-- A PUBLIC listing still waiting for its FIRST review matches none of these and stays
-- unstamped, so its approval after this deploy reaches whoever followed meanwhile.
-- Known trade-off: a first submission edited or re-shared more than an hour after it was
-- first sent, and still pending at deploy, is stamped too. That errs toward a missed
-- announcement, never a false "new app" one, and only for listings pending at deploy.
UPDATE publication.workflow_publications
   SET followers_notified_at = COALESCE(reviewed_at, published_at, NOW())
 WHERE visibility = 'PUBLIC'
   AND (status = 'ACTIVE'
        OR reviewed_at IS NOT NULL
        OR use_count > 0
        OR updated_at > published_at + INTERVAL '1 hour')
   AND followers_notified_at IS NULL;

-- The bell subject for "a creator you follow published a new listing" is the publication.
ALTER TABLE orchestrator.notifications
    DROP CONSTRAINT IF EXISTS chk_notif_subject_type_v1;

-- NOT VALID then VALIDATE, as V518 and V528 did: every existing row already satisfies the
-- narrower V528 check, so the validation is a plain scan.
ALTER TABLE orchestrator.notifications
    ADD CONSTRAINT chk_notif_subject_type_v1
        CHECK (subject_type IN (
            'WORKFLOW',
            'APPLICATION',
            'AGENT_TASK',
            'CREDENTIAL',
            'TRIGGER',
            'ORG_INVITATION',
            'BADGE',
            'AGENT',
            'BILLING',
            'PUBLICATION'
        )) NOT VALID;

ALTER TABLE orchestrator.notifications
    VALIDATE CONSTRAINT chk_notif_subject_type_v1;

COMMENT ON CONSTRAINT chk_notif_subject_type_v1 ON orchestrator.notifications IS
    'V551 relaxed: admits PUBLICATION, for a followed creator''s new marketplace listing.';
