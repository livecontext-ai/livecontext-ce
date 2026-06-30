package com.apimarketplace.common.bundle;

import lombok.extern.slf4j.Slf4j;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 signing + SHA-256 checksum for signed bundles.
 *
 * <p>Plain class (no Spring) so any service can reuse it. Hosts typically bind
 * the constructor arguments to env vars:
 * <ul>
 *   <li>{@code *_SIGNING_KEY_PEM} - base64 PKCS#8 Ed25519 private key
 *       (no PEM headers/armor; just the base64 body). Required to sign.</li>
 *   <li>{@code *_SIGNING_PUBLIC_KEY} - base64 X.509 Ed25519 public key.
 *       Required for local verification (tests and the {@code /signing-key}
 *       endpoint). If unset, {@link #verify(byte[], String)} returns false and
 *       {@link #publicKeyBase64()} returns null; signing still works.</li>
 *   <li>{@code *_SIGNING_KEY_ID} - opaque identifier stored on each bundle row
 *       so CE can pick the right public key from its trust list during
 *       verification.</li>
 *   <li>{@code *_ISSUER} - string stamped on every bundle.</li>
 * </ul>
 *
 * <p>When the private key is missing this class still constructs - callers
 * will be refused by {@link #sign(byte[])} with a clear error. Config presence
 * is not a hard boot failure because local dev doesn't need signing keys.
 *
 * <p>Key generation (one-time, offline):
 * <pre>
 *   openssl genpkey -algorithm ed25519 -out priv.pem
 *   openssl pkey -in priv.pem -pubout -out pub.pem
 *   # strip headers and newlines -&gt; private / public key values
 * </pre>
 */
@Slf4j
public class Ed25519BundleCrypto {

    static final String ALG = "Ed25519";

    private final String keyId;
    private final String issuer;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public Ed25519BundleCrypto(String privateKeyB64, String publicKeyB64, String keyId, String issuer) {
        this.keyId = keyId;
        this.issuer = issuer;

        PrivateKey priv = null;
        PublicKey pub = null;
        try {
            if (privateKeyB64 != null && !privateKeyB64.isBlank()) {
                priv = loadPrivateKey(privateKeyB64);
            }
            if (publicKeyB64 != null && !publicKeyB64.isBlank()) {
                pub = loadPublicKey(publicKeyB64);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("Bundle signing key / public key is malformed - " +
                    "bundle signing will be unavailable: {}", e.getMessage());
        }
        this.privateKey = priv;
        this.publicKey = pub;

        if (priv == null) {
            log.warn("Bundle signing private key is not set. Cloud-side bundle " +
                    "creation is disabled. Set a base64-encoded PKCS#8 Ed25519 key " +
                    "(openssl genpkey -algorithm ed25519) for any environment that " +
                    "publishes signed bundles.");
        } else {
            log.info("Ed25519BundleCrypto initialised (keyId={}, issuer={})", keyId, issuer);
        }
    }

    public boolean canSign() {
        return privateKey != null;
    }

    public String keyId() { return keyId; }

    public String issuer() { return issuer; }

    /**
     * Return the public key for verification. Used by tests and by the
     * CE-facing bundle endpoint so CE can pin the key it expects.
     */
    public String publicKeyBase64() {
        if (publicKey == null) return null;
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * SHA-256 of the canonical payload, hex-encoded (64 lowercase chars).
     */
    public String checksum(byte[] payload) {
        return sha256Hex(payload);
    }

    /**
     * Sign the given payload bytes (typically the canonical JSON body)
     * with the configured Ed25519 key; returns base64-encoded signature.
     *
     * @throws IllegalStateException if no private key is configured
     */
    public String sign(byte[] payload) {
        if (privateKey == null) {
            throw new IllegalStateException(
                    "CATALOG_BUNDLE_SIGNING_KEY_PEM is not set - cannot sign catalog bundle");
        }
        try {
            Signature sig = Signature.getInstance(ALG);
            sig.initSign(privateKey);
            sig.update(payload);
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /**
     * Verify {@code signatureB64} against {@code payload} using the configured
     * public key. Returns {@code false} on any failure (no public key, bad
     * format, signature mismatch) - callers treat this as reject.
     */
    public boolean verify(byte[] payload, String signatureB64) {
        if (publicKey == null || signatureB64 == null) return false;
        try {
            Signature sig = Signature.getInstance(ALG);
            sig.initVerify(publicKey);
            sig.update(payload);
            return sig.verify(Base64.getDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static PrivateKey loadPrivateKey(String b64) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(sanitize(b64));
        return KeyFactory.getInstance(ALG).generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    static PublicKey loadPublicKey(String b64) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(sanitize(b64));
        return KeyFactory.getInstance(ALG).generatePublic(new X509EncodedKeySpec(bytes));
    }

    /** Strip PEM armor + whitespace so callers can paste an entire .pem file. */
    static String sanitize(String raw) {
        return raw
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s+", "");
    }

    /** SHA-256 of the payload, hex-encoded (64 lowercase chars). */
    static String sha256Hex(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JDK - cannot compute bundle checksum", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
