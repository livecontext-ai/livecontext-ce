-- Add support for EXPERIENCE display mode (interactive published applications: snake game, quizzes, etc.)
-- Experiences run under the creator's tenantId so all participants share the same tables (e.g. leaderboard).
-- Note: display_mode column is VARCHAR(50), not a PostgreSQL enum - no ALTER TYPE needed.

-- Track daily participation limits per user per experience
CREATE TABLE IF NOT EXISTS publication.experience_participations (
    id BIGSERIAL PRIMARY KEY,
    publication_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    participated_at DATE NOT NULL DEFAULT CURRENT_DATE,
    run_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_experience_participation UNIQUE (publication_id, user_id, participated_at)
);

CREATE INDEX IF NOT EXISTS idx_exp_part_pub_user
    ON publication.experience_participations(publication_id, user_id);
