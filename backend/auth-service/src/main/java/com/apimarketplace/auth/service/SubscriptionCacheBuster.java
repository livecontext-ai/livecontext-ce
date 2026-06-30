package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Single shared "an owner's plan changed - refresh everyone who sees it" cache-bust pipe.
 *
 * <p>When an owner's subscription plan changes, the gateway user-resolution cache (5-min TTL)
 * must be invalidated for:
 * <ol>
 *   <li>the owner themselves (their own {@code plan} / {@code activeOrgPlan}); and</li>
 *   <li>every member of every org the owner OWNS - those members resolve the owner's plan as
 *       their {@code activeOrgPlan} when acting in that workspace, so a FREE→TEAM change must
 *       unlock the workspace for them on their NEXT request instead of after the TTL.</li>
 * </ol>
 *
 * <p>Extracted from {@code WebhookController} (the Stripe-webhook path, "PR9") so the admin
 * comp-plan grant ({@link AdminPlanService}) goes through the EXACTLY SAME entitlement-propagation
 * pipe - no parallel, drift-prone copy. Both the Stripe flow and the admin grant call
 * {@link #fanOutForOwner(Long, String)}.
 *
 * <p>Best-effort &amp; null-tolerant: any unwired optional bean (CE mode, slim test fixture) makes
 * the method a no-op. {@link GatewayCacheClient} logs+swallows on transport failure, and the whole
 * body is wrapped, so a cache-bust hiccup never fails the caller's transaction.
 */
@Service
public class SubscriptionCacheBuster {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCacheBuster.class);

    // Optional injection (required=false) so tests / CE mode without the cache wiring keep
    // working - mirrors the original WebhookController field-injection contract.
    @Autowired(required = false)
    private GatewayCacheClient gatewayCacheClient;
    @Autowired(required = false)
    private UserRepository userRepository;
    @Autowired(required = false)
    private OrganizationRepository organizationRepository;
    @Autowired(required = false)
    private OrganizationMemberRepository organizationMemberRepository;

    /**
     * Invalidate the gateway cache for the owner and every member of every org they own.
     *
     * @param ownerUserId the user whose plan changed (the wallet/capability owner)
     * @param reason       short label for logs (e.g. {@code "subscription.upsert"},
     *                     {@code "admin.plan.grant"})
     */
    public void fanOutForOwner(Long ownerUserId, String reason) {
        if (gatewayCacheClient == null || userRepository == null) {
            log.debug("Cache fan-out skipped - gateway client or user repo not wired (reason={})", reason);
            return;
        }
        try {
            // 1. Bust the owner's own cache.
            userRepository.findById(ownerUserId).ifPresent(u -> {
                String providerId = u.getProviderId();
                if (providerId != null && !providerId.isBlank()) {
                    gatewayCacheClient.invalidateUserCache(providerId);
                }
            });

            // 2. Bust every member of every org the owner owns. Iteration is bounded by
            //    member count × owned-org count - typically < 100.
            if (organizationRepository != null && organizationMemberRepository != null) {
                int memberCount = 0;
                var ownedOrgs = organizationRepository.findByOwnerId(ownerUserId);
                for (Organization org : ownedOrgs) {
                    if (org.isDeleted()) continue;
                    var members = organizationMemberRepository.findByOrganization_Id(org.getId());
                    for (OrganizationMember m : members) {
                        User memberUser = m.getUser();
                        if (memberUser == null) continue;
                        String providerId = memberUser.getProviderId();
                        if (providerId != null && !providerId.isBlank()) {
                            gatewayCacheClient.invalidateUserCache(providerId);
                            memberCount++;
                        }
                    }
                }
                log.info("Cache fan-out: invalidated {} member caches across {} orgs owned by user {} (reason={})",
                        memberCount, ownedOrgs.size(), ownerUserId, reason);
            }
        } catch (Exception e) {
            log.warn("Cache fan-out failed for user {} (reason={}): {}", ownerUserId, reason, e.getMessage());
        }
    }
}
