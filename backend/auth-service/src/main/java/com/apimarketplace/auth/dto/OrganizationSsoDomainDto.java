package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.OrganizationSsoDomain;

import java.time.Instant;
import java.util.UUID;

/**
 * A claimed SSO domain plus the exact DNS record the admin must publish to verify it.
 */
public record OrganizationSsoDomainDto(
        UUID id,
        String domain,
        boolean verified,
        Instant verifiedAt,
        Instant lastCheckedAt,
        String txtRecordName,
        String txtRecordValue
) {
    public static OrganizationSsoDomainDto of(OrganizationSsoDomain d, String recordName, String recordValue) {
        return new OrganizationSsoDomainDto(d.getId(), d.getDomain(), d.isVerified(), d.getVerifiedAt(),
                d.getLastCheckedAt(), recordName, recordValue);
    }
}
