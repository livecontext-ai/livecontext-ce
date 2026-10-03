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
    public GatewayAuthenticationFilter gatewayAuthenticationFilter(
            GatewayFilterProperties properties,
            @org.springframework.beans.factory.annotation.Value("${gateway.signature.accept-v1:true}") boolean acceptV1,
            @org.springframework.beans.factory.annotation.Value("${gateway.signature.max-skew-seconds:60}") long v2MaxSkewSeconds,
            GatewaySignatureV1OnlyMonitor v1OnlyMonitor) {
        return new GatewayAuthenticationFilter(properties, acceptV1, v2MaxSkewSeconds, v1OnlyMonitor);
    }

    /**
     * CASA LC-035 cutover evidence: counts (and logs once per route and caller) the requests the
     * filter accepted on the v1 signature alone. See {@link GatewaySignatureV1OnlyMonitor}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    public GatewaySignatureV1OnlyMonitor gatewaySignatureV1OnlyMonitor() {
        return new GatewaySignatureV1OnlyMonitor();
    }

    /**
     * Binds {@link GatewaySignatureV1OnlyMonitor} to the service's Micrometer registry, so the
     * count is scraped as {@code gateway_signature_v1_only_total}. Micrometer is optional in
     * common-lib, hence the class condition; the registry is looked up after every singleton is
     * built, so the binding does not depend on auto-configuration order. Without a registry the
     * monitor still logs.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    static class V1OnlyMetricsConfiguration {

        @Bean
        org.springframework.beans.factory.SmartInitializingSingleton gatewaySignatureV1OnlyMetricsBinder(
                org.springframework.beans.factory.ObjectProvider<GatewaySignatureV1OnlyMonitor> monitor,
                org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
            return () -> {
                GatewaySignatureV1OnlyMonitor m = monitor.getIfAvailable();
                io.micrometer.core.instrument.MeterRegistry r = registry.getIfUnique();
                if (m != null && r != null) {
                    // Registered once per series; the monitor caches the handle and bounds the series.
                    m.bindCounter((path, caller) -> io.micrometer.core.instrument.Counter
                            .builder(GatewaySignatureV1OnlyMonitor.METRIC)
                            .description("Requests accepted on the legacy v1 gateway signature alone (no v2 header)")
                            .tag("path", path)
                            .tag("caller", caller)
                            .register(r)::increment);
                }
            };
        }
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
