package com.apimarketplace.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.regex.Pattern;

/**
 * Validates {@code MODEL_AUDIT_HMAC_KEY} at startup.
 *
 * <p>The key is interpolated into the Hikari {@code connection-init-sql}
 * (see {@code application.yml}) and executed verbatim against Postgres:
 * <pre>SELECT set_config('app.model_audit_hmac_key', '&lt;KEY&gt;', false)</pre>
 * Any character that would close the SQL string literal ({@code '}, newline,
 * backslash, backtick, null byte) could break out of the statement, so we
 * refuse to start if the key doesn't match a conservative base64/hex/URL-safe
 * allow-list. In production the key is generated with
 * {@code openssl rand -base64 32}, which always fits the allow-list.
 *
 * <p>Empty key is allowed and logged as a warning - audit trigger no-ops,
 * admin edits are not recorded. Acceptable for dev but never for prod.
 *
 * <p>Per V109 design, migration-service does NOT set this variable; the audit
 * trigger's {@code current_setting(..., true)} returns NULL for those
 * connections and the trigger no-ops, letting Flyway migrations proceed.
 */
@Slf4j
@Configuration
public class ModelAuditHmacConfig {

    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9+/=_\\-]{1,256}$");

    @Value("${MODEL_AUDIT_HMAC_KEY:}")
    private String hmacKey;

    @PostConstruct
    void validate() {
        if (hmacKey == null || hmacKey.isEmpty()) {
            log.warn("MODEL_AUDIT_HMAC_KEY is not set. Admin edits to agent.model_config_overrides " +
                    "will NOT be audited (trigger no-ops). Set a base64 secret (openssl rand -base64 32) " +
                    "for any environment where admin auditing is required.");
            return;
        }
        if (!ALLOWED.matcher(hmacKey).matches()) {
            throw new IllegalStateException(
                    "MODEL_AUDIT_HMAC_KEY contains characters outside the base64/URL-safe alphabet " +
                            "(A-Z a-z 0-9 + / = _ -) or exceeds 256 chars. Refusing to start because this " +
                            "value is interpolated into the Hikari connection-init-sql literal and an unsafe " +
                            "character would break out of the SQL string and could be executed against the DB.");
        }
        log.info("MODEL_AUDIT_HMAC_KEY validated ({} chars). Hikari connection-init-sql will propagate it " +
                "as app.model_audit_hmac_key on every physical connection.", hmacKey.length());
    }
}
