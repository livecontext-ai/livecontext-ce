-- V553: applications to the partner program (V549).
--
-- Until now an admin created every partner code by hand, from an email address, with
-- no record of who asked or why. A signed-in user now applies from the public
-- /partners page; an admin approves (which creates the PARTNER code through the V549
-- path, unchanged) or rejects, and the applicant sees the outcome on their partner
-- dashboard.
--
-- One row per application, kept after the decision: a rejected applicant may apply
-- again later, and each attempt stays on record.
--
-- Style: no em-dash or en-dash anywhere.

CREATE TABLE IF NOT EXISTS auth.partner_application (
    id               BIGSERIAL     PRIMARY KEY,
    user_id          BIGINT        NOT NULL,
    -- PENDING until an admin decides; APPROVED carries the created code.
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    company_name     VARCHAR(120)  NOT NULL,
    website          VARCHAR(255),
    -- Who the applicant builds for (their clients or audience), free text.
    audience         VARCHAR(500),
    message          VARCHAR(2000),
    reward_code_id   BIGINT,
    reviewed_by      BIGINT,
    reviewed_at      TIMESTAMPTZ,
    -- Shown to the applicant on a rejection, so keep it addressed to them.
    decision_note    VARCHAR(500),
    -- Optimistic lock: two admins deciding the same application at once must not both
    -- win (the second decision would overwrite the first, code and all).
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_partner_application_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_partner_application_approved_code
        CHECK (status <> 'APPROVED' OR reward_code_id IS NOT NULL)
);

-- At most one open application per user: a double submit loses on this index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_partner_application_pending_user
    ON auth.partner_application (user_id) WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_partner_application_status
    ON auth.partner_application (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_partner_application_user
    ON auth.partner_application (user_id, created_at DESC);
