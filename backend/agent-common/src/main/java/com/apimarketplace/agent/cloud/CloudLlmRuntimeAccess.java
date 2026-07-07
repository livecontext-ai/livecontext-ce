package com.apimarketplace.agent.cloud;

import java.util.Optional;

/**
 * Resolves the effective CE LLM source and the cloud relay credentials for a tenant.
 */
public interface CloudLlmRuntimeAccess {

    CloudLlmSource getEffectiveLlmSource(String tenantId);

    Optional<CloudLlmRuntimeCredentials> resolveCloudRuntime(String tenantId);

    /**
     * Install-global cloud credentials for the model-catalog bundle sync, which runs
     * once per CE install rather than per tenant. Present iff this CE install has an
     * active registered cloud link (regardless of the per-tenant CLOUD/BYOK inference
     * choice) - that is the entitlement for catalog updates. Empty otherwise, which
     * makes the bundle sync skip ("no link, no updates"). Default empty for non-CE
     * implementations.
     */
    default Optional<CloudLlmRuntimeCredentials> resolveActiveCloudRuntime() {
        return Optional.empty();
    }

    /**
     * Catalog-credential relay variant of {@link #getEffectiveLlmSource}: the tenant's
     * source for third-party catalog API platform credentials, keyed on the cloud link's
     * {@code catalogSource} instead of {@code llmSource}. The two toggles are independent.
     * Default BYOK (execute locally) for non-CE implementations.
     */
    default CloudLlmSource getEffectiveCatalogSource(String tenantId) {
        return CloudLlmSource.BYOK;
    }

    /**
     * Catalog-credential relay variant of {@link #resolveCloudRuntime}: the cloud relay
     * credentials for catalog tool executions, present only when the link's
     * {@code catalogSource} is CLOUD and the relay is ready. Default empty for non-CE
     * implementations.
     */
    default Optional<CloudLlmRuntimeCredentials> resolveCatalogCloudRuntime(String tenantId) {
        return Optional.empty();
    }

    /**
     * Catalog-credential relay variant of {@link #resolveActiveCloudRuntime}:
     * install-global cloud credentials resolved from THE active registered cloud link,
     * reported against its {@code catalogSource} instead of its {@code llmSource}.
     * Default empty for non-CE implementations.
     */
    default Optional<CloudLlmRuntimeCredentials> resolveActiveCatalogRuntime() {
        return Optional.empty();
    }

    default boolean isCloudSelected(String tenantId) {
        return getEffectiveLlmSource(tenantId) == CloudLlmSource.CLOUD;
    }

    /**
     * Used by tenant-less model catalog endpoints. Implementations may return false
     * when they cannot cheaply determine a platform-wide value.
     */
    default boolean isCloudSelectedForAnyTenant() {
        return false;
    }
}
