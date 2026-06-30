package com.apimarketplace.publication.dto;

/**
 * Combined check response: existing link (if any) + quota configuration.
 * Replaces two separate calls (getAll + getConfig) with a single targeted lookup.
 */
public record SharedLinkCheckResponse(
        SharedLinkResponse link,           // null if no existing link found
        SharedLinkConfigResponse config
) {}
