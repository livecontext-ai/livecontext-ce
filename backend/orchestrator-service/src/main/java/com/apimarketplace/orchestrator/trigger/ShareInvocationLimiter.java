package com.apimarketplace.orchestrator.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Daily invocation cap for anonymous PUBLIC chat/form share links.
 *
 * <p>A public chat/form link ({@code /chat/{token}}, {@code /form/{token}}) triggers an agent
 * run that spends the OWNER's LLM credits, yet the only throttle on that path is the gateway's
 * per-IP limit - an attacker rotating IPs (or a botnet) can drain the owner's credits at will.
 * This adds two Redis-backed daily counters, checked before the agent fires:
 * <ul>
 *   <li>per share link ({@code share:invoke:token:{token}}) - caps a single abused link;</li>
 *   <li>per owner ({@code share:invoke:owner:{tenantId}}) - caps total spend across all the
 *       owner's public links (mirrors the dormant gateway {@code SHARE_OWNER_LIMIT_PER_DAY}).</li>
 * </ul>
 *
 * <p>Fail-OPEN on Redis errors: a Redis blip must not take down legitimate public chat/form
 * (availability &gt; a bounded abuse window). Disable entirely with {@code share.invocation.enabled=false}.
 */
@Component
public class ShareInvocationLimiter {

    private static final Logger logger = LoggerFactory.getLogger(ShareInvocationLimiter.class);

    private final StringRedisTemplate redis;

    @Value("${share.invocation.enabled:true}")
    private boolean enabled;

    @Value("${share.invocation.per-token-daily-limit:200}")
    private long perTokenDailyLimit;

    @Value("${share.invocation.per-owner-daily-limit:500}")
    private long perOwnerDailyLimit;

    public ShareInvocationLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records one public invocation and returns {@code false} when the per-link or per-owner
     * daily cap is exceeded (the caller should then reject with 429 and NOT fire the agent).
     * Fails open (returns {@code true}) on any Redis error.
     */
    public boolean tryAcquire(String shareToken, String ownerTenantId) {
        if (!enabled) {
            return true;
        }
        try {
            // Per-link first: if this link is exhausted, reject without charging the owner counter.
            if (shareToken != null && !shareToken.isBlank()
                    && !incrementAndCheck("share:invoke:token:" + shareToken, perTokenDailyLimit)) {
                logger.warn("Share invocation per-link daily cap ({}) reached for token {}",
                        perTokenDailyLimit, shareToken);
                return false;
            }
            if (ownerTenantId != null && !ownerTenantId.isBlank()
                    && !incrementAndCheck("share:invoke:owner:" + ownerTenantId, perOwnerDailyLimit)) {
                logger.warn("Share invocation per-owner daily cap ({}) reached for owner {}",
                        perOwnerDailyLimit, ownerTenantId);
                return false;
            }
            return true;
        } catch (Exception e) {
            // Fail open - never let a Redis outage break legitimate public chat/form.
            logger.warn("Share invocation limiter unavailable (failing open): {}", e.getMessage());
            return true;
        }
    }

    private boolean incrementAndCheck(String key, long limit) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // First hit of the day - set the 24h TTL so the counter rolls over.
            redis.expire(key, Duration.ofDays(1));
        }
        return count == null || count <= limit;
    }
}
