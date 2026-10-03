package com.apimarketplace.publication.repository;

import java.time.Instant;

/**
 * The token columns of a cloud link, as stored right now (see
 * {@link CeCloudLinkRepository#findTokensByTenantId}).
 */
public record CloudLinkTokens(String encryptedRefreshToken, String cachedAccessToken, Instant tokenExpiresAt) {
}
