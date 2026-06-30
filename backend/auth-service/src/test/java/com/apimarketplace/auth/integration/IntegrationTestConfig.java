package com.apimarketplace.auth.integration;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.auth.service.StripeBillingService;
import com.apimarketplace.auth.service.StripeScheduleService;
import com.apimarketplace.auth.service.PlanCacheService;
import com.apimarketplace.auth.service.PriceCacheService;
import com.apimarketplace.auth.util.NonceUtil;
import com.stripe.StripeClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/**
 * Shared test configuration for integration tests.
 * Mocks external services (Stripe, Keycloak, etc.) that are not available during testing.
 */
@TestConfiguration
public class IntegrationTestConfig {

    @MockitoBean
    private StripeClient stripeClient;

    @MockitoBean
    private StripeBillingService stripeBillingService;

    @MockitoBean
    private StripeScheduleService stripeScheduleService;

    @MockitoBean
    private PlanCacheService planCacheService;

    @MockitoBean
    private PriceCacheService priceCacheService;

    @MockitoBean
    private NonceUtil nonceUtil;

    @Bean
    @Primary
    public RestTemplate testRestTemplate() {
        return new RestTemplate();
    }

    /**
     * Disable GatewayAuthenticationFilter for integration tests.
     * Prevents gateway header validation so MockMvc requests can reach controllers.
     */
    @Bean
    public FilterRegistrationBean<GatewayAuthenticationFilter> disableGatewayFilter(
            GatewayAuthenticationFilter filter) {
        FilterRegistrationBean<GatewayAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
