package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.auth.client.AuthClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The language and time zone of the person a message goes to: the {@code tenantId} of the
 * notification row (or incident), which is always the RECIPIENT, never the workflow's owner.
 * auth-service owns the values (V527 {@code users.locale}, {@code users.time_zone}); they
 * are read through auth-client, never from the auth schema.
 *
 * <p>Cached per recipient (bounded, 10 minutes by default): a digest pass or a burst of
 * failures would otherwise ask auth-service the same question once per message. Only a
 * real answer is cached; the fallback (auth-service unreachable, unknown user) is not,
 * so an outage never pins a French reader to English for ten minutes.
 *
 * <p>Never throws: any failure is English and UTC, so the message still goes out.
 */
@Component
public class NotificationLocaleResolver {

    private static final Logger logger = LoggerFactory.getLogger(NotificationLocaleResolver.class);
    static final long MAX_ENTRIES = 10_000;

    private final AuthClient authClient;
    private final Cache<String, NotificationLocale> cache;

    @Autowired
    public NotificationLocaleResolver(AuthClient authClient,
                                      @Value("${notifications.delivery.locale-cache-ttl:PT10M}") Duration ttl) {
        this(authClient, ttl, Ticker.systemTicker());
    }

    NotificationLocaleResolver(AuthClient authClient, Duration ttl, Ticker ticker) {
        this.authClient = authClient;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .build();
    }

    public NotificationLocale resolve(String recipientId) {
        if (recipientId == null || recipientId.isBlank()) return NotificationLocale.DEFAULT;
        NotificationLocale cached = cache.getIfPresent(recipientId);
        if (cached != null) return cached;
        try {
            AuthClient.LocaleContext ctx = authClient.getLocaleContext(recipientId);
            if (ctx == null) return NotificationLocale.DEFAULT;
            NotificationLocale resolved = NotificationLocale.of(ctx.locale(), ctx.timeZone());
            if (!ctx.fallback()) cache.put(recipientId, resolved);
            return resolved;
        } catch (RuntimeException ex) {
            // AuthClient never throws by contract; this guards a future change of that contract.
            logger.debug("[notification-delivery] locale of {} unavailable: {}", recipientId, ex.getMessage());
            return NotificationLocale.DEFAULT;
        }
    }
}
