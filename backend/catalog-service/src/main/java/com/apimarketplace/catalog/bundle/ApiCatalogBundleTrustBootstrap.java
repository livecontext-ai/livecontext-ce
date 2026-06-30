package com.apimarketplace.catalog.bundle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Trust-on-first-use (TOFU) bootstrap for the API-catalog bundle trust list.
 * Mirrors {@code agent-service CatalogBundleTrustBootstrap}.
 *
 * <p>A CE pointed at a cloud ({@code api-catalog.bundle.cloud-url} set) that has no
 * operator-pinned key cannot verify any bundle, so the API-catalog sync would
 * forever report {@code TRUST_UNCONFIGURED}. This component closes that gap: it
 * fetches the cloud's PUBLIC signing-key endpoint
 * ({@code GET {api-catalog.bundle.cloud-url}/api/catalog/public/bundles/signing-key})
 * and pins the returned Ed25519 public key into {@link ApiCatalogTrustedKeyRegistry},
 * after which the normal fetch -> verify -> apply pipeline proceeds.
 *
 * <p><b>"Cloud-linked" for the API catalog = a configured cloud URL.</b> Unlike the
 * LLM model bundle, the API-catalog bundle download is a PUBLIC endpoint with no
 * per-install link token (CE authenticates the CONTENT via the signature, not the
 * transport), so the only signal that this CE is "pointed at a cloud" is a non-empty
 * {@code api-catalog.bundle.cloud-url} - which is exactly what gates the fetch here.
 * The bootstrap only pins into an EMPTY registry and never overwrites an existing key.
 *
 * <p>Never throws: any miss (cloud URL empty, HTTP/network error, malformed or
 * unparseable response) returns {@code pinned=false} with a detail string the
 * scheduler records as {@code TRUST_UNCONFIGURED} and retries next tick.
 */
@Slf4j
@Component
public class ApiCatalogBundleTrustBootstrap {

    private final RestTemplate restTemplate;
    private final ApiCatalogTrustedKeyRegistry trustedKeys;
    private final String cloudUrl;

    public ApiCatalogBundleTrustBootstrap(
            RestTemplate restTemplate,
            ApiCatalogTrustedKeyRegistry trustedKeys,
            @Value("${api-catalog.bundle.cloud-url:}") String cloudUrl) {
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
     * Shape of {@code GET /api/catalog/public/bundles/signing-key}
     * (see {@link ApiCatalogBundleController#signingKey()}).
     */
    record SigningKeyResponse(String keyId, String issuer, String publicKeyBase64, String algorithm) {}

    /**
     * Fetch the cloud's signing key and pin it (trust-on-first-use). Returns
     * {@code pinned=true} with the keyId on success, otherwise {@code pinned=false}
     * with a reason. Idempotent: a no-op (skipped) once a key is already pinned.
     */
    public Result bootstrapTrust() {
        // Defensive: never re-fetch or overwrite once trust is configured.
        if (trustedKeys.hasKeys()) {
            return Result.skipped("trust already configured");
        }
        // No cloud URL = this CE is not pointed at a cloud = nothing to bootstrap from.
        if (cloudUrl.isEmpty()) {
            return Result.skipped("api-catalog.bundle.cloud-url is empty");
        }
        String url = cloudUrl.replaceFirst("/+$", "") + "/api/catalog/public/bundles/signing-key";

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
            return Result.skipped(
                    "signing key '" + resp.keyId() + "' could not be pinned (unparseable or already present)");
        }
        log.info("TOFU bootstrap: pinned cloud API-catalog signing key '{}' (issuer={}) from {}",
                resp.keyId(), resp.issuer(), url);
        return Result.pinned(resp.keyId());
    }
}
