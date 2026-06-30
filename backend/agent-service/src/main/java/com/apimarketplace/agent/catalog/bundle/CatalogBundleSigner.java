package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.common.bundle.Ed25519BundleCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Ed25519 signing + SHA-256 checksum for catalog bundles.
 *
 * <p>Thin Spring wrapper around {@link Ed25519BundleCrypto} (common-lib) that
 * binds the catalog-bundle env config. Keys are configured via env:
 * <ul>
 *   <li>{@code CATALOG_BUNDLE_SIGNING_KEY_PEM} - base64 PKCS#8 Ed25519 private key
 *       (no PEM headers/armor; just the base64 body). Required to sign.</li>
 *   <li>{@code CATALOG_BUNDLE_SIGNING_PUBLIC_KEY} - base64 X.509 Ed25519 public key.
 *       Required for local verification (tests and the {@code /signing-key}
 *       endpoint). If unset, {@link #verify(byte[], String)} returns false and
 *       {@link #publicKeyBase64()} returns null; signing still works.</li>
 *   <li>{@code CATALOG_BUNDLE_SIGNING_KEY_ID} - opaque identifier (default
 *       {@code "default"}) stored on each bundle row so CE can pick the right
 *       public key from its trust list during verification.</li>
 *   <li>{@code CATALOG_BUNDLE_ISSUER} - string stamped on every bundle (default
 *       {@code "livecontext-cloud"}).</li>
 * </ul>
 *
 * <p>When the private key is missing this bean still starts - the bundle
 * service will refuse to {@link #sign(byte[])} and log a clear error. This
 * matches how {@code ModelAuditHmacConfig} degrades: config presence is not a
 * hard boot failure because local dev doesn't need signing keys.
 *
 * <p>Key generation (one-time, offline):
 * <pre>
 *   openssl genpkey -algorithm ed25519 -out priv.pem
 *   openssl pkey -in priv.pem -pubout -out pub.pem
 *   # strip headers and newlines -&gt; CATALOG_BUNDLE_SIGNING_KEY_PEM / _PUBLIC_KEY
 * </pre>
 */
@Component
public class CatalogBundleSigner {

    private final Ed25519BundleCrypto crypto;

    public CatalogBundleSigner(
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

    /**
     * Return the public key for verification. Used by tests and by the
     * CE-facing catalog-bundle endpoint so CE can pin the key it expects.
     */
    public String publicKeyBase64() {
        return crypto.publicKeyBase64();
    }

    /**
     * SHA-256 of the canonical payload, hex-encoded (64 lowercase chars).
     */
    public String checksum(byte[] payload) {
        return crypto.checksum(payload);
    }

    /**
     * Sign the given payload bytes (typically the canonical JSON body)
     * with the configured Ed25519 key; returns base64-encoded signature.
     *
     * @throws IllegalStateException if no private key is configured
     */
    public String sign(byte[] payload) {
        return crypto.sign(payload);
    }

    /**
     * Verify {@code signatureB64} against {@code payload} using the configured
     * public key. Returns {@code false} on any failure (no public key, bad
     * format, signature mismatch) - callers treat this as reject.
     */
    public boolean verify(byte[] payload, String signatureB64) {
        return crypto.verify(payload, signatureB64);
    }

    // Package-private helper kept for tests: UTF-8 encode a canonical JSON string.
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
