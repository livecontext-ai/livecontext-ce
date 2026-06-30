package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.common.bundle.BundleVerification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Verify a {@link SignedBundle} received from the cloud:
 *
 * <ol>
 *   <li>KeyId must be in the pinned {@link TrustedKeyRegistry}.</li>
 *   <li>SHA-256 of the decoded payload must equal {@code checksum}.</li>
 *   <li>Ed25519 signature must verify against the trusted public key.</li>
 * </ol>
 *
 * <p>Thin wrapper around {@link BundleVerification} (common-lib). Pure logic -
 * no I/O. Any failure returns a {@link Result} with {@code ok=false} and a
 * structured {@code status} string the scheduler can store on the sync-status
 * row. Rejections are <em>never</em> upgraded to exceptions: on prod the
 * scheduler must keep serving the last-good bundle.
 */
@Component
@RequiredArgsConstructor
public class CatalogBundleVerifier {

    private final TrustedKeyRegistry trustedKeys;

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

    public Result verify(SignedBundle bundle) {
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

    // Package-private for tests that want to build a canonical byte array.
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
