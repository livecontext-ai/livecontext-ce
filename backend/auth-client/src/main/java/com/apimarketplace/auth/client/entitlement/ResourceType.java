package com.apimarketplace.auth.client.entitlement;

/**
 * Resource types subject to per-plan creation limits.
 * The string value is the canonical key used in HTTP/JSON contracts and in
 * {@code Plan.getResourceLimit(String)} on the auth-service side.
 */
public enum ResourceType {
    WORKFLOW,
    AGENT,
    DATASOURCE,
    INTERFACE,
    APPLICATION,
    /** Marketplace publications the org has PUBLISHED (distinct from APPLICATION = acquired). */
    PUBLICATION;

    public String key() {
        return name();
    }
}
