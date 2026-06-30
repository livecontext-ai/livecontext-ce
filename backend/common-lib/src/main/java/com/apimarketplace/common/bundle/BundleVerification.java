package com.apimarketplace.common.bundle;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;

/**
 * Generic verify pipeline for a signed bundle received from the cloud:
 *
 * <ol>
 *   <li>KeyId must be in the pinned {@link TrustedKeys} list.</li>
 *   <li>SHA-256 of the decoded payload must equal {@code checksum}.</li>
 *   <li>Ed25519 signature must verify against the trusted public key.</li>
 * </ol>
 *
 * <p>Pure logic - no I/O. Any failure returns a {@link Result} with {@code
 * ok=false} and a structured {@link Status} the caller can store on a
 * sync-status row. Rejections are <em>never</em> upgraded to exceptions:
 * on prod the caller must keep serving the last-good bundle.
 */
public final class BundleVerification {

    private BundleVerification() {
    }

    /** Verification outcome. Never null. See {@link Status}. */
    public record Result(boolean ok, Status status, String detail, byte[] payloadBytes) {
        public static Result success(byte[] bytes) {
            return new Result(true, Status.OK, null, bytes);
        }
        public static Result fail(Status status, String detail) {
            return new Result(false, status, detail, null);
        }
    }

    public enum Status {
        OK,
        TRUST_UNKNOWN,       // no pinned key for the bundle's signing_key_id
        CHECKSUM_INVALID,    // SHA-256 mismatch
        SIGNATURE_INVALID,   // Ed25519 verify returned false
        PAYLOAD_MALFORMED    // base64 decode failed
    }

    /**
     * Run the full verify pipeline against the given trust list.
     *
     * @param trustedKeys   pinned keyId → public key trust list
     * @param signingKeyId  keyId stamped on the bundle by the signer
     * @param checksumHex   expected SHA-256 of the decoded payload, hex-encoded
     * @param signatureB64  base64 Ed25519 signature over the decoded payload bytes
     * @param payloadBase64 base64-encoded canonical payload bytes
     */
    public static Result verify(TrustedKeys trustedKeys,
                                String signingKeyId,
                                String checksumHex,
                                String signatureB64,
                                String payloadBase64) {
        Optional<PublicKey> key = trustedKeys.find(signingKeyId);
        if (key.isEmpty()) {
            return Result.fail(Status.TRUST_UNKNOWN,
                    "signing_key_id '" + signingKeyId + "' is not in the pinned trust list");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException e) {
            return Result.fail(Status.PAYLOAD_MALFORMED, "payload base64 decode failed: " + e.getMessage());
        }

        String computed = Ed25519BundleCrypto.sha256Hex(bytes);
        if (!computed.equalsIgnoreCase(checksumHex)) {
            return Result.fail(Status.CHECKSUM_INVALID,
                    "sha256 mismatch - expected " + checksumHex + ", computed " + computed);
        }

        try {
            Signature sig = Signature.getInstance(Ed25519BundleCrypto.ALG);
            sig.initVerify(key.get());
            sig.update(bytes);
            boolean ok = sig.verify(Base64.getDecoder().decode(signatureB64));
            if (!ok) return Result.fail(Status.SIGNATURE_INVALID, "Ed25519 verify returned false");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Result.fail(Status.SIGNATURE_INVALID, e.getMessage());
        }

        return Result.success(bytes);
    }
}
