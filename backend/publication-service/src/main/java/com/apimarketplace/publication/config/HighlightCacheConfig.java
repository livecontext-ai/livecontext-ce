package com.apimarketplace.publication.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine cache for the public {@code GET /api/publications/highlights/{displayMode}}
 * endpoint. Highlights change rarely (admin curation event), so a 60s TTL gives
 * us strong burst protection on anonymous traffic without ever serving stale
 * data for more than a minute. Manual eviction on PUT (see
 * {@code PublicationHighlightService#replaceHighlights}) keeps the freshest
 * write visible to authenticated clients immediately.
 *
 * <p><b>Co-existence with the default {@code cacheManager}.</b> The
 * {@code common-storage-service} module already defines a {@link CacheManager}
 * bean named {@code cacheManager} (a {@link org.springframework.cache.concurrent.ConcurrentMapCacheManager
 * ConcurrentMapCacheManager} for storage quotas). To avoid bean-name collision
 * we register OUR Caffeine manager under a distinct name
 * ({@code highlightsCacheManager}) and reference it explicitly from the
 * {@code @Cacheable}/{@code @CacheEvict} annotations on the highlight service.
 * {@code @EnableCaching} is already active via {@code StorageConfig}.</p>
 */
@Configuration
public class HighlightCacheConfig {

    public static final String HIGHLIGHTS_CACHE = "highlightsByMode";
    public static final String HIGHLIGHTS_CACHE_MANAGER = "highlightsCacheManager";

    @Bean(HIGHLIGHTS_CACHE_MANAGER)
    public CacheManager highlightsCacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(HIGHLIGHTS_CACHE);
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(64));
        return mgr;
    }
}
