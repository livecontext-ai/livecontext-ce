package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which identities own the shipped catalog (LC-002, CASA readiness).
 *
 * <p>An API row is platform-owned when its {@code created_by} is one of these identities: the
 * catalog importer's {@code importer.catalog.system-user} ({@code CATALOG_SYSTEM_USER}, default
 * {@code system}) and the bundle applier's {@code SYSTEM}. {@code created_by} is set from the
 * gateway-validated caller identity, so unlike {@code source} (which a submission body carries)
 * a user cannot claim it.
 */
@Component
public class PlatformOwnership {

    private final Set<String> ownerIds;

    public PlatformOwnership(@Value("${catalog.platform-owner-ids:${CATALOG_SYSTEM_USER:system},SYSTEM}") String csv) {
        this.ownerIds = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isPlatformOwner(String userId) {
        return userId != null && ownerIds.contains(userId);
    }

    public boolean isPlatformOwned(ApiEntity api) {
        return api != null && isPlatformOwner(api.getCreatedBy());
    }
}
