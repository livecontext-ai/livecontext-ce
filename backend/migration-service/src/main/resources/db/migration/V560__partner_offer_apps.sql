-- The applications a partner gives with an offer (V559), and their delivery to the clients who
-- pay through it. The offer lists up to ten of the partner's own applications (publication ids,
-- in the order shown on the offer page). Whoever pays through the link receives them: one row per
-- (offer, client, application), written AWAITING_PAYMENT when the checkout opens, PENDING once the
-- subscription's first invoice is paid, then installed in the client's workspace and retried until
-- it lands or is refused for good. The waiting rows let a payment whose webhook failed be found again.
ALTER TABLE auth.partner_offer
    ADD COLUMN IF NOT EXISTS app_publication_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS auth.partner_offer_delivery (
    id               BIGSERIAL    PRIMARY KEY,
    -- Purged with the partner's offers (AccountPurgeService deletes them).
    offer_id         BIGINT       NOT NULL REFERENCES auth.partner_offer (id) ON DELETE CASCADE,
    client_user_id   BIGINT       NOT NULL,
    publication_id   UUID         NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    -- When the next try may start; also the lease a try takes, so two pods never install twice.
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Why the last try failed, for support (an error code and a short message, no user data).
    last_error       VARCHAR(300),
    invoice_id       VARCHAR(255),
    -- When the reconciliation last looked for this checkout's payment: it looks at the least
    -- recently checked first, so every waiting checkout gets its turn.
    checked_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_partner_offer_delivery UNIQUE (offer_id, client_user_id, publication_id),
    CONSTRAINT chk_partner_offer_delivery_status CHECK (status IN ('AWAITING_PAYMENT', 'PENDING', 'INSTALLED', 'FAILED'))
);

-- The retry sweep reads the due rows only.
CREATE INDEX IF NOT EXISTS idx_partner_offer_delivery_due
    ON auth.partner_offer_delivery (next_attempt_at) WHERE status = 'PENDING';

-- The checkouts still waiting for their payment (the reconciliation and its cleanup).
CREATE INDEX IF NOT EXISTS idx_partner_offer_delivery_awaiting
    ON auth.partner_offer_delivery (created_at) WHERE status = 'AWAITING_PAYMENT';

-- The client's own deliveries (welcome screen, account purge).
CREATE INDEX IF NOT EXISTS idx_partner_offer_delivery_client
    ON auth.partner_offer_delivery (client_user_id);
