package com.apimarketplace.agent.gemini.cache;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stage 2 - scheduled driver for {@link GeminiCachedContentManager#sweep()}.
 *
 * <p>Extracted to its own component so the manager itself does not need
 * {@code @Scheduled} - that annotation forces Spring to CGLIB-proxy the
 * target class, which fails at bean instantiation when the target has a
 * parameterised-only constructor ({@code NoSuchMethodException: <init>()}).
 * This sweeper is a thin wrapper: plain POJO-backed Spring bean, proxied
 * normally, delegates to the manager's package-visible {@code sweep()}.
 */
@Component
public class GeminiCacheSweeper {

    private final GeminiCachedContentManager manager;

    public GeminiCacheSweeper(GeminiCachedContentManager manager) {
        this.manager = manager;
    }

    /** 5 minutes - matches {@code GeminiCachedContentManager.SWEEP_INTERVAL_MS}. */
    @Scheduled(fixedRate = 5 * 60 * 1_000L)
    public void sweep() {
        manager.sweep();
    }
}
