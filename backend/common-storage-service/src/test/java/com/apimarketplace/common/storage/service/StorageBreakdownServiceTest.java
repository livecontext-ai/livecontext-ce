package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.repository.OrgStorageBreakdownRepository;
import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageBreakdownService Unit Tests")
@ExtendWith(MockitoExtension.class)
class StorageBreakdownServiceTest {

    private static final String TENANT_ID = "tenant-001";
    private static final String ORG_ID = "org-42";
    private static final String CATEGORY_FILES = "FILES";
    private static final String CATEGORY_STEP_OUTPUTS = "STEP_OUTPUTS";
    private static final String CATEGORY_AGENTS = "AGENTS";

    @Mock
    private TenantStorageBreakdownRepository breakdownRepository;

    @Mock
    private OrgStorageBreakdownRepository orgBreakdownRepository;

    private StorageBreakdownService service;

    @BeforeEach
    void setUp() {
        service = new StorageBreakdownService(breakdownRepository, orgBreakdownRepository);
    }

    // ========================================================================
    // increment()
    // ========================================================================

    @Nested
    @DisplayName("increment()")
    class IncrementTests {

        @Test
        @DisplayName("should call repository incrementUsage with correct parameters")
        void shouldCallRepositoryWithCorrectParams() {
            service.increment(TENANT_ID, CATEGORY_FILES, 1024L, 1);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 1024L, 1);
        }

        @Test
        @DisplayName("should skip increment when both deltaBytes and deltaCount are zero")
        void shouldSkipWhenBothZero() {
            service.increment(TENANT_ID, CATEGORY_FILES, 0L, 0);

            verifyNoInteractions(breakdownRepository);
        }

        @Test
        @DisplayName("should NOT skip when only deltaBytes is zero but deltaCount is non-zero")
        void shouldNotSkipWhenOnlyBytesZero() {
            service.increment(TENANT_ID, CATEGORY_FILES, 0L, 1);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 0L, 1);
        }

        @Test
        @DisplayName("should NOT skip when only deltaCount is zero but deltaBytes is non-zero")
        void shouldNotSkipWhenOnlyCountZero() {
            service.increment(TENANT_ID, CATEGORY_FILES, 500L, 0);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 500L, 0);
        }

        @Test
        @DisplayName("should handle negative deltas for decrements")
        void shouldHandleNegativeDeltas() {
            service.increment(TENANT_ID, CATEGORY_FILES, -2048L, -1);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, -2048L, -1);
        }

        @Test
        @DisplayName("should handle very large byte values (> 2GB)")
        void shouldHandleLargeByteValues() {
            long twoGB = 2L * 1024 * 1024 * 1024;
            service.increment(TENANT_ID, CATEGORY_FILES, twoGB, 1);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, twoGB, 1);
        }

        @Test
        @DisplayName("should handle Long.MAX_VALUE without overflow")
        void shouldHandleMaxLong() {
            service.increment(TENANT_ID, CATEGORY_FILES, Long.MAX_VALUE, 1);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, Long.MAX_VALUE, 1);
        }

        @Test
        @DisplayName("should swallow repository exceptions gracefully")
        void shouldSwallowRepositoryExceptions() {
            doThrow(new RuntimeException("DB connection lost"))
                    .when(breakdownRepository).incrementUsage(anyString(), anyString(), anyLong(), anyInt());

            // Should NOT throw
            service.increment(TENANT_ID, CATEGORY_FILES, 100L, 1);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 100L, 1);
        }

        @Test
        @DisplayName("should handle null tenantId without NPE from service layer")
        void shouldHandleNullTenantId() {
            // The repository might throw, but service should catch it
            doThrow(new RuntimeException("not-null constraint"))
                    .when(breakdownRepository).incrementUsage(isNull(), anyString(), anyLong(), anyInt());

            service.increment(null, CATEGORY_FILES, 100L, 1);
            // No exception thrown - swallowed
        }

        @Test
        @DisplayName("should handle empty string category")
        void shouldHandleEmptyCategory() {
            service.increment(TENANT_ID, "", 100L, 1);

            verify(breakdownRepository).incrementUsage(TENANT_ID, "", 100L, 1);
        }
    }

    // ========================================================================
    // trackSave()
    // ========================================================================

    @Nested
    @DisplayName("trackSave()")
    class TrackSaveTests {

        @Test
        @DisplayName("should call increment with positive delta and count +1")
        void shouldTrackSavePositive() {
            service.trackSave(TENANT_ID, CATEGORY_FILES, 4096L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 4096L, 1);
        }

        @Test
        @DisplayName("should track save with zero bytes (empty file)")
        void shouldTrackSaveZeroBytes() {
            // Zero bytes but still count +1 (item exists)
            service.trackSave(TENANT_ID, CATEGORY_FILES, 0L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 0L, 1);
        }
    }

    // ========================================================================
    // trackDelete()
    // ========================================================================

    @Nested
    @DisplayName("trackDelete()")
    class TrackDeleteTests {

        @Test
        @DisplayName("should call increment with negative delta and count -1")
        void shouldTrackDeleteNegative() {
            service.trackDelete(TENANT_ID, CATEGORY_FILES, 4096L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, -4096L, -1);
        }

        @Test
        @DisplayName("should handle delete of zero-byte entity")
        void shouldTrackDeleteZeroBytes() {
            service.trackDelete(TENANT_ID, CATEGORY_FILES, 0L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 0L, -1);
        }
    }

    // ========================================================================
    // trackSizeChange()
    // ========================================================================

    @Nested
    @DisplayName("trackSizeChange()")
    class TrackSizeChangeTests {

        @Test
        @DisplayName("should increment bytes only, no count change")
        void shouldTrackSizeChangeOnly() {
            service.trackSizeChange(TENANT_ID, CATEGORY_STEP_OUTPUTS, 500L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_STEP_OUTPUTS, 500L, 0);
        }

        @Test
        @DisplayName("should handle negative size change (shrink)")
        void shouldHandleShrink() {
            service.trackSizeChange(TENANT_ID, CATEGORY_STEP_OUTPUTS, -300L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_STEP_OUTPUTS, -300L, 0);
        }

        @Test
        @DisplayName("should skip when delta is zero")
        void shouldSkipZeroDelta() {
            service.trackSizeChange(TENANT_ID, CATEGORY_STEP_OUTPUTS, 0L);

            verifyNoInteractions(breakdownRepository);
        }
    }

    // ========================================================================
    // getTotalUsage()
    // ========================================================================

    @Nested
    @DisplayName("getTotalUsage()")
    class GetTotalUsageTests {

        @Test
        @DisplayName("should return sum from repository")
        void shouldReturnSum() {
            when(breakdownRepository.sumTotalUsage(TENANT_ID)).thenReturn(1_073_741_824L);

            long total = service.getTotalUsage(TENANT_ID);

            assertThat(total).isEqualTo(1_073_741_824L);
        }

        @Test
        @DisplayName("should return 0 for tenant with no breakdown data")
        void shouldReturnZeroForNewTenant() {
            when(breakdownRepository.sumTotalUsage("new-tenant")).thenReturn(0L);

            long total = service.getTotalUsage("new-tenant");

            assertThat(total).isEqualTo(0L);
        }

        @Test
        @DisplayName("should handle negative total (should never happen, but defensive)")
        void shouldHandleNegativeTotal() {
            when(breakdownRepository.sumTotalUsage(TENANT_ID)).thenReturn(-100L);

            long total = service.getTotalUsage(TENANT_ID);

            assertThat(total).isEqualTo(-100L); // Raw value, reconciliation will fix
        }
    }

    // ========================================================================
    // getBreakdown()
    // ========================================================================

    @Nested
    @DisplayName("getBreakdown()")
    class GetBreakdownTests {

        @Test
        @DisplayName("should return all categories for tenant")
        void shouldReturnAllCategories() {
            List<TenantStorageBreakdown> expected = List.of(
                    new TenantStorageBreakdown(TENANT_ID, "FILES", 1024L, 5),
                    new TenantStorageBreakdown(TENANT_ID, "STEP_OUTPUTS", 2048L, 10),
                    new TenantStorageBreakdown(TENANT_ID, "AGENTS", 512L, 2)
            );
            when(breakdownRepository.findByTenantId(TENANT_ID)).thenReturn(expected);

            List<TenantStorageBreakdown> result = service.getBreakdown(TENANT_ID);

            assertThat(result).hasSize(3);
            assertThat(result).extracting(TenantStorageBreakdown::getCategory)
                    .containsExactly("FILES", "STEP_OUTPUTS", "AGENTS");
        }

        @Test
        @DisplayName("should return empty list for new tenant")
        void shouldReturnEmptyForNewTenant() {
            when(breakdownRepository.findByTenantId("new-tenant")).thenReturn(Collections.emptyList());

            List<TenantStorageBreakdown> result = service.getBreakdown("new-tenant");

            assertThat(result).isEmpty();
        }
    }

    // ========================================================================
    // setUsage() - reconciliation
    // ========================================================================

    @Nested
    @DisplayName("setUsage() - reconciliation")
    class SetUsageTests {

        @Test
        @DisplayName("should call repository setUsage with absolute values")
        void shouldSetAbsoluteValues() {
            service.setUsage(TENANT_ID, CATEGORY_FILES, 50000L, 100);

            verify(breakdownRepository).setUsage(TENANT_ID, CATEGORY_FILES, 50000L, 100);
        }

        @Test
        @DisplayName("should allow setting to zero (category emptied)")
        void shouldAllowSettingToZero() {
            service.setUsage(TENANT_ID, CATEGORY_AGENTS, 0L, 0);

            verify(breakdownRepository).setUsage(TENANT_ID, CATEGORY_AGENTS, 0L, 0);
        }
    }

    // ========================================================================
    // Stress / Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Stress and Edge Cases")
    class StressTests {

        @Test
        @DisplayName("should handle rapid sequential increments for same tenant/category")
        void shouldHandleRapidSequentialIncrements() {
            for (int i = 0; i < 1000; i++) {
                service.trackSave(TENANT_ID, CATEGORY_FILES, 100L);
            }

            verify(breakdownRepository, times(1000)).incrementUsage(TENANT_ID, CATEGORY_FILES, 100L, 1);
        }

        @Test
        @DisplayName("should handle increments across all 7 categories")
        void shouldHandleAllCategories() {
            String[] categories = {"STEP_OUTPUTS", "FILES", "EXECUTION_DATA", "AGENTS",
                    "INTERFACES", "CONVERSATIONS", "CONFIGURATION"};

            for (String cat : categories) {
                service.trackSave(TENANT_ID, cat, 512L);
            }

            for (String cat : categories) {
                verify(breakdownRepository).incrementUsage(TENANT_ID, cat, 512L, 1);
            }
        }

        @Test
        @DisplayName("should handle multiple tenants independently")
        void shouldHandleMultipleTenants() {
            service.trackSave("tenant-A", CATEGORY_FILES, 100L);
            service.trackSave("tenant-B", CATEGORY_FILES, 200L);
            service.trackSave("tenant-C", CATEGORY_FILES, 300L);

            verify(breakdownRepository).incrementUsage("tenant-A", CATEGORY_FILES, 100L, 1);
            verify(breakdownRepository).incrementUsage("tenant-B", CATEGORY_FILES, 200L, 1);
            verify(breakdownRepository).incrementUsage("tenant-C", CATEGORY_FILES, 300L, 1);
        }

        @Test
        @DisplayName("should handle alternating save/delete for same entity size")
        void shouldHandleAlternatingSaveDelete() {
            // Simulate save then delete of same item
            service.trackSave(TENANT_ID, CATEGORY_FILES, 1024L);
            service.trackDelete(TENANT_ID, CATEGORY_FILES, 1024L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 1024L, 1);
            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, -1024L, -1);
        }

        @Test
        @DisplayName("should handle mixed category operations in sequence")
        void shouldHandleMixedCategoryOps() {
            service.trackSave(TENANT_ID, CATEGORY_FILES, 100L);
            service.trackSave(TENANT_ID, CATEGORY_AGENTS, 200L);
            service.trackDelete(TENANT_ID, CATEGORY_FILES, 50L);
            service.trackSizeChange(TENANT_ID, CATEGORY_AGENTS, 50L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 100L, 1);
            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_AGENTS, 200L, 1);
            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, -50L, -1);
            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_AGENTS, 50L, 0);
        }

        @Test
        @DisplayName("should handle tenant IDs with special characters")
        void shouldHandleSpecialCharTenantIds() {
            String[] specialIds = {
                    "google-oauth2|12345",
                    "auth0|abc_def",
                    "tenant with spaces",
                    "tenant@domain.com",
                    "テナント"
            };

            for (String id : specialIds) {
                service.trackSave(id, CATEGORY_FILES, 100L);
                verify(breakdownRepository).incrementUsage(id, CATEGORY_FILES, 100L, 1);
            }
        }

        @Test
        @DisplayName("should handle extremely large item count")
        void shouldHandleLargeItemCount() {
            service.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, 1L, Integer.MAX_VALUE);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_STEP_OUTPUTS, 1L, Integer.MAX_VALUE);
        }
    }

    // ========================================================================
    // Org-aware trackers (Issue #149)
    // ========================================================================

    @Nested
    @DisplayName("Org-aware trackers (Issue #149)")
    class OrgAwareTrackerTests {

        @Test
        @DisplayName("trackSave with organizationId increments BOTH tenant and org rollups")
        void trackSaveWithOrgIncrementsBothTables() {
            service.trackSave(TENANT_ID, CATEGORY_FILES, 4096L, ORG_ID);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 4096L, 1);
            verify(orgBreakdownRepository).incrementUsage(ORG_ID, CATEGORY_FILES, 4096L, 1);
        }

        @Test
        @DisplayName("trackSave with null organizationId writes tenant table only")
        void trackSaveWithNullOrgWritesTenantOnly() {
            service.trackSave(TENANT_ID, CATEGORY_FILES, 4096L, null);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 4096L, 1);
            verifyNoInteractions(orgBreakdownRepository);
        }

        @Test
        @DisplayName("trackSave with blank organizationId writes tenant table only")
        void trackSaveWithBlankOrgWritesTenantOnly() {
            service.trackSave(TENANT_ID, CATEGORY_FILES, 4096L, "   ");

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 4096L, 1);
            verifyNoInteractions(orgBreakdownRepository);
        }

        @Test
        @DisplayName("3-arg trackSave (legacy) does NOT touch the org table")
        void legacyTrackSaveIgnoresOrgTable() {
            service.trackSave(TENANT_ID, CATEGORY_FILES, 4096L);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 4096L, 1);
            verifyNoInteractions(orgBreakdownRepository);
        }

        @Test
        @DisplayName("trackDelete with organizationId decrements BOTH tables")
        void trackDeleteWithOrgDecrementsBoth() {
            service.trackDelete(TENANT_ID, CATEGORY_FILES, 4096L, ORG_ID);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, -4096L, -1);
            verify(orgBreakdownRepository).incrementUsage(ORG_ID, CATEGORY_FILES, -4096L, -1);
        }

        @Test
        @DisplayName("trackSizeChange with organizationId updates BOTH tables, no count change")
        void trackSizeChangeWithOrgUpdatesBoth() {
            service.trackSizeChange(TENANT_ID, CATEGORY_STEP_OUTPUTS, 500L, ORG_ID);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_STEP_OUTPUTS, 500L, 0);
            verify(orgBreakdownRepository).incrementUsage(ORG_ID, CATEGORY_STEP_OUTPUTS, 500L, 0);
        }

        @Test
        @DisplayName("zero-delta calls skip BOTH tables for the org path too")
        void zeroDeltaSkipsBothTables() {
            service.trackSizeChange(TENANT_ID, CATEGORY_STEP_OUTPUTS, 0L, ORG_ID);

            verifyNoInteractions(breakdownRepository);
            verifyNoInteractions(orgBreakdownRepository);
        }

        @Test
        @DisplayName("org-repo exception does NOT mask the tenant write")
        void orgRepoExceptionDoesNotMaskTenantWrite() {
            doThrow(new RuntimeException("org table unavailable"))
                    .when(orgBreakdownRepository).incrementUsage(anyString(), anyString(), anyLong(), anyInt());

            // Must not throw
            service.trackSave(TENANT_ID, CATEGORY_FILES, 4096L, ORG_ID);

            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 4096L, 1);
        }

        @Test
        @DisplayName("getOrgBreakdown returns rows from the org repository")
        void getOrgBreakdownReadsOrgRepo() {
            List<OrgStorageBreakdown> rows = List.of(
                    new OrgStorageBreakdown(ORG_ID, "FILES", 1024L, 5),
                    new OrgStorageBreakdown(ORG_ID, "AGENTS", 512L, 2)
            );
            when(orgBreakdownRepository.findByOrganizationId(ORG_ID)).thenReturn(rows);

            List<OrgStorageBreakdown> result = service.getOrgBreakdown(ORG_ID);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(OrgStorageBreakdown::getCategory)
                    .containsExactly("FILES", "AGENTS");
        }

        @Test
        @DisplayName("setOrgUsage forwards absolute values to the org repository")
        void setOrgUsageSetsAbsoluteValues() {
            service.setOrgUsage(ORG_ID, CATEGORY_FILES, 50000L, 100);

            verify(orgBreakdownRepository).setUsage(ORG_ID, CATEGORY_FILES, 50000L, 100);
        }
    }
}
