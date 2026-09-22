package com.apimarketplace.catalog.service.credential;

import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.http.CredentialIdentityMatcher;
import com.apimarketplace.credential.client.dto.CredentialIdentityDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Builds the endpoint capability block for one caller: the integration policy, the
 * caller's accounts that belong to the integration, and the sentence that says what to
 * do about it.
 *
 * <p>Two agent surfaces use it and they must not answer differently: the tool contract
 * a caller reads BEFORE it calls, and the refusal it gets when it called anyway. The
 * refusal is the one that used to be wrong, and a refusal that contradicts the contract
 * is worse than either being wrong alone.
 *
 * <p><b>Fail-open, always, and silence is not the same as zero.</b> Every lookup here
 * is an enrichment of an answer the caller already has, so an unreadable template or a
 * missing integration name simply drops what could not be worked out. The one that has
 * to be handled deliberately is an unreachable credential service: the ordinary listing
 * answers a failure with an EMPTY list, which is indistinguishable from "this account
 * has no credentials", and reporting that to a person tells them to connect a service
 * they may already have connected. So the accounts are read through the listing that
 * can say it could not look, and an unavailable listing hands {@code null} down rather
 * than an empty list. Nothing in this class may decide whether a call runs.
 */
@Slf4j
@Service
public class EndpointCredentialCapabilityService {

    private final IntegrationScopePolicyReader policyReader;
    private final UserCredentialService userCredentialService;

    public EndpointCredentialCapabilityService(IntegrationScopePolicyReader policyReader,
                                               UserCredentialService userCredentialService) {
        this.policyReader = policyReader;
        this.userCredentialService = userCredentialService;
    }

    /**
     * The block a caller reads, or {@code base} unchanged when nothing could be added.
     *
     * @param base           the {@code {type, requiredScopes}} block from the contract
     * @param integration    the integration's credential name this endpoint requires
     * @param requiredScopes the scopes the endpoint declares
     * @param tenantId       whose accounts to look at; blank means the accounts are
     *                       unknown, which is reported as unknown and never as none
     */
    public Map<String, Object> describe(Map<String, Object> base,
                                        String integration,
                                        List<String> requiredScopes,
                                        String tenantId) {
        if (base == null) {
            return null;
        }
        if (integration == null || integration.isBlank()) {
            return base;
        }
        try {
            IntegrationScopePolicy policy = policyReader.forIntegration(integration);
            List<EndpointCredentialCapability.Account> accounts = accountsOf(integration, tenantId);
            return EndpointCredentialCapability.enrich(base, integration, requiredScopes,
                    policy, accounts);
        } catch (Exception e) {
            log.debug("Credential capability unavailable for integration {}: {}",
                    integration, e.getMessage());
            return base;
        }
    }

    /**
     * Whether a standard connection to this integration could ever grant these scopes.
     *
     * <p>Tenant-free, so a refusal can consult it without a second credential lookup.
     */
    public List<String> scopesNeedingOwnOAuthClient(String integration, List<String> requiredScopes) {
        if (integration == null || integration.isBlank() || requiredScopes == null
                || requiredScopes.isEmpty()) {
            return List.of();
        }
        try {
            return policyReader.forIntegration(integration).scopesNeedingOwnOAuthClient(requiredScopes);
        } catch (Exception e) {
            log.debug("Scope policy unavailable for integration {}: {}", integration, e.getMessage());
            return List.of();
        }
    }

    /**
     * The caller's credentials the runtime would consider for this integration.
     *
     * <p>Matched through {@link CredentialIdentityMatcher}, the matcher the executor
     * itself resolves with, so this listing cannot offer an account the run would
     * refuse nor hide one it would accept. Identities only: deciding which account was
     * meant must never fetch the material of the ones it is about to reject.
     */
    private List<EndpointCredentialCapability.Account> accountsOf(String integration, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            // Nobody to look at. Answering "no accounts" here would be a statement about
            // an account nobody named, and it is the statement that sends a person to
            // connect a service they may already have.
            return null;
        }
        String requirement = integration.trim();
        String bareIntegration = CredentialIdentityMatcher.integrationOfRequirement(requirement);
        List<CredentialIdentityDto> identities =
                userCredentialService.tryListIdentities(tenantId).orElse(null);
        if (identities == null) {
            return null;
        }
        return identities.stream()
                .filter(c -> CredentialIdentityMatcher.matchesRequirement(
                        bareIntegration, requirement, c.getIntegration(), c.getName()))
                .map(c -> new EndpointCredentialCapability.Account(
                        c.getName(),
                        c.getIntegration(),
                        c.getStatus(),
                        // The auth mechanism, which decides whether the endpoint's scope
                        // requirement applies to this account at all. Carried rather than
                        // assumed: an integration can be connected through several
                        // variants, and only the OAuth one has scopes to compare.
                        c.getType(),
                        c.isDefault(),
                        c.getScopes()))
                .toList();
    }
}
