package com.apimarketplace.common.storage.url;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single source of truth for the user-facing file URL.
 *
 * <p>Emits an <b>absolute</b>, <b>opaque</b> URL that addresses a stored file by its
 * {@code storage.storage} row UUID - never by the raw S3 key. The S3 key starts with the
 * numeric tenant/user id ({@code {tenantId}/{workflowId}/...}), so putting it in a URL leaks
 * the owner's id; the row UUID leaks nothing. The URL is built against the public origin
 * ({@code app.base-url} = {@code APP_PUBLIC_URL}, e.g. {@code https://livecontext.ai}) and
 * targets the Next.js proxy path {@code /api/proxy/files/by-id/{id}/raw}, which forwards to the
 * org-scoped streaming endpoint in storage-service and promotes the caller's session token.
 *
 * <p>Lives in {@code common-storage-service} so every minting site - the agent {@code files}
 * tool, workflow runtime sidecars, the frontend - produces the identical canonical shape.
 * The internal {@link com.apimarketplace.common.storage.domain.StorageEntity#getS3Key() s3 key}
 * remains the runtime wiring handle (the engine fetches S3 by it) and is a separate concern;
 * it is never what this builder emits.
 */
@Component
public class PublicFileUrlBuilder {

    /** Public origin for user-facing links (frontend). Falls back to local frontend in dev. */
    private final String baseUrl;

    /**
     * Public FRONTEND origin (where the Next.js {@code /api/proxy/files/...} catch-all lives). Reads
     * the dedicated {@code app.public-url} first, falling back to {@code app.base-url}. They are the
     * same in the cloud microservices ({@code app.base-url == APP_PUBLIC_URL}), so the fallback is a
     * no-op there. They DIVERGE in the CE monolith, where {@code app.base-url} points at the GATEWAY
     * origin (auth-service mail / Keycloak links) - using it here would mint
     * {@code http://gateway:8080/api/proxy/...} URLs that 404 (the proxy route only exists on the
     * frontend). CE therefore sets {@code app.public-url} to the frontend origin explicitly.
     */
    public PublicFileUrlBuilder(@Value("${app.public-url:${app.base-url:http://localhost:3000}}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /**
     * Absolute, opaque, user-facing URL for the stored file identified by {@code storageId}
     * (the {@code storage.storage} row UUID). {@code inline} → rendered in-browser
     * ({@code disposition=inline}); otherwise the endpoint defaults to attachment/download.
     */
    public String fileUrl(UUID storageId, boolean inline) {
        String url = baseUrl + "/api/proxy/files/by-id/" + storageId + "/raw";
        return url + (inline ? "?disposition=inline" : "?disposition=attachment");
    }

    /** Convenience overload for a String id (already validated as a UUID upstream). */
    public String fileUrl(String storageId, boolean inline) {
        String url = baseUrl + "/api/proxy/files/by-id/" + storageId + "/raw";
        return url + (inline ? "?disposition=inline" : "?disposition=attachment");
    }
}
