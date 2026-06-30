package com.apimarketplace.orchestrator.services.stepbystep;

import com.apimarketplace.orchestrator.domain.PendingSignalEntity;
import com.apimarketplace.orchestrator.repository.PendingSignalRepository;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingSignalDbService")
class PendingSignalDbServiceTest {

    @Mock
    private PendingSignalRepository repository;

    private PendingSignalDbService service;

    @BeforeEach
    void setUp() {
        service = new PendingSignalDbService(repository);
    }

    @Nested
    @DisplayName("Pending signal operations")
    class PendingSignalTests {

        @Test
        @DisplayName("markPending() should save entity when not already pending")
        void markPendingShouldSave() {
            when(repository.existsPending("run-1", "0", "mcp:step1")).thenReturn(false);

            service.markPending("run-1", "0", "mcp:step1");

            verify(repository).save(any(PendingSignalEntity.class));
        }

        @Test
        @DisplayName("markPending() should skip when already pending")
        void markPendingShouldSkipWhenAlreadyPending() {
            when(repository.existsPending("run-1", "0", "mcp:step1")).thenReturn(true);

            service.markPending("run-1", "0", "mcp:step1");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("isPending() should delegate to repository")
        void isPendingShouldDelegate() {
            when(repository.existsPending("run-1", "0", "mcp:step1")).thenReturn(true);

            assertTrue(service.isPending("run-1", "0", "mcp:step1"));
        }

        @Test
        @DisplayName("removePending() should delegate to repository")
        void removePendingShouldDelegate() {
            service.removePending("run-1", "0", "mcp:step1");

            verify(repository).deletePending("run-1", "0", "mcp:step1");
        }

        @Test
        @DisplayName("getPendingNodeIds() should delegate to repository")
        void getPendingNodeIdsShouldDelegate() {
            Set<String> expected = Set.of("mcp:step1", "mcp:step2");
            when(repository.findPendingNodeIds("run-1")).thenReturn(expected);

            Set<String> result = service.getPendingNodeIds("run-1");

            assertEquals(expected, result);
        }
    }

    @Nested
    @DisplayName("Pre-approved signal operations")
    class PreApprovedSignalTests {

        @Test
        @DisplayName("markPreApproved() should save entity with expiry")
        void markPreApprovedShouldSaveWithExpiry() {
            when(repository.existsPreApproved("run-1", "0", "mcp:step1")).thenReturn(false);

            service.markPreApproved("run-1", "0", "mcp:step1");

            ArgumentCaptor<PendingSignalEntity> captor = ArgumentCaptor.forClass(PendingSignalEntity.class);
            verify(repository).save(captor.capture());
            assertNotNull(captor.getValue());
        }

        @Test
        @DisplayName("markPreApproved() should skip when already pre-approved")
        void markPreApprovedShouldSkipWhenExists() {
            when(repository.existsPreApproved("run-1", "0", "mcp:step1")).thenReturn(true);

            service.markPreApproved("run-1", "0", "mcp:step1");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("isPreApproved() should return true for non-expired signal")
        void isPreApprovedShouldReturnTrueForValid() {
            PendingSignalEntity entity = mock(PendingSignalEntity.class);
            when(entity.isExpired()).thenReturn(false);
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.of(entity));

            assertTrue(service.isPreApproved("run-1", "0", "mcp:step1"));
        }

        @Test
        @DisplayName("isPreApproved() should return false for expired signal")
        void isPreApprovedShouldReturnFalseForExpired() {
            PendingSignalEntity entity = mock(PendingSignalEntity.class);
            when(entity.isExpired()).thenReturn(true);
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.of(entity));

            assertFalse(service.isPreApproved("run-1", "0", "mcp:step1"));
        }

        @Test
        @DisplayName("isPreApproved() should return false when not found")
        void isPreApprovedShouldReturnFalseWhenNotFound() {
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.empty());

            assertFalse(service.isPreApproved("run-1", "0", "mcp:step1"));
        }

        @Test
        @DisplayName("getPreApprovedNodeIds() should delegate to repository")
        void getPreApprovedNodeIdsShouldDelegate() {
            Set<String> expected = Set.of("mcp:step1");
            when(repository.findPreApprovedNodeIds("run-1")).thenReturn(expected);

            assertEquals(expected, service.getPreApprovedNodeIds("run-1"));
        }
    }

    @Nested
    @DisplayName("consumePreApproval()")
    class ConsumePreApprovalTests {

        @Test
        @DisplayName("Should consume and return true for valid pre-approval")
        void shouldConsumeValid() {
            PendingSignalEntity entity = mock(PendingSignalEntity.class);
            when(entity.isExpired()).thenReturn(false);
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.of(entity));

            assertTrue(service.consumePreApproval("run-1", "0", "mcp:step1"));

            verify(repository).delete(entity);
        }

        @Test
        @DisplayName("Should delete expired pre-approval and return false")
        void shouldDeleteExpiredAndReturnFalse() {
            PendingSignalEntity entity = mock(PendingSignalEntity.class);
            when(entity.isExpired()).thenReturn(true);
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.of(entity));

            assertFalse(service.consumePreApproval("run-1", "0", "mcp:step1"));

            verify(repository).delete(entity);
        }

        @Test
        @DisplayName("Should return false when no pre-approval exists")
        void shouldReturnFalseWhenNotExists() {
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.empty());

            assertFalse(service.consumePreApproval("run-1", "0", "mcp:step1"));
        }
    }

    @Nested
    @DisplayName("consumeWildcardPreApproval()")
    class ConsumeWildcardTests {

        @Test
        @DisplayName("Should try wildcard (*) first then default (0)")
        void shouldTryWildcardThenDefault() {
            // Wildcard not found
            when(repository.findPreApproved("run-1", "*", "mcp:step1"))
                .thenReturn(Optional.empty());

            // Default item "0" found
            PendingSignalEntity entity = mock(PendingSignalEntity.class);
            when(entity.isExpired()).thenReturn(false);
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.of(entity));

            assertTrue(service.consumeWildcardPreApproval("run-1", "mcp:step1"));
        }

        @Test
        @DisplayName("Should return false when neither wildcard nor default found")
        void shouldReturnFalseWhenNeitherFound() {
            when(repository.findPreApproved("run-1", "*", "mcp:step1"))
                .thenReturn(Optional.empty());
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.empty());

            assertFalse(service.consumeWildcardPreApproval("run-1", "mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Combined operations")
    class CombinedOperationTests {

        @Test
        @DisplayName("signalNode() should remove pending and consume pre-approval")
        void signalNodeShouldRemoveAndConsume() {
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.empty());

            boolean result = service.signalNode("run-1", "0", "mcp:step1");

            assertTrue(result);
            verify(repository).deletePending("run-1", "0", "mcp:step1");
        }

        @Test
        @DisplayName("shouldAutoExecute() should check specific then wildcard pre-approval")
        void shouldAutoExecuteShouldCheckBoth() {
            // Specific pre-approval found
            PendingSignalEntity entity = mock(PendingSignalEntity.class);
            when(entity.isExpired()).thenReturn(false);
            when(repository.findPreApproved("run-1", "0", "mcp:step1"))
                .thenReturn(Optional.of(entity));

            assertTrue(service.shouldAutoExecute("run-1", "0", "mcp:step1"));
        }

        @Test
        @DisplayName("shouldAutoExecute() should return false when no pre-approvals")
        void shouldAutoExecuteShouldReturnFalse() {
            when(repository.findPreApproved(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

            assertFalse(service.shouldAutoExecute("run-1", "0", "mcp:step1"));
        }
    }

    @Nested
    @DisplayName("Cleanup operations")
    class CleanupTests {

        @Test
        @DisplayName("deleteByRunId() should delegate to repository")
        void deleteByRunIdShouldDelegate() {
            service.deleteByRunId("run-1");
            verify(repository).deleteByRunId("run-1");
        }

        @Test
        @DisplayName("cleanupExpiredPreApprovals() should delete expired signals")
        void cleanupExpiredShouldDelegate() {
            when(repository.deleteExpiredPreApprovals(any(Instant.class))).thenReturn(5);

            service.cleanupExpiredPreApprovals();

            verify(repository).deleteExpiredPreApprovals(any(Instant.class));
        }
    }

    @Nested
    @DisplayName("RunScopedCache implementation")
    class RunScopedCacheTests {

        @Test
        @DisplayName("Should implement RunScopedCache")
        void shouldImplementInterface() {
            assertInstanceOf(RunScopedCache.class, service);
        }

        @Test
        @DisplayName("Should return correct cache name")
        void shouldReturnCacheName() {
            assertEquals("PendingSignalDbService", service.getCacheName());
        }

        @Test
        @DisplayName("Should return CONTROL_FLOW domain")
        void shouldReturnDomain() {
            assertEquals(RunScopedCache.CacheDomain.CONTROL_FLOW, service.getDomain());
        }

        @Test
        @DisplayName("cleanupRun() should delegate to deleteByRunId()")
        void cleanupRunShouldDelegate() {
            service.cleanupRun("run-1");
            verify(repository).deleteByRunId("run-1");
        }

        @Test
        @DisplayName("getCacheSize() should return repository count")
        void getCacheSizeShouldReturnCount() {
            when(repository.count()).thenReturn(42L);
            assertEquals(42, service.getCacheSize());
        }

        @Test
        @DisplayName("getPendingItemIdsForNode() should delegate to repository")
        void getPendingItemIdsShouldDelegate() {
            Set<String> expected = Set.of("0", "1");
            when(repository.findPendingItemIdsForNode("run-1", "mcp:step1"))
                .thenReturn(expected);

            assertEquals(expected, service.getPendingItemIdsForNode("run-1", "mcp:step1"));
        }
    }
}
