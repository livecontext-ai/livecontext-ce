package com.apimarketplace.publication.service;

import com.apimarketplace.common.plan.CloudPlanAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * CE-side adapter exposing the bound cloud account's governing plan to auth-service's
 * {@code PlanLimitService}, so paying on the cloud unlocks the plan's features on the CE.
 *
 * <p>Only instantiated when {@code marketplace.mode=remote} (CE / self-hosted). In the cloud
 * deployment this bean is absent, leaving {@code PlanLimitService}'s optional dependency
 * {@code null} and its local-plan resolution untouched - the delegation is a CE-only concern.
 */
@Component
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class PublicationCloudPlanAccess implements CloudPlanAccess {

    private final CloudLinkService cloudLinkService;

    public PublicationCloudPlanAccess(CloudLinkService cloudLinkService) {
        this.cloudLinkService = cloudLinkService;
    }

    @Override
    public Optional<String> governingPlanCode(Long tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return cloudLinkService.governingCloudPlanCode(tenantId);
    }
}
