package com.apimarketplace.common.storage.config;

import com.apimarketplace.common.mapping.SimpleMappingService;
import com.apimarketplace.common.storage.service.api.StorageOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;

import static com.apimarketplace.common.storage.config.StorageConstants.*;

/**
 * Configuration pour le Common Storage Service.
 * Centralise les beans et la configuration du module.
 */
@Configuration
@EnableCaching
public class StorageConfig {

    private static final Logger logger = LoggerFactory.getLogger(StorageConfig.class);

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Bean SimpleMappingService pour le mapping des donnees.
     */
    @Bean
    public SimpleMappingService simpleMappingService() {
        return new SimpleMappingService();
    }

    /**
     * Bean WebClient.Builder pre-configure pour les appels HTTP.
     * Permet l'injection de dependance au lieu de creation inline.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * Configuration du cache unique pour tous les usages.
     * Utilise les constantes centralisees pour les noms de cache.
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        cacheManager.setCacheNames(Arrays.asList(
            CACHE_QUOTA_STATUS,
            CACHE_TENANT_QUOTA,
            CACHE_ORG_QUOTA_STATUS,
            CACHE_ORG_QUOTA,
            CACHE_STORAGE_DATA,
            CACHE_MAPPING_RESULTS,
            CACHE_MAPPING_SPECS
        ));
        cacheManager.setAllowNullValues(false);
        return cacheManager;
    }

    /**
     * Nettoyage automatique des storages expires.
     * Utilise la constante CLEANUP_INTERVAL_MS.
     */
    @Scheduled(fixedRate = CLEANUP_INTERVAL_MS)
    @SchedulerLock(name = "storage_expired_cleanup", lockAtMostFor = "PT10M")
    public void cleanupExpiredStorages() {
        try {
            // Injection differee pour eviter le cycle de dependance
            StorageOperations storageService = applicationContext.getBean(StorageOperations.class);
            logger.info("Demarrage nettoyage automatique des storages expires");
            int cleaned = storageService.cleanupExpired();
            logger.info("Nettoyage termine: {} storages supprimes", cleaned);
        } catch (Exception e) {
            logger.error("Erreur lors du nettoyage automatique", e);
        }
    }
}
