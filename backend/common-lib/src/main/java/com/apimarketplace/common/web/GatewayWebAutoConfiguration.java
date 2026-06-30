package com.apimarketplace.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Auto-configuration that registers the shared gateway authentication filter
 * and tenant resolver beans for servlet-based services.
 *
 * <p>Uses {@code @ConditionalOnWebApplication(type = SERVLET)} to prevent
 * activation in the reactive gateway (which uses its own WebFlux-based filter).</p>
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li><b>microservice</b> (default): Registers {@link GatewayAuthenticationFilter}
 *       which validates HMAC signature from the API gateway.</li>
 *   <li><b>monolith</b>: Registers {@link MonolithSecurityFilter} which validates
 *       JWT directly and injects X-User-ID header (no gateway needed).</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(GatewayFilterProperties.class)
public class GatewayWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TenantResolver tenantResolver() {
        return new TenantResolver();
    }

    /**
     * PR25.2 - register the MDC context filter so every servlet-based service
     * gets org/tenant/user tags in logs by default. No conditional - every
     * service benefits from the observability tag.
     */
    @Bean
    @ConditionalOnMissingBean
    public MdcContextFilter mdcContextFilter() {
        return new MdcContextFilter();
    }

    @Bean
    @ConditionalOnMissingBean(GatewayAuthenticationFilter.class)
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    public GatewayAuthenticationFilter gatewayAuthenticationFilter(GatewayFilterProperties properties) {
        return new GatewayAuthenticationFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean(MonolithSecurityFilter.class)
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
    public MonolithSecurityFilter monolithSecurityFilter(GatewayFilterProperties properties) {
        return new MonolithSecurityFilter(
                () -> null, // Host applications should override this with the CE JWT public key.
                properties.getPublicPaths()
        );
    }

    @Bean
    @ConditionalOnMissingBean(ServicePrefixRewriteFilter.class)
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
    public ServicePrefixRewriteFilter servicePrefixRewriteFilter() {
        return new ServicePrefixRewriteFilter();
    }
}
