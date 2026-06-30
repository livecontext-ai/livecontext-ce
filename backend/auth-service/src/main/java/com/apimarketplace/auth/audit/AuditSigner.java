package com.apimarketplace.auth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signer for audit events. Allows tamper detection at read time:
 * if anyone modifies the JSON line in the log file, the signature won't match.
 *
 * The HMAC key MUST be configured via {@code audit.hmac-key} (base64) in
 * production. If not set, an ephemeral key is generated and a warning logged
 * - this means signatures cannot be verified across restarts (dev mode only).
 */
@Component
public class AuditSigner {

    private static final Logger log = LoggerFactory.getLogger(AuditSigner.class);
    private static final String ALGO = "HmacSHA256";

    private final SecretKeySpec key;

    public AuditSigner(@Value("${audit.hmac-key:}") String hmacKeyB64) {
        byte[] keyBytes;
        if (hmacKeyB64 == null || hmacKeyB64.isBlank()) {
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            log.warn("audit.hmac-key not configured - using ephemeral key. " +
                    "Audit log signatures will not survive restart. " +
                    "Set AUDIT_HMAC_KEY in production.");
        } else {
            keyBytes = Base64.getDecoder().decode(hmacKeyB64);
        }
        this.key = new SecretKeySpec(keyBytes, ALGO);
    }

    public String sign(String canonicalJson) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(key);
            return HexFormat.of().formatHex(
                    mac.doFinal(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign audit event", e);
        }
    }
}
