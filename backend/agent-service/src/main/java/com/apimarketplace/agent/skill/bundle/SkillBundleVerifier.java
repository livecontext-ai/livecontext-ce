package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.common.bundle.BundleVerification;
import com.apimarketplace.common.bundle.TrustedKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verify a {@link SignedSkillBundle} received from the cloud:
 *
 * <ol>
 *   <li>KeyId must be in the pinned trust list.</li>
 *   <li>SHA-256 of the decoded payload must equal {@code checksum}.</li>
 *   <li>Ed25519 signature must verify against the trusted public key.</li>
 * </ol>
 *
 * <p>Reuses common-lib {@link BundleVerification}/{@link TrustedKeys} and the SHARED trust
 * root {@code catalog.bundle.trusted-keys} (one Ed25519 key signs every cloud bundle:
 * model, API-catalog and skill). Pure logic - no I/O. Any failure returns a {@link Result}
 * with {@code ok=false}; rejections are never upgraded to exceptions so the scheduler keeps
 * serving the last-good state.
 */
@Slf4j
@Component
public class SkillBundleVerifier {

    private final TrustedKeys trustedKeys;

    public SkillBundleVerifier(@Value("${catalog.bundle.trusted-keys:}") String rawTrustedKeys) {
        this.trustedKeys = new TrustedKeys(rawTrustedKeys);
        if (!trustedKeys.hasKeys()) {
            log.info("No trusted skill-bundle keys configured (catalog.bundle.trusted-keys). " +
                    "CE skill-bundle sync will reject all bundles until this is set.");
        }
    }

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
        TRUST_UNKNOWN,
        CHECKSUM_INVALID,
        SIGNATURE_INVALID,
        PAYLOAD_MALFORMED
    }

    /** True if any keys are configured. Used by the scheduler to skip work on unconfigured envs. */
    public boolean hasKeys() {
        return trustedKeys.hasKeys();
    }

    public Result verify(SignedSkillBundle bundle) {
        if (bundle == null) return Result.fail(Status.PAYLOAD_MALFORMED, "null bundle");

        BundleVerification.Result r = BundleVerification.verify(
                trustedKeys,
                bundle.signingKeyId(),
                bundle.checksum(),
                bundle.signature(),
                bundle.payloadBase64());

        if (r.ok()) return Result.success(r.payloadBytes());
        return Result.fail(Status.valueOf(r.status().name()), r.detail());
    }
}
