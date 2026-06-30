package com.apimarketplace.common.bundle;

import lombok.extern.slf4j.Slf4j;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CE-side pinned trust list: keyId → Ed25519 public key.
 *
 * <p>Plain class (no Spring) parsed from one flat string of the form
 * {@code keyId1=base64X509;keyId2=base64X509;...}. Keeping it a single string
 * rather than a Spring {@code Map} binding avoids env-file quoting hell -
 * operators paste the map as a single line.
 *
 * <p>If the string is empty or no entries parse, the trust list holds zero
 * keys. In that case {@link #find(String)} always returns empty - any bundle
 * is rejected by {@link BundleVerification}. This is the intended default on
 * cloud deployments (which sign, do not verify remotely).
 */
@Slf4j
public class TrustedKeys {

    private final Map<String, PublicKey> keys;

    public TrustedKeys(String spec) {
        this.keys = parse(spec);
    }

    /** Return the public key pinned under {@code keyId}, or empty if unknown. */
    public Optional<PublicKey> find(String keyId) {
        if (keyId == null) return Optional.empty();
        return Optional.ofNullable(keys.get(keyId));
    }

    /** True if any keys are configured. Used by schedulers to skip work on unconfigured envs. */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    /** Pinned keyIds (read-only view). Exposed for diagnostics. */
    public Set<String> keyIds() {
        return Collections.unmodifiableSet(keys.keySet());
    }

    private static Map<String, PublicKey> parse(String raw) {
        Map<String, PublicKey> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String entry : raw.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                log.warn("Skipping malformed trusted-keys entry (expected keyId=base64): {}", trimmed);
                continue;
            }
            String keyId = trimmed.substring(0, eq).trim();
            String b64 = trimmed.substring(eq + 1).trim();
            try {
                PublicKey pk = Ed25519BundleCrypto.loadPublicKey(b64);
                out.put(keyId, pk);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                log.warn("Skipping unparseable trusted key (id={}): {}", keyId, e.getMessage());
            }
        }
        return out;
    }
}
