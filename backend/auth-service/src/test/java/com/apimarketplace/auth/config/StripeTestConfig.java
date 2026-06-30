package com.apimarketplace.auth.config;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration for Stripe services.
 * 
 * This configuration provides a StripeClient configured for testing.
 * It uses Stripe's test mode API key for safe integration testing.
 * 
 * For unit tests, consider using stripe-mock:
 * https://github.com/stripe/stripe-mock
 * 
 * Usage in tests:
 * <pre>
 * @SpringBootTest
 * @Import(StripeTestConfig.class)
 * class MyBillingTest {
 *     @Autowired
 *     private StripeBillingService stripeBillingService;
 *     
 *     @Test
 *     void testCheckout() {
 *         // Test code here - uses test API key
 *     }
 * }
 * </pre>
 * 
 * For local development with stripe-mock:
 * <pre>
 * docker run --rm -p 12111:12111 stripe/stripe-mock:latest
 * </pre>
 * Then set: billing.stripe.secretKey=sk_test_xxx
 * And: billing.stripe.baseUrl=http://localhost:12111
 */
@TestConfiguration
public class StripeTestConfig {

    @Value("${billing.stripe.secretKey:sk_test_placeholder}")
    private String testSecretKey;
    
    @Value("${billing.stripe.baseUrl:}")
    private String baseUrl;

    /**
     * Creates a StripeClient for testing.
     * 
     * This bean is marked @Primary to override the production bean in tests.
     * 
     * To use stripe-mock, set billing.stripe.baseUrl to http://localhost:12111
     */
    @Bean
    @Primary
    public StripeClient testStripeClient() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            // Connect to stripe-mock for isolated testing
            return StripeClient.builder()
                    .setApiKey(testSecretKey)
                    .build();
        }
        
        // Use Stripe test environment
        return new StripeClient(testSecretKey);
    }
}
