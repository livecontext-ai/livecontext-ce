package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.common.bundle.TrustedKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.Optional;

/**
 * CE-side pinned trust list: keyId → Ed25519 public key.
 *
 * <p>Thin Spring wrapper around {@link TrustedKeys} (common-lib) bound to the
 * flat property {@code catalog.bundle.trusted-keys} of the form
 * {@code keyId1=base64X509;keyId2=base64X509;...}. Keeping it a single string
 * rather than a Spring {@code Map} binding avoids env-file quoting hell -
 * operators paste the map as a single line.
 *
 * <p>If the string is empty or no entries parse, the registry holds zero
 * keys. In that case {@link #find(String)} always returns empty - any bundle
 * is rejected by {@link CatalogBundleVerifier}. This is the intended default
 * on cloud deployments (which sign, do not verify remotely).
 *
 * <p><b>Trust-on-first-use (TOFU).</b> When a cloud-LINKED CE has no
 * operator-pinned key, {@link CatalogBundleTrustBootstrap} fetches the linked
 * cloud's published Ed25519 signing key and {@link #pin(String, String) pins}
 * it here so catalog updates flow without a manual
 * {@code CATALOG_BUNDLE_TRUSTED_KEYS}. The trust list is therefore mutable at
 * runtime: {@code keys}/{@code rawSpec} are {@code volatile} and {@link #pin}
 * is {@code synchronized}. {@code pin} only ADDS an unknown keyId - an
 * operator-pinned key (or a prior TOFU pin) is never overwritten.
 */
@Slf4j
@Component
public class TrustedKeyRegistry {

    private volatile TrustedKeys keys;
    private volatile String rawSpec;

    public TrustedKeyRegistry(
            @Value("${catalog.bundle.trusted-keys:}") String raw) {
        this.rawSpec = raw == null ? "" : raw.trim();
        this.keys = new TrustedKeys(this.rawSpec);
        if (!keys.hasKeys()) {
            log.info("No trusted catalog-bundle keys configured (catalog.bundle.trusted-keys). " +
                    "A cloud-linked CE will trust-on-first-use bootstrap the cloud's signing key; " +
                    "otherwise sync rejects all bundles.");
        } else {
            log.info("Loaded {} trusted catalog-bundle key(s): {}", keys.keyIds().size(), keys.keyIds());
        }
    }

    /**
     * Trust-on-first-use pin: add {@code keyId -> publicKey} to the trust list.
     *
     * <p>Returns {@code true} iff the key was newly added AND the base64 parses
     * as a valid Ed25519 X.509 public key. Never overwrites an already-pinned
     * {@code keyId} (returns {@code false}) - so an operator-pinned key or an
     * earlier TOFU pin stays authoritative and a rotated cloud key is never
     * silently re-trusted within a running process. Thread-safe: callers (the
     * scheduled tick + the admin "sync now") may race.
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
            // base64 did not parse to a valid Ed25519 public key - TrustedKeys
            // skips it, so the candidate would silently drop the new key.
            log.warn("TOFU pin rejected: signing key '{}' is not a valid Ed25519 X.509 public key", keyId);
            return false;
        }
        this.keys = candidate;
        this.rawSpec = merged;
        log.info("TOFU: pinned catalog-bundle signing key '{}' (trust-on-first-use)", keyId);
        return true;
    }

    /** Return the public key pinned under {@code keyId}, or empty if unknown. */
    public Optional<PublicKey> find(String keyId) {
        return keys.find(keyId);
    }

    /** True if any keys are configured. Used by the scheduler to skip work on unconfigured envs. */
    public boolean hasKeys() {
        return keys.hasKeys();
    }

    /** Pinned keyIds (read-only view). Exposed for diagnostics. */
    public java.util.Set<String> keyIds() {
        return keys.keyIds();
    }

    /** Underlying common-lib trust list, for {@link CatalogBundleVerifier} delegation. */
    TrustedKeys trustedKeys() {
        return keys;
    }
}
