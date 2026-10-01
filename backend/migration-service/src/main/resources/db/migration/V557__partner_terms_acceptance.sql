-- V557: the Partner Program Terms a partner accepted, and when.
--
-- The program had no terms of its own: the application form pointed at the marketing page.
-- Partners now accept a versioned text (the Partner Program Terms, published under
-- /legal/partners) before they apply, and a partner who never accepted it (a code created by
-- an admin before this migration, or one approved on an older version) is asked to accept it
-- from their dashboard. No commission is paid out to a partner who has not accepted any
-- version.
--
-- One row per acceptance, append-only: the record of who accepted which version, when, from
-- where. It is the evidence of the contract, so it is never updated, and it outlives the
-- account (see AccountPurgeService): the identity behind the user id is kept in the payout
-- and accounting records for the statutory period.
--
-- Style: no em-dash or en-dash anywhere.

CREATE TABLE IF NOT EXISTS auth.partner_terms_acceptance (
    id             BIGSERIAL     PRIMARY KEY,
    user_id        BIGINT        NOT NULL,
    -- The version identifier printed on the terms page (a date, for example 2026-10-01).
    terms_version  VARCHAR(32)   NOT NULL,
    -- The fingerprint of that version's exact text (sha256 of both language versions). A
    -- published version's text never changes, so the row proves which words were accepted.
    terms_fingerprint VARCHAR(80) NOT NULL,
    accepted_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Where the partner accepted: with their application, or from their partner dashboard.
    source         VARCHAR(16)   NOT NULL,
    -- The evidence of the click: the client address and browser at the time of acceptance.
    ip_address     VARCHAR(64),
    user_agent     VARCHAR(256),
    CONSTRAINT chk_partner_terms_acceptance_source CHECK (source IN ('APPLICATION', 'DASHBOARD')),
    -- Accepting the same version twice records nothing new.
    CONSTRAINT uq_partner_terms_acceptance_user_version UNIQUE (user_id, terms_version)
);

CREATE INDEX IF NOT EXISTS idx_partner_terms_acceptance_user
    ON auth.partner_terms_acceptance (user_id, accepted_at DESC);

COMMENT ON TABLE auth.partner_terms_acceptance IS
    'V557 acceptances of the Partner Program Terms: one append-only row per user and version.';
