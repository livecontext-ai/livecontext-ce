-- V527: context for the cloud lifecycle emails (Resend contacts + events).
--
-- auth-service keeps one Resend contact per user and sends it named events
-- (user.signed_up, user.activated, user.returned, checkout.*, credits.*). The
-- email sequences branch on the contact's locale, plan, persona, time zone,
-- country and marketing consent, so those facts live on the user row:
--
--   locale / locale_explicit   the app locale; an explicit pick in the UI
--                              (locale_explicit = true) is never overwritten
--                              by the locale the app merely displayed.
--   time_zone                  IANA zone id reported by the browser, last wins.
--   signup_country             ISO-3166 alpha-2 from Cloudflare, write-once.
--   signup_ip / _captured_at   ABUSE PREVENTION ONLY, write-once, never sent to
--                              Resend, nulled 12 months after capture by the
--                              auth-service purge scheduler.
--   marketing_consent / _at    opt-in for news and offers, default off.
--   activated_at               first workflow created, set once; the
--                              user.activated event is emitted only when this
--                              flips from NULL.
--
-- user_acquisition holds the first-touch attribution (UTM, referrer, landing
-- path), write-once. It cascades with the user so an account purge takes it.

ALTER TABLE auth.users
    ADD COLUMN IF NOT EXISTS locale                VARCHAR(8),
    ADD COLUMN IF NOT EXISTS locale_explicit       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS time_zone             VARCHAR(64),
    ADD COLUMN IF NOT EXISTS signup_country        VARCHAR(2),
    ADD COLUMN IF NOT EXISTS signup_ip             VARCHAR(45),
    ADD COLUMN IF NOT EXISTS signup_ip_captured_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS marketing_consent     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS marketing_consent_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS activated_at          TIMESTAMPTZ;

COMMENT ON COLUMN auth.users.signup_ip IS
    'Abuse prevention only. Write-once, never sent to any third party, nulled 12 months after signup_ip_captured_at. V527.';

-- The 12-month purge scans only rows that still hold an IP.
CREATE INDEX IF NOT EXISTS idx_users_signup_ip_captured_at
    ON auth.users (signup_ip_captured_at)
    WHERE signup_ip IS NOT NULL;

CREATE TABLE IF NOT EXISTS auth.user_acquisition (
    user_id       BIGINT PRIMARY KEY REFERENCES auth.users (id) ON DELETE CASCADE,
    utm_source    VARCHAR(255),
    utm_medium    VARCHAR(255),
    utm_campaign  VARCHAR(255),
    utm_content   VARCHAR(255),
    utm_term      VARCHAR(255),
    referrer      VARCHAR(1024),
    landing_path  VARCHAR(1024),
    first_seen_at TIMESTAMPTZ,
    captured_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE auth.user_acquisition IS
    'First-touch acquisition attribution (UTM, referrer, landing path). Write-once per user. V527.';
