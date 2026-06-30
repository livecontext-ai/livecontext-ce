-- CE embedded local accounts do not depend on external email delivery.
-- Backfill rows created before PasswordAuthService started marking LOCAL
-- registrations as verified.
UPDATE auth.users
SET email_verified = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE auth_provider = 'LOCAL'
  AND email_verified = FALSE;
