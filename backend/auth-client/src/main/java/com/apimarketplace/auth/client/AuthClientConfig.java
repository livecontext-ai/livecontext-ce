package com.apimarketplace.auth.client;

import com.apimarketplace.auth.client.access.OrgAccessDeniedExceptionHandler;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.access.OrgAccessGuardImpl;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.LimitExceededExceptionHandler;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.web.AppEditionProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for AuthClient bean.
 * Each consuming service imports this config to get an AuthClient wired to auth-service.
 */
@Configuration
public class AuthClientConfig {

    @Bean
    public AuthClient authClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl) {
        return new AuthClient(authServiceUrl);
    }

    /**
     * Per-plan resource creation gate. CE Free disables checks (no-op). Only the
     * Self-Hosted Enterprise edition fails closed when auth-service cannot resolve
     * a plan limit - the overage protection expected by a licensed enterprise
     * deployment. Managed Cloud (and Dedicated Cloud) stay fail-OPEN on lookup
     * gaps, matching the pre-existing behaviour: a transient/absent subscription
     * ({@code __NONE__}) must never 500 a normal resource-creation call.
     */
    @Bean
    public EntitlementGuard entitlementGuard(
            AuthClient authClient,
            AppEditionProvider editionProvider) {
        return new EntitlementGuard(
                authClient,
                editionProvider.hasCeFreeUnlimitedLocalResources(),
                editionProvider.isSelfHostedEnterprise());
    }

    /**
     * Org-level deny-list guard. Single canonical implementation factorising the
     * four near-identical copies that previously lived in each service. Read
     * filtering + write enforcement (delete/status/clone + diff-sensitive update
     * per PR-2) both call this bean.
     */
    @Bean
    public OrgAccessGuard orgAccessGuard(AuthClient authClient,
                                         ObjectProvider<EventBus> eventBusProvider) {
        return new OrgAccessGuardImpl(authClient, eventBusProvider.getIfAvailable());
    }

    /**
     * Registers the {@link LimitExceededException} → HTTP 409 mapper as a bean
     * so every service importing this config picks it up regardless of their
     * own component scan base package.
     */
    @Bean
    public LimitExceededExceptionHandler limitExceededExceptionHandler() {
        return new LimitExceededExceptionHandler();
    }

    /**
     * Registers the {@link com.apimarketplace.auth.client.access.OrgAccessDeniedException}
     * → HTTP 403 mapper. Required because services typically install a
     * catch-all {@code @ExceptionHandler(Exception.class)} that would
     * otherwise shadow the exception's {@code @ResponseStatus} and return 500.
     */
    @Bean
    public OrgAccessDeniedExceptionHandler orgAccessDeniedExceptionHandler() {
        return new OrgAccessDeniedExceptionHandler();
    }
}
