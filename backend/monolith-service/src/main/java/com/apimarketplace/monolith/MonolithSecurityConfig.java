package com.apimarketplace.monolith;

import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.security.JwtKeyPairManager;
import com.apimarketplace.auth.service.ApiKeyService;
import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.common.web.MonolithSecurityFilter;
import com.apimarketplace.publication.service.SharedLinkService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Main SecurityFilterChain for the CE monolith.
 * Permits all requests - actual authentication is handled by MonolithSecurityFilter (servlet filter).
 *
 * This bean MUST be in the monolith package (not via FullyQualifiedBeanNameGenerator on auth-service)
 * so Spring Boot auto-configuration detects it and does NOT create a default Basic-auth chain.
 */
@Configuration
@EnableWebSecurity
public class MonolithSecurityConfig {

    @Bean
    public MonolithSecurityFilter monolithSecurityFilter(GatewayFilterProperties properties,
                                                         JwtKeyPairManager keyPairManager,
                                                         SharedLinkService sharedLinkService,
                                                         ApiKeyService apiKeyService) {
        return new MonolithSecurityFilter(
                keyPairManager::getPublicKey,
                properties.getPublicPaths(),
                token -> sharedLinkService.getByToken(token)
                        .map(link -> new MonolithSecurityFilter.ShareTokenContext(
                                link.getTenantId(),
                                link.getOrganizationId(),
                                link.getResourceType().name(),
                                link.getResourceToken(),
                                link.getResourceId() != null ? link.getResourceId().toString() : null))
                        .orElse(null),
                // API-key auth (X-API-Key / "Bearer lc_live_..."), same trust model as the cloud
                // gateway's ApiKeyResolver: the lc_live_ key resolves to its owner's identity.
                // Scoped multi keys additionally carry the tool allow-list, which the filter
                // injects downstream as X-Api-Key-Scopes (null = legacy/full access = no header).
                apiKey -> {
                    UserResolutionResponse resolved = apiKeyService.resolveByPlaintextKey(apiKey);
                    if (resolved == null || !resolved.canMakeRequest()) {
                        return null;
                    }
                    MonolithSecurityFilter.JwtClaims claims = new MonolithSecurityFilter.JwtClaims(
                            String.valueOf(resolved.getUserId()),
                            resolved.getProviderId(),
                            resolved.getEmail(),
                            resolved.getRoles() != null ? String.join(",", resolved.getRoles()) : "USER",
                            resolved.getDefaultOrganizationId(),
                            resolved.getDefaultOrganizationRole(),
                            resolved.getMemberships().stream()
                                    .map(m -> new MonolithSecurityFilter.OrgMembershipClaim(
                                            m.getOrgId(), m.getRole(), m.isPersonal(), m.isPaused()))
                                    .toList());
                    return new MonolithSecurityFilter.ApiKeyAuth(claims, resolved.getApiKeyScopes());
                }
        );
    }

    @Bean
    public SecurityFilterChain monolithFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().permitAll()
            );
        return http.build();
    }
}
