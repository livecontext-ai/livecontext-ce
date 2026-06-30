-- Add moderation columns to workflow_publications
ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS reviewer_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

-- Index for efficient pending publication queries
CREATE INDEX IF NOT EXISTS idx_wp_status ON publication.workflow_publications(status);
