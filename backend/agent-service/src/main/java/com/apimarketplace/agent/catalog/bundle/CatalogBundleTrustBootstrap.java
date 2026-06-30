package com.apimarketplace.agent.catalog.bundle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Trust-on-first-use (TOFU) bootstrap for the model-catalog bundle trust list.
 *
 * <p>A cloud-LINKED CE that has no operator-pinned key cannot verify any bundle,
 * so the model-catalog sync would forever report {@code TRUST_UNCONFIGURED} even
 * though the linked cloud is publishing signed bundles. This component closes that
 * gap: it fetches the cloud's PUBLIC signing-key endpoint
 * ({@code GET {catalog.bundle.cloud-url}/api/catalog-bundles/signing-key}) and pins
 * the returned Ed25519 public key into {@link TrustedKeyRegistry}, after which the
 * normal fetch -> verify -> apply pipeline proceeds.
 *
 * <p><b>Gating is the caller's job.</b> {@link CatalogBundleSyncScheduler} only
 * invokes {@link #bootstrapTrust()} when the install is cloud-linked AND the
 * registry is empty. This component additionally guards both conditions
 * defensively (it no-ops if a key is already pinned, and skips if the cloud URL is
 * empty), so it never re-fetches on a healthy install and never overwrites an
 * existing key. The signing-key endpoint is unauthenticated (trust bootstrap), so
 * no cloud-link credentials are needed to read it - the cloud-link gate lives in
 * the scheduler. Trust stays defence-in-depth: the bearer gates WHO downloads the
 * bundle, the pinned signature proves WHAT was downloaded.
 *
 * <p>Never throws: any miss (cloud URL empty, HTTP/network error, malformed or
 * unparseable response) returns {@code pinned=false} with a detail string the
 * scheduler records as {@code TRUST_UNCONFIGURED} and retries next tick.
 */
@Slf4j
@Component
public class CatalogBundleTrustBootstrap {

    private final RestTemplate restTemplate;
    private final TrustedKeyRegistry trustedKeys;
    private final String cloudUrl;

    public CatalogBundleTrustBootstrap(
            RestTemplate restTemplate,
            TrustedKeyRegistry trustedKeys,
            @Value("${catalog.bundle.cloud-url:}") String cloudUrl) {
        this.restTemplate = restTemplate;
        this.trustedKeys = trustedKeys;
        this.cloudUrl = cloudUrl == null ? "" : cloudUrl.trim();
    }

    /** Outcome of a TOFU bootstrap attempt. Never null. */
    public record Result(boolean pinned, String keyId, String detail) {
        public static Result pinned(String keyId) { return new Result(true, keyId, null); }
        public static Result skipped(String detail) { return new Result(false, null, detail); }
    }

    /**
     * Shape of {@code GET /api/catalog-bundles/signing-key}
     * (see {@link CatalogBundleController#signingKey()}).
     */
    record SigningKeyResponse(String keyId, String issuer, String publicKeyBase64, String algorithm) {}

    /**
     * Fetch the linked cloud's signing key and pin it (trust-on-first-use).
     * Returns {@code pinned=true} with the keyId on success, otherwise
     * {@code pinned=false} with a reason. Idempotent: a no-op (skipped) once a
     * key is already pinned.
     */
    public Result bootstrapTrust() {
        // Defensive: never re-fetch or overwrite once trust is configured.
        if (trustedKeys.hasKeys()) {
            return Result.skipped("trust already configured");
        }
        if (cloudUrl.isEmpty()) {
            return Result.skipped("catalog.bundle.cloud-url is empty");
        }
        String url = cloudUrl.replaceFirst("/+$", "") + "/api/catalog-bundles/signing-key";

        SigningKeyResponse resp;
        try {
            resp = restTemplate.getForObject(url, SigningKeyResponse.class);
        } catch (HttpStatusCodeException e) {
            // 503 = the cloud has no signing key configured yet; any 4xx/5xx lands here.
            return Result.skipped("HTTP " + e.getStatusCode().value() + " from " + url);
        } catch (RestClientException e) {
            return Result.skipped(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (resp == null
                || resp.keyId() == null || resp.keyId().isBlank()
                || resp.publicKeyBase64() == null || resp.publicKeyBase64().isBlank()) {
            return Result.skipped("cloud signing-key response missing keyId/publicKeyBase64");
        }

        boolean ok = trustedKeys.pin(resp.keyId(), resp.publicKeyBase64());
        if (!ok) {
            // Either unparseable, or another thread pinned a key first.
            return Result.skipped(
                    "signing key '" + resp.keyId() + "' could not be pinned (unparseable or already present)");
        }
        log.info("TOFU bootstrap: pinned cloud signing key '{}' (issuer={}) from {}",
                resp.keyId(), resp.issuer(), url);
        return Result.pinned(resp.keyId());
    }
}
