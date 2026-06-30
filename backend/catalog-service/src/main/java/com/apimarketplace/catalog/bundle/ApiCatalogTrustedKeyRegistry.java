package com.apimarketplace.catalog.bundle;

import com.apimarketplace.common.bundle.TrustedKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.Optional;
import java.util.Set;

/**
 * CE-side pinned trust list for API-catalog bundles: keyId → Ed25519 public key.
 *
 * <p>Bound to the SAME flat property as the LLM model bundle
 * ({@code catalog.bundle.trusted-keys}, format
 * {@code keyId1=base64X509;keyId2=base64X509;...}) - the two bundle systems
 * deliberately share one trust root, so a CE operator pins the cloud key once.
 *
 * <p>If the string is empty or no entries parse, the registry holds zero keys
 * and every bundle is rejected by {@link ApiCatalogBundleVerifier}. This is
 * the intended default on cloud deployments (which sign, never verify).
 *
 * <p><b>Trust-on-first-use (TOFU).</b> When a CE pointed at a cloud has no
 * operator-pinned key, {@link ApiCatalogBundleTrustBootstrap} fetches the
 * cloud's published Ed25519 signing key and {@link #pin(String, String) pins}
 * it here so API-catalog updates flow without a manual
 * {@code CATALOG_BUNDLE_TRUSTED_KEYS}. The trust list is therefore mutable at
 * runtime: {@code keys}/{@code rawSpec} are {@code volatile} and {@link #pin}
 * is {@code synchronized}. {@code pin} only ADDS an unknown keyId - an
 * operator-pinned key (or a prior TOFU pin) is never overwritten.
 */
@Slf4j
@Component
public class ApiCatalogTrustedKeyRegistry {

    private volatile TrustedKeys keys;
    private volatile String rawSpec;

    public ApiCatalogTrustedKeyRegistry(
            @Value("${catalog.bundle.trusted-keys:}") String raw) {
        this.rawSpec = raw == null ? "" : raw.trim();
        this.keys = new TrustedKeys(this.rawSpec);
        if (!keys.hasKeys()) {
            log.info("No trusted catalog-bundle keys configured (catalog.bundle.trusted-keys). " +
                    "A CE pointed at a cloud will trust-on-first-use bootstrap the cloud's signing " +
                    "key; otherwise API catalog bundle sync rejects all bundles.");
        } else {
            log.info("Loaded {} trusted API-catalog-bundle key(s): {}", keys.keyIds().size(), keys.keyIds());
        }
    }

    /**
     * Trust-on-first-use pin: add {@code keyId -> publicKey} to the trust list.
     *
     * <p>Returns {@code true} iff the key was newly added AND the base64 parses
     * as a valid Ed25519 X.509 public key. Never overwrites an already-pinned
     * {@code keyId} (returns {@code false}) - so an operator-pinned key or an
     * earlier TOFU pin stays authoritative and a rotated cloud key is never
     * silently re-trusted within a running process.
     */
    public synchronized boolean pin(String keyId, String publicKeyBase64) {
        if (keyId == null || keyId.isBlank()
                || publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            return false;
        }
        if (keys.find(keyId).isPresent()) {
            return false; // already pinned - TOFU never re-trusts/overwrites
        }
        String merged = rawSpec.isBlank()
                ? keyId + "=" + publicKeyBase64
                : rawSpec + ";" + keyId + "=" + publicKeyBase64;
        TrustedKeys candidate = new TrustedKeys(merged);
        if (candidate.find(keyId).isEmpty()) {
            log.warn("TOFU pin rejected: signing key '{}' is not a valid Ed25519 X.509 public key", keyId);
            return false;
        }
        this.keys = candidate;
        this.rawSpec = merged;
        log.info("TOFU: pinned API-catalog-bundle signing key '{}' (trust-on-first-use)", keyId);
        return true;
    }

    /** Return the public key pinned under {@code keyId}, or empty if unknown. */
    public Optional<PublicKey> find(String keyId) {
        return keys.find(keyId);
    }

    /** True if any keys are configured. The scheduler skips work on unconfigured envs. */
    public boolean hasKeys() {
        return keys.hasKeys();
    }

    /** Pinned keyIds (read-only view). Exposed for diagnostics. */
    public Set<String> keyIds() {
        return keys.keyIds();
    }

    /** Underlying common-lib trust list, for {@link ApiCatalogBundleVerifier} delegation. */
    TrustedKeys trustedKeys() {
        return keys;
    }
}
