-- V556: partner tiers (Silver / Gold / Platinum) on top of the V549 partner program.
--
-- A partner's commission rate used to be whatever their code was created with. It now
-- follows a tier that only ever goes UP:
--   SILVER    the entry tier, given on approval;
--   GOLD      reached automatically once the partner's customers have paid a set amount
--             (settled revenue: past the refund window, never voided);
--   PLATINUM  reached the same way at a higher amount, or granted by an admin to a
--             founding partner (only until the founder window closes). Kept for life.
--
-- One row per partner (the account, not the code: a partner keeps their tier if their
-- code is ever replaced). No row reads as SILVER. The rates and thresholds are
-- configuration, not data: the row only records the tier reached and whether it was a
-- founder grant.
--
-- Style: no em-dash or en-dash anywhere.

CREATE TABLE IF NOT EXISTS auth.partner_standing (
    user_id             BIGINT       PRIMARY KEY,
    tier                VARCHAR(16)  NOT NULL DEFAULT 'SILVER',
    -- A founding partner: PLATINUM for life, granted by an admin before the window closed.
    founder             BOOLEAN      NOT NULL DEFAULT FALSE,
    -- When the current tier was reached.
    reached_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The admin behind the last manual change; NULL when the tier was reached on revenue.
    updated_by_user_id  BIGINT,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_partner_standing_tier CHECK (tier IN ('SILVER', 'GOLD', 'PLATINUM')),
    CONSTRAINT chk_partner_standing_founder CHECK (NOT founder OR tier = 'PLATINUM')
);

COMMENT ON TABLE auth.partner_standing IS
    'V556 partner tier per partner account. Raised by revenue and never lowered by it; only an admin ending founder status (terms clause 7.5) lowers it. No row = SILVER.';
