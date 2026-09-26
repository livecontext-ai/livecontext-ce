package com.apimarketplace.orchestrator.tools.websearch;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signs the tenant a browser-agent LLM call relays for, so the CE shim
 * ({@code BrowserAgentLlmShimController}) can trust it.
 *
 * <p>The shim is reachable without a user session (the browser-agent runner calls it), and an
 * install can hold several cloud links, one per user. The tenant therefore travels with the call,
 * and an unsigned tenant would let any caller pick whose paid link a relay runs on. Only
 * {@link BrowserAgentModule} signs, after it has read the tenant from the execution context; the
 * shim verifies. Both run in the same process, so the key is random per boot and never leaves
 * memory: a restart only invalidates the runs in flight.
 */
@Component
public class BrowserAgentRelayTenantSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public BrowserAgentRelayTenantSigner() {
        this.key = new byte[32];
        new SecureRandom().nextBytes(this.key);
    }

    /** The signature of {@code tenantId}, URL-safe base64 without padding. */
    public String sign(String tenantId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(tenantId));
    }

    /** Whether {@code signature} is this process's signature of {@code tenantId} (constant time). */
    public boolean verify(String tenantId, String signature) {
        if (tenantId == null || tenantId.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(signature.trim());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(mac(tenantId), presented);
    }

    private byte[] mac(String tenantId) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(tenantId.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
