package com.apimarketplace.catalog.bundle;

import com.apimarketplace.common.bundle.Ed25519BundleCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ed25519 signing + SHA-256 checksum for API-catalog bundles.
 *
 * <p>Thin Spring wrapper around {@link Ed25519BundleCrypto} (common-lib) bound
 * to the SAME env vars as the LLM model bundle signer
 * ({@code agent-service CatalogBundleSigner}) - deliberately a shared trust
 * root: one cloud keypair signs both the model catalog and the API catalog,
 * and CE pins it once via {@code catalog.bundle.trusted-keys}.
 * <ul>
 *   <li>{@code CATALOG_BUNDLE_SIGNING_KEY_PEM} - base64 PKCS#8 Ed25519 private
 *       key (PEM armor tolerated). Required to sign.</li>
 *   <li>{@code CATALOG_BUNDLE_SIGNING_PUBLIC_KEY} - base64 X.509 public key
 *       (for tests and the public {@code /signing-key} endpoint).</li>
 *   <li>{@code CATALOG_BUNDLE_SIGNING_KEY_ID} - key id stamped on each bundle
 *       (default {@code "default"}).</li>
 *   <li>{@code CATALOG_BUNDLE_ISSUER} - issuer string (default
 *       {@code "livecontext-cloud"}).</li>
 * </ul>
 *
 * <p>When the private key is missing this bean still starts - the bundle
 * service refuses to build with a clear error. Local dev needs no keys.
 */
@Component
public class ApiCatalogBundleSigner {

    private final Ed25519BundleCrypto crypto;

    public ApiCatalogBundleSigner(
            @Value("${CATALOG_BUNDLE_SIGNING_KEY_PEM:}") String privateKeyB64,
            @Value("${CATALOG_BUNDLE_SIGNING_PUBLIC_KEY:}") String publicKeyB64,
            @Value("${CATALOG_BUNDLE_SIGNING_KEY_ID:default}") String keyId,
            @Value("${CATALOG_BUNDLE_ISSUER:livecontext-cloud}") String issuer) {
        this.crypto = new Ed25519BundleCrypto(privateKeyB64, publicKeyB64, keyId, issuer);
    }

    public boolean canSign() {
        return crypto.canSign();
    }

    public String keyId() { return crypto.keyId(); }

    public String issuer() { return crypto.issuer(); }

    /** Public key for verification (null when not configured). */
    public String publicKeyBase64() {
        return crypto.publicKeyBase64();
    }

    /** SHA-256 of the payload bytes, hex-encoded (64 lowercase chars). */
    public String checksum(byte[] payload) {
        return crypto.checksum(payload);
    }

    /**
     * Sign the given payload bytes (the GZIPPED canonical JSON for API-catalog
     * bundles) with the configured Ed25519 key; returns base64.
     *
     * @throws IllegalStateException if no private key is configured
     */
    public String sign(byte[] payload) {
        return crypto.sign(payload);
    }

    /** Verify {@code signatureB64} against {@code payload}; false on any failure. */
    public boolean verify(byte[] payload, String signatureB64) {
        return crypto.verify(payload, signatureB64);
    }
}
