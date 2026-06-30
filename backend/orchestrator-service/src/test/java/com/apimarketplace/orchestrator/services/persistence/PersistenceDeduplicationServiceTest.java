package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PersistenceDeduplicationService.
 *
 * This service is now a no-op: deduplication is handled at DB level
 * via INSERT ... ON CONFLICT DO NOTHING. It only implements RunScopedCache
 * to avoid breaking the cache registry.
 */
@DisplayName("PersistenceDeduplicationService")
class PersistenceDeduplicationServiceTest {

    private PersistenceDeduplicationService service;

    @BeforeEach
    void setUp() {
        service = new PersistenceDeduplicationService();
    }

    @Test
    @DisplayName("Should implement RunScopedCache interface")
    void shouldImplementRunScopedCache() {
        assertInstanceOf(RunScopedCache.class, service);
    }

    @Test
    @DisplayName("Should return cache name")
    void shouldReturnCacheName() {
        assertEquals("PersistenceDeduplicationCache", service.getCacheName());
    }

    @Test
    @DisplayName("Should return PERSISTENCE domain")
    void shouldReturnPersistenceDomain() {
        assertEquals(RunScopedCache.CacheDomain.PERSISTENCE, service.getDomain());
    }

    @Test
    @DisplayName("Should return 0 cache size (no-op)")
    void shouldReturnZeroCacheSize() {
        assertEquals(0, service.getCacheSize());
    }

    @Test
    @DisplayName("Should not throw on cleanupRun (no-op)")
    void shouldNotThrowOnCleanupRun() {
        assertDoesNotThrow(() -> service.cleanupRun("run-123"));
    }
}
