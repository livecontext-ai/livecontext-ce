package com.apimarketplace.common.storage.signing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 signer for the public showcase file proxy. The publication-service
 * mints {@code /api/files/proxy-signed?key=…&exp=…&disposition=…&sig=…} URLs,
 * the storage-service verifies the signature here. The signature IS the
 * authorization - no JWT, no tenant header, no DB lookup.
 *
 * <p><strong>Pattern</strong>: same canonicalisation rule on both sides
 * ({@code key + "|" + exp + "|" + disposition}, then HMAC-SHA256, then
 * base64url no-padding). The {@code disposition} is included in the signed
 * payload so an attacker cannot flip {@code inline} → {@code attachment} on
 * a captured URL.
 *
 * <p>Lives in {@code common-storage-service} so both the minting side
 * (publication-service rewriter) and the verifying side (storage-service
 * controller) share one canonicalisation function - drift between the two
 * would silently break every marketplace card.
 *
 * <p><strong>Failure mode</strong>: an unconfigured/blank secret makes
 * {@link #sign} return {@code null} (with WARN) and {@link #verify} return
 * {@code false}. Production must set {@code STORAGE_SHOWCASE_HMAC_SECRET};
 * in dev the marketplace cards stay broken until the operator opts in.
 */
@Component
public class ShowcaseUrlSigner {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseUrlSigner.class);
    private static final String ALGO = "HmacSHA256";

    private final SecretKeySpec key;
    private final boolean enabled;

    public ShowcaseUrlSigner(@Value("${storage.showcase.hmac-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            this.key = null;
            this.enabled = false;
            log.warn("storage.showcase.hmac-secret not configured - anonymous marketplace " +
                    "showcase image rendering is disabled. Set STORAGE_SHOWCASE_HMAC_SECRET " +
                    "in production.");
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            this.key = new SecretKeySpec(bytes, ALGO);
            this.enabled = true;
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Compute the canonical signature for {@code key|exp|disposition}.
     * @return base64url no-padding signature, or {@code null} if disabled.
     */
    public String sign(String storageKey, long expiresAtEpochSeconds, String disposition) {
        if (!enabled) return null;
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(key);
            byte[] payload = canonicalPayload(storageKey, expiresAtEpochSeconds, disposition);
            byte[] raw = mac.doFinal(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            log.error("Failed to sign showcase URL for key={}: {}", storageKey, e.getMessage());
            return null;
        }
    }

    /**
     * Constant-time verify of the signature against the canonical payload.
     * @return {@code true} when signed by us AND not yet expired.
     */
    public boolean verify(String storageKey, long expiresAtEpochSeconds, String disposition,
                           String providedSignature, long nowEpochSeconds) {
        if (!enabled) return false;
        if (providedSignature == null || providedSignature.isEmpty()) return false;
        if (expiresAtEpochSeconds <= nowEpochSeconds) return false;
        String expected = sign(storageKey, expiresAtEpochSeconds, disposition);
        if (expected == null) return false;
        // Decode both to bytes so the comparison is on raw HMAC output, not the
        // textual encoding (avoids issues if a client encodes with padding).
        byte[] expectedBytes;
        byte[] providedBytes;
        try {
            expectedBytes = Base64.getUrlDecoder().decode(expected);
            providedBytes = Base64.getUrlDecoder().decode(providedSignature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private static byte[] canonicalPayload(String storageKey, long exp, String disposition) {
        String d = disposition == null ? "" : disposition;
        return (storageKey + "|" + exp + "|" + d).getBytes(StandardCharsets.UTF_8);
    }
}
