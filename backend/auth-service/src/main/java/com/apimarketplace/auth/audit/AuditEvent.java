package com.apimarketplace.auth.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable audit event. All PII is pseudonymized before reaching this record:
 * - userId is the internal UUID (never the email)
 * - ipHash is sha256(ip + daily_salt)
 * - uaHash is sha256(user_agent)
 *
 * The {@code signature} is an HMAC-SHA256 over the canonical JSON (without the
 * signature field), used to detect log tampering at read time.
 */
public record AuditEvent(
        String eventId,        // UUID v4
        Instant timestamp,
        String eventType,      // enum-style: "login.success", "login.failure", ...
        String severity,       // info | warn | critical
        String service,        // auth-service
        String userId,         // nullable for anonymous events
        String ipHash,         // nullable
        String uaHash,         // nullable
        String result,         // success | failure | unknown
        String reason,         // nullable, short enum-style code
        Map<String, Object> details, // bounded, sanitized
        String signature       // HMAC-SHA256 hex
) {
}
