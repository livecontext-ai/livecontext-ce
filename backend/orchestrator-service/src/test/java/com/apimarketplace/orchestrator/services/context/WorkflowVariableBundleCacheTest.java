package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowVariableBundleCache.
 *
 * The cache fetches the workflow-variable bundle from auth-service (via
 * CredentialClient) at most once per run, invalidates on cleanupRun, and
 * short-circuits to an empty map when the run/tenant coordinates are missing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowVariableBundleCache")
class WorkflowVariableBundleCacheTest {

    private static final String RUN_ID = "run-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";
    private static final Map<String, Object> BUNDLE = Map.of("api_url", "https://api.example.com", "n", 5);

    @Mock
    private CredentialClient credentialClient;

    private WorkflowVariableBundleCache cache;

    @BeforeEach
    void setUp() {
        cache = new WorkflowVariableBundleCache(credentialClient);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getBundle() - fetch and caching behavior
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getBundle()")
    class GetBundleTests {

        @Test
        @DisplayName("Should return the bundle fetched from CredentialClient")
        void shouldReturnFetchedBundle() {
            // Arrange
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(BUNDLE);

            // Act
            Map<String, Object> result = cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);

            // Assert
            assertEquals(BUNDLE, result);
        }

        @Test
        @DisplayName("Should fetch from CredentialClient only ONCE for two calls with the same runId")
        void shouldFetchOnlyOncePerRun() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(BUNDLE);

            Map<String, Object> first = cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);
            Map<String, Object> second = cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);

            assertEquals(BUNDLE, first);
            assertEquals(BUNDLE, second);
            verify(credentialClient, times(1)).getWorkflowVariablesBundle(TENANT_ID, ORG_ID);
        }

        @Test
        @DisplayName("Should fetch separately for different runIds")
        void shouldFetchSeparatelyForDifferentRunIds() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(BUNDLE);

            cache.getBundle("run-A", TENANT_ID, ORG_ID);
            cache.getBundle("run-B", TENANT_ID, ORG_ID);

            verify(credentialClient, times(2)).getWorkflowVariablesBundle(TENANT_ID, ORG_ID);
        }

        @Test
        @DisplayName("Should cache an empty bundle (auth-service outage is not retry-stormed)")
        void shouldCacheEmptyBundle() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(Map.of());

            Map<String, Object> first = cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);
            Map<String, Object> second = cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);

            assertTrue(first.isEmpty());
            assertTrue(second.isEmpty());
            verify(credentialClient, times(1)).getWorkflowVariablesBundle(TENANT_ID, ORG_ID);
        }

        @Test
        @DisplayName("Should accept a null organizationId and pass it through to the client")
        void shouldAcceptNullOrganizationId() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, null)).thenReturn(BUNDLE);

            Map<String, Object> result = cache.getBundle(RUN_ID, TENANT_ID, null);

            assertEquals(BUNDLE, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getBundle() - guard clauses (no client call)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getBundle() guards")
    class GetBundleGuardTests {

        @Test
        @DisplayName("Should return empty map and never call the client for null runId")
        void shouldReturnEmptyForNullRunId() {
            Map<String, Object> result = cache.getBundle(null, TENANT_ID, ORG_ID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("Should return empty map and never call the client for null tenantId")
        void shouldReturnEmptyForNullTenantId() {
            Map<String, Object> result = cache.getBundle(RUN_ID, null, ORG_ID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("Should return empty map and never call the client for blank tenantId")
        void shouldReturnEmptyForBlankTenantId() {
            Map<String, Object> result = cache.getBundle(RUN_ID, "   ", ORG_ID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cleanupRun() - RunScopedCache lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cleanupRun()")
    class CleanupRunTests {

        @Test
        @DisplayName("Should invalidate the run entry so the next getBundle refetches")
        void shouldInvalidateSoNextGetRefetches() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(BUNDLE);
            cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);

            cache.cleanupRun(RUN_ID);
            cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);

            verify(credentialClient, times(2)).getWorkflowVariablesBundle(TENANT_ID, ORG_ID);
        }

        @Test
        @DisplayName("Should not affect other runs' entries")
        void shouldNotAffectOtherRuns() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(BUNDLE);
            cache.getBundle("run-A", TENANT_ID, ORG_ID);
            cache.getBundle("run-B", TENANT_ID, ORG_ID);

            cache.cleanupRun("run-A");
            cache.getBundle("run-B", TENANT_ID, ORG_ID);

            // run-B is still cached: only the two initial fetches happened
            verify(credentialClient, times(2)).getWorkflowVariablesBundle(TENANT_ID, ORG_ID);
        }

        @Test
        @DisplayName("Should be a no-op for a null runId")
        void shouldBeNoOpForNullRunId() {
            assertDoesNotThrow(() -> cache.cleanupRun(null));
        }

        @Test
        @DisplayName("Should be idempotent (safe to call twice)")
        void shouldBeIdempotent() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(BUNDLE);
            cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);

            cache.cleanupRun(RUN_ID);
            assertDoesNotThrow(() -> cache.cleanupRun(RUN_ID));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache identity
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RunScopedCache identity")
    class IdentityTests {

        @Test
        @DisplayName("Should report its cache name as WorkflowVariableBundleCache")
        void shouldReportCacheName() {
            assertEquals("WorkflowVariableBundleCache", cache.getCacheName());
        }

        @Test
        @DisplayName("Should belong to the EXECUTION cache domain")
        void shouldBelongToExecutionDomain() {
            assertEquals(RunScopedCache.CacheDomain.EXECUTION, cache.getDomain());
        }

        @Test
        @DisplayName("Should report the number of cached run entries")
        void shouldReportCacheSize() {
            when(credentialClient.getWorkflowVariablesBundle(TENANT_ID, ORG_ID)).thenReturn(BUNDLE);

            assertEquals(0, cache.getCacheSize());
            cache.getBundle(RUN_ID, TENANT_ID, ORG_ID);

            assertEquals(1, cache.getCacheSize());
        }
    }
}
