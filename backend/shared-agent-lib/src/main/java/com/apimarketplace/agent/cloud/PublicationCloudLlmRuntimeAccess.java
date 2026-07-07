package com.apimarketplace.agent.cloud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * CE adapter: resolves cloud runtime information from publication-service.
 */
@Component
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class PublicationCloudLlmRuntimeAccess implements CloudLlmRuntimeAccess {

    private final RestTemplate restTemplate;
    private final String publicationUrl;

    // Two constructors (the package-private one is for tests); mark the
    // injection ctor so Spring doesn't fall back to a non-existent default
    // ctor (NoSuchMethodException <init>()) - breaks CE boot (marketplace.mode=remote).
    @org.springframework.beans.factory.annotation.Autowired
    public PublicationCloudLlmRuntimeAccess(
            @Value("${services.publication-url:http://localhost:8092}") String publicationUrl) {
        this(new RestTemplate(), publicationUrl);
    }

    PublicationCloudLlmRuntimeAccess(RestTemplate restTemplate, String publicationUrl) {
        this.restTemplate = restTemplate;
        this.publicationUrl = publicationUrl.endsWith("/")
                ? publicationUrl.substring(0, publicationUrl.length() - 1)
                : publicationUrl;
    }

    @Override
    public CloudLlmSource getEffectiveLlmSource(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return CloudLlmSource.BYOK;
        }
        Map<?, ?> body = fetch("/api/internal/cloud-link/source/" + tenantId, tenantId);
        return CloudLlmSource.from(asString(body.get("source")));
    }

    @Override
    public Optional<CloudLlmRuntimeCredentials> resolveCloudRuntime(String tenantId) {
        return Optional.of(runtime(tenantId))
                .filter(RuntimeResponse::cloudReady)
                .filter(r -> r.source() == CloudLlmSource.CLOUD)
                .filter(r -> hasText(r.accessToken()) && hasText(r.installId()) && hasText(r.cloudApiUrl()))
                .map(r -> new CloudLlmRuntimeCredentials(r.accessToken(), r.installId(), r.cloudApiUrl()));
    }

    @Override
    public Optional<CloudLlmRuntimeCredentials> resolveActiveCloudRuntime() {
        Map<?, ?> body;
        try {
            // Install-global, tenant-less. Send a system sentinel X-User-ID ("0") so any
            // header filter is satisfied; the /active-runtime endpoint ignores it and
            // resolves THE active install link itself. Any failure (unreachable, non-2xx)
            // is treated as "not linked" so the bundle sync skips instead of crashing.
            body = fetch("/api/internal/cloud-link/active-runtime", "0");
        } catch (RuntimeException unreachable) {
            return Optional.empty();
        }
        boolean cloudReady = Boolean.TRUE.equals(body.get("cloudReady"));
        String accessToken = asString(body.get("accessToken"));
        String installId = asString(body.get("installId"));
        String cloudApiUrl = asString(body.get("cloudApiUrl"));
        if (!cloudReady || !hasText(accessToken) || !hasText(installId) || !hasText(cloudApiUrl)) {
            return Optional.empty();
        }
        return Optional.of(new CloudLlmRuntimeCredentials(accessToken, installId, cloudApiUrl));
    }

    @Override
    public CloudLlmSource getEffectiveCatalogSource(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return CloudLlmSource.BYOK;
        }
        Map<?, ?> body = fetch("/api/internal/cloud-link/catalog-source/" + tenantId, tenantId);
        return CloudLlmSource.from(asString(body.get("source")));
    }

    @Override
    public Optional<CloudLlmRuntimeCredentials> resolveCatalogCloudRuntime(String tenantId) {
        return Optional.of(catalogRuntime(tenantId))
                .filter(RuntimeResponse::cloudReady)
                .filter(r -> r.source() == CloudLlmSource.CLOUD)
                .filter(r -> hasText(r.accessToken()) && hasText(r.installId()) && hasText(r.cloudApiUrl()))
                .map(r -> new CloudLlmRuntimeCredentials(r.accessToken(), r.installId(), r.cloudApiUrl()));
    }

    @Override
    public Optional<CloudLlmRuntimeCredentials> resolveActiveCatalogRuntime() {
        Map<?, ?> body;
        try {
            // Install-global, tenant-less. Send a system sentinel X-User-ID ("0") so any
            // header filter is satisfied; the /active-catalog-runtime endpoint ignores it
            // and resolves THE active install link itself. Any failure (unreachable,
            // non-2xx) is treated as "not linked" so the caller skips instead of crashing.
            body = fetch("/api/internal/cloud-link/active-catalog-runtime", "0");
        } catch (RuntimeException unreachable) {
            return Optional.empty();
        }
        // Defensive source filter, mirroring the tenant-scoped resolveCatalogCloudRuntime:
        // the catalog credential relay is an opt-in (catalogSource=CLOUD), so credentials
        // reported for a BYOK catalog source must never be consumed even if the producer
        // ever emitted them ready.
        CloudLlmSource source = CloudLlmSource.from(asString(body.get("source")));
        boolean cloudReady = Boolean.TRUE.equals(body.get("cloudReady"));
        String accessToken = asString(body.get("accessToken"));
        String installId = asString(body.get("installId"));
        String cloudApiUrl = asString(body.get("cloudApiUrl"));
        if (source != CloudLlmSource.CLOUD
                || !cloudReady || !hasText(accessToken) || !hasText(installId) || !hasText(cloudApiUrl)) {
            return Optional.empty();
        }
        return Optional.of(new CloudLlmRuntimeCredentials(accessToken, installId, cloudApiUrl));
    }

    private RuntimeResponse runtime(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new RuntimeResponse(CloudLlmSource.BYOK, false, null, null, null);
        }
        Map<?, ?> body = fetch("/api/internal/cloud-link/runtime/" + tenantId, tenantId);
        return new RuntimeResponse(
                CloudLlmSource.from(asString(body.get("source"))),
                Boolean.TRUE.equals(body.get("cloudReady")),
                asString(body.get("accessToken")),
                asString(body.get("installId")),
                asString(body.get("cloudApiUrl"))
        );
    }

    private RuntimeResponse catalogRuntime(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new RuntimeResponse(CloudLlmSource.BYOK, false, null, null, null);
        }
        Map<?, ?> body = fetch("/api/internal/cloud-link/catalog-runtime/" + tenantId, tenantId);
        return new RuntimeResponse(
                CloudLlmSource.from(asString(body.get("source"))),
                Boolean.TRUE.equals(body.get("cloudReady")),
                asString(body.get("accessToken")),
                asString(body.get("installId")),
                asString(body.get("cloudApiUrl"))
        );
    }

    private Map<?, ?> fetch(String path, String tenantId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    publicationUrl + path,
                    HttpMethod.GET,
                    new HttpEntity<>(headers(tenantId)),
                    Map.class
            );
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("publication-service returned " + response.getStatusCode().value());
            }
            return response.getBody();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to resolve CE LLM source from publication-service", e);
        }
    }

    private static HttpHeaders headers(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set("X-User-ID", tenantId);
        return headers;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RuntimeResponse(
            CloudLlmSource source,
            boolean cloudReady,
            String accessToken,
            String installId,
            String cloudApiUrl
    ) {
    }
}
