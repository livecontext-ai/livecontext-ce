package com.apimarketplace.common.scaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Readiness probe for Redis-backed scaling infrastructure.
 *
 * <p>Checks Redis connectivity by issuing a PING command.
 * Only active when {@code scaling.backend=redis} is configured.
 *
 * <p>Reports UP when Redis is reachable, DOWN otherwise.
 * This allows Kubernetes/load balancers to route traffic only to
 * instances with a healthy Redis connection.
 */
@Component
@ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
public class ScalingReadinessIndicator extends AbstractHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ScalingReadinessIndicator.class);

    private final RedisConnectionFactory connectionFactory;

    public ScalingReadinessIndicator(RedisConnectionFactory connectionFactory) {
        super("Redis scaling readiness check failed");
        this.connectionFactory = connectionFactory;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            String pong;
            try (var connection = connectionFactory.getConnection()) {
                pong = connection.ping();
            }
            builder.up()
                   .withDetail("status", "connected")
                   .withDetail("response", pong);
            log.debug("Redis scaling readiness check passed");
        } catch (Exception e) {
            log.warn("Redis scaling readiness check failed: {}", e.getMessage());
            builder.down()
                   .withDetail("status", "disconnected")
                   .withDetail("error", e.getMessage());
        }
    }
}
