package com.apimarketplace.auth.config;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Health indicator for Stripe API connectivity.
 *
 * Reports the health of the Stripe connection by attempting to retrieve
 * account balance, which is a lightweight API call.
 *
 * This enables monitoring tools to detect Stripe connectivity issues
 * before they impact user-facing operations.
 *
 * @see <a href="https://stripe.com/docs/api/balance">Stripe Balance API</a>
 */
@Component
@ConditionalOnBean(StripeClient.class)
public class StripeHealthIndicator extends AbstractHealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(StripeHealthIndicator.class);

    private final StripeClient stripeClient;

    public StripeHealthIndicator(StripeClient stripeClient) {
        super("Stripe health check failed");
        this.stripeClient = stripeClient;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            // Lightweight API call to check connectivity
            var balance = stripeClient.balance().retrieve();
            
            builder.up()
                   .withDetail("status", "connected")
                   .withDetail("livemode", balance.getLivemode())
                   .withDetail("availableCount", balance.getAvailable() != null ? balance.getAvailable().size() : 0);
            
            logger.debug("Stripe health check passed");
            
        } catch (StripeException e) {
            logger.warn("Stripe health check failed: {}", e.getMessage());
            
            builder.down()
                   .withDetail("status", "disconnected")
                   .withDetail("error", e.getMessage())
                   .withDetail("code", e.getCode());
        } catch (Exception e) {
            logger.error("Stripe health check error: {}", e.getMessage());
            
            builder.down()
                   .withDetail("status", "error")
                   .withDetail("error", e.getMessage());
        }
    }
}
