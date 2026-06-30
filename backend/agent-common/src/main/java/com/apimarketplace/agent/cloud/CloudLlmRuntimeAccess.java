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
