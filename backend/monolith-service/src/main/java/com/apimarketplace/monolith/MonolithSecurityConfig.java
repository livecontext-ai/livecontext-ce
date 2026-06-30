package com.apimarketplace.monolith;

import com.apimarketplace.auth.security.JwtKeyPairManager;
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
                                                         SharedLinkService sharedLinkService) {
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
                        .orElse(null)
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
