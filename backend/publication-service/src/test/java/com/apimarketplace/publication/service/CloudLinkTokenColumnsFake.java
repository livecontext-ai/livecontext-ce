package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.apimarketplace.publication.repository.CloudLinkTokens;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Wires the token-column queries of a MOCKED {@link CeCloudLinkRepository} onto whatever entity
 * the test stubbed {@code findByTenantId} with, the way the database row backs them in
 * production: {@code findTokensByTenantId} reads that entity's token fields, {@code updateTokens}
 * / {@code clearCachedAccessToken} / {@code touchLastUsedAt} write into it. Tests that share ONE
 * entity instance between every read keep their meaning; the real-database behaviour (distinct
 * instances, {@code updatable = false}) is pinned by {@code CloudLinkTokenColumnsPersistenceTest}.
 *
 * <p>Stubbed with {@code doAnswer(..).when(mock)} so wiring the same mock twice never runs an
 * existing answer while stubbing.
 */
final class CloudLinkTokenColumnsFake {

    private CloudLinkTokenColumnsFake() {
    }

    static void backByStubbedEntity(CeCloudLinkRepository repository) {
        lenient().doAnswer(inv -> repository.findByTenantId(inv.getArgument(0)).map(link -> new CloudLinkTokens(
                        link.getEncryptedRefreshToken(), link.getCachedAccessToken(), link.getTokenExpiresAt())))
                .when(repository).findTokensByTenantId(any());
        lenient().doAnswer(inv -> {
            CeCloudLinkEntity link = repository.findByTenantId(inv.getArgument(0)).orElse(null);
            if (link == null) {
                return 0;
            }
            link.setEncryptedRefreshToken(inv.getArgument(1));
            link.setCachedAccessToken(inv.getArgument(2));
            link.setTokenExpiresAt(inv.<Instant>getArgument(3));
            link.setLastUsedAt(inv.<Instant>getArgument(4));
            return 1;
        }).when(repository).updateTokens(any(), any(), any(), any(), any());
        lenient().doAnswer(inv -> {
            CeCloudLinkEntity link = repository.findByTenantId(inv.getArgument(0)).orElse(null);
            if (link == null) {
                return 0;
            }
            link.setCachedAccessToken(null);
            link.setTokenExpiresAt(null);
            return 1;
        }).when(repository).clearCachedAccessToken(any());
        lenient().doAnswer(inv -> {
            CeCloudLinkEntity link = repository.findByTenantId(inv.getArgument(0)).orElse(null);
            if (link == null) {
                return 0;
            }
            link.setLastUsedAt(inv.<Instant>getArgument(1));
            return 1;
        }).when(repository).touchLastUsedAt(any(), any());
    }
}
