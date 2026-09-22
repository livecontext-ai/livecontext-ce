-- Password reset for embedded (self-hosted) auth.
--
-- Cloud never reaches this table: Keycloak owns the reset flow there
-- (resetPasswordAllowed=true on the realm). Embedded auth had no reset at all,
-- so a self-hoster who forgot their password had no route back into their own
-- install. The table still ships to both editions so the schema stays identical
-- across them; the `auth.mode=embedded` gate on the service is what keeps it
-- dark on cloud.
--
-- DEPLOYING THIS TO CLOUD NEEDS -f migration_enabled=true.
-- The JPA entity is unconditional (the table ships to both editions so the
-- schema stays identical), and auth-service runs `ddl-auto: validate`, so
-- without this migration applied the new auth-service pods do not boot at all:
-- validation fails on a missing auth.password_reset_tokens. `--atomic` turns
-- that into a failed rollout rather than an outage, which is 15 wasted minutes
-- rather than a page. CE is unaffected: it runs Flyway in-process at startup.
--
-- The token is stored ONLY as a SHA-256 hash, exactly as refresh tokens are
-- (PasswordAuthService.hashToken). A dump of this table must not hand anyone an
-- account: the raw token exists in the reset e-mail and nowhere else, not even
-- in a log line.
--
-- One live token per user is enforced in the service, not here, and the reason
-- given here before was wrong: a partial unique index on (user_id) WHERE
-- used_at IS NULL would NOT touch the historical rows, because a redeemed row
-- has used_at set and falls outside the index by definition.
--
-- A second rationale that was ALSO wrong, recorded because it is the tempting
-- one: that the mail would go out before the violation surfaced at flush. It
-- would not. The entity is GenerationType.IDENTITY, so Hibernate runs the
-- INSERT at persist() to obtain the key and the loser fails inside save(),
-- before the dispatch is even reached; the dispatch now waits for commit
-- besides.
--
-- The actual reason: sequential requests never collide (issuing invalidates the
-- previous token in the same transaction first), so the index would only ever
-- fire on two GENUINELY concurrent submits for one account, where it converts a
-- harmless outcome into a worse one. Today both succeed and both links work.
-- With the index, the loser's whole request rolls back and, because the answer
-- must stay uniform, the person is told a link is on its way and gets nothing.
-- Two live tokens is the better failure: the newer one wins the next issuance,
-- redemption burns whatever is left, and neither lies to anyone.

CREATE TABLE IF NOT EXISTS auth.password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    -- ON DELETE CASCADE, like auth.refresh_tokens. Without it a purged account
    -- leaves live tokens behind, and redeeming one reaches "User not found",
    -- which the controller returns verbatim: a stranger holding an old link
    -- would learn the account was deleted.
    user_id     BIGINT       NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP    NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_ip  VARCHAR(45)  NULL
);

-- The lookup on redemption. Unique because a collision would let one token
-- redeem another account, and SHA-256 over 32 random bytes makes it free.
CREATE UNIQUE INDEX IF NOT EXISTS idx_password_reset_token_hash
    ON auth.password_reset_tokens (token_hash);

-- Rate limiting and invalidation both scan a user's recent rows.
CREATE INDEX IF NOT EXISTS idx_password_reset_user_created
    ON auth.password_reset_tokens (user_id, created_at DESC);

-- The daily cleanup.
CREATE INDEX IF NOT EXISTS idx_password_reset_expires
    ON auth.password_reset_tokens (expires_at);

COMMENT ON TABLE auth.password_reset_tokens IS
    'Single-use password reset tokens for embedded auth (CE). token_hash is SHA-256; the raw token lives only in the e-mail.';
