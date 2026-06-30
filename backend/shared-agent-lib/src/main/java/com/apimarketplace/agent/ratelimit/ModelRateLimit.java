package com.apimarketplace.agent.ratelimit;

/**
 * Model-specific rate limit override.
 *
 * <p>Each field independently overrides the corresponding provider-level limit:
 * <ul>
 *   <li>{@code null} = inherit from provider config for this dimension</li>
 *   <li>{@code 0} = explicitly disable this dimension (no traffic allowed)</li>
 *   <li>{@code > 0} = override limit for this dimension</li>
 * </ul>
 *
 * @param tpm            global tokens-per-minute override
 * @param rpm            global requests-per-minute override
 * @param tpmPerTenant   per-tenant tokens-per-minute override
 * @param rpmPerTenant   per-tenant requests-per-minute override
 */
public record ModelRateLimit(Integer tpm, Integer rpm, Integer tpmPerTenant, Integer rpmPerTenant) {
}
