package com.apimarketplace.catalog.bundle;

import com.apimarketplace.common.bundle.BundleVerification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Verify an {@link ApiCatalogSignedBundle} received from the cloud:
 *
 * <ol>
 *   <li>KeyId must be in the pinned {@link ApiCatalogTrustedKeyRegistry}.</li>
 *   <li>SHA-256 of the decoded payload must equal {@code checksum}.</li>
 *   <li>Ed25519 signature must verify against the trusted public key.</li>
 * </ol>
 *
 * <p>The decoded payload bytes are the GZIPPED canonical JSON - the signature
 * covers the gzip bytes, so verification happens BEFORE gunzip (the applier
 * gunzips the verified bytes). Thin wrapper around
 * {@link BundleVerification} (common-lib). Pure logic - no I/O; rejections are
 * never upgraded to exceptions so CE keeps serving its last-good catalog.
 */
@Component
@RequiredArgsConstructor
public class ApiCatalogBundleVerifier {

    private final ApiCatalogTrustedKeyRegistry trustedKeys;

    /** Verification outcome. Never null. {@code payloadBytes} = verified GZIP bytes. */
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

    public Result verify(ApiCatalogSignedBundle bundle) {
        if (bundle == null) return Result.fail(Status.PAYLOAD_MALFORMED, "null bundle");

        BundleVerification.Result r = BundleVerification.verify(
                trustedKeys.trustedKeys(),
                bundle.signingKeyId(),
                bundle.checksum(),
                bundle.signature(),
                bundle.payloadBase64());

        if (r.ok()) return Result.success(r.payloadBytes());
        return Result.fail(Status.valueOf(r.status().name()), r.detail());
    }
}
