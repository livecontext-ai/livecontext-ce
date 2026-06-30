package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.config.RedisCacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotCacheService")
class SnapshotCacheServiceTest {

    @Mock
    private RedisCacheConfig cacheConfig;

    private SnapshotCacheService service;

    @BeforeEach
    void setUp() {
        when(cacheConfig.getSnapshotTtl()).thenReturn(Duration.ofMinutes(30));
        service = new SnapshotCacheService(cacheConfig);
    }

    @Nested
    @DisplayName("getSnapshot()")
    class GetSnapshotTests {

        @Test
        @DisplayName("Should return empty for null runId")
        void shouldReturnEmptyForNull() {
            Optional<SnapshotCacheService.SnapshotResult> result = service.getSnapshot(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty when no data cached and no DB")
        void shouldReturnEmptyWhenNoData() {
            Optional<SnapshotCacheService.SnapshotResult> result = service.getSnapshot("run-1");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return cached snapshot after caching")
        void shouldReturnCachedSnapshot() {
            Map<String, Object> snapshot = Map.of("key", "value");
            service.cacheSnapshot("run-1", snapshot, "COMPLETED");

            Optional<SnapshotCacheService.SnapshotResult> result = service.getSnapshot("run-1");
            assertTrue(result.isPresent());
            assertTrue(result.get().isFromCache());
        }
    }

    @Nested
    @DisplayName("cacheSnapshot()")
    class CacheSnapshotTests {

        @Test
        @DisplayName("Should not cache null runId")
        void shouldNotCacheNullRunId() {
            service.cacheSnapshot(null, Map.of("key", "value"), "COMPLETED");
            // No exception thrown, and subsequent get should return empty
            assertTrue(service.getSnapshot("null-run").isEmpty());
        }

        @Test
        @DisplayName("Should not cache null snapshot")
        void shouldNotCacheNullSnapshot() {
            service.cacheSnapshot("run-1", null, "COMPLETED");
            assertTrue(service.getSnapshot("run-1").isEmpty());
        }

        @Test
        @DisplayName("Should not cache empty snapshot")
        void shouldNotCacheEmptySnapshot() {
            service.cacheSnapshot("run-1", Map.of(), "COMPLETED");
            assertTrue(service.getSnapshot("run-1").isEmpty());
        }
    }

    @Nested
    @DisplayName("evict()")
    class EvictTests {

        @Test
        @DisplayName("Should not evict null runId")
        void shouldNotEvictNull() {
            assertDoesNotThrow(() -> service.evict(null));
        }

        @Test
        @DisplayName("Should evict cached entry")
        void shouldEvictCachedEntry() {
            service.cacheSnapshot("run-1", Map.of("key", "value"), "COMPLETED");
            service.evict("run-1");

            assertTrue(service.getSnapshot("run-1").isEmpty());
        }
    }

    @Nested
    @DisplayName("SnapshotResult record")
    class SnapshotResultTests {

        @Test
        @DisplayName("isLive() should return true for LIVE source")
        void isLiveShouldReturnTrue() {
            SnapshotCacheService.SnapshotResult result = new SnapshotCacheService.SnapshotResult(
                Map.of(), null, SnapshotCacheService.SnapshotSource.LIVE
            );
            assertTrue(result.isLive());
            assertFalse(result.isFromCache());
            assertFalse(result.isFromDatabase());
        }

        @Test
        @DisplayName("isFromCache() should return true for CACHE source")
        void isFromCacheShouldReturnTrue() {
            SnapshotCacheService.SnapshotResult result = new SnapshotCacheService.SnapshotResult(
                Map.of(), "COMPLETED", SnapshotCacheService.SnapshotSource.CACHE
            );
            assertTrue(result.isFromCache());
            assertFalse(result.isLive());
        }

        @Test
        @DisplayName("isFromDatabase() should return true for DATABASE source")
        void isFromDatabaseShouldReturnTrue() {
            SnapshotCacheService.SnapshotResult result = new SnapshotCacheService.SnapshotResult(
                Map.of(), "COMPLETED", SnapshotCacheService.SnapshotSource.DATABASE
            );
            assertTrue(result.isFromDatabase());
        }

        @Test
        @DisplayName("hasTerminalStatus() should return true when status is non-null")
        void hasTerminalStatusShouldReturnTrue() {
            SnapshotCacheService.SnapshotResult result = new SnapshotCacheService.SnapshotResult(
                Map.of(), "COMPLETED", SnapshotCacheService.SnapshotSource.CACHE
            );
            assertTrue(result.hasTerminalStatus());
        }

        @Test
        @DisplayName("hasTerminalStatus() should return false when status is null")
        void hasTerminalStatusShouldReturnFalse() {
            SnapshotCacheService.SnapshotResult result = new SnapshotCacheService.SnapshotResult(
                Map.of(), null, SnapshotCacheService.SnapshotSource.LIVE
            );
            assertFalse(result.hasTerminalStatus());
        }
    }
}
