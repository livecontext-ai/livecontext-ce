package com.apimarketplace.common.storage.service;

import ch.qos.logback.classic.Level;
import com.github.benmanes.caffeine.cache.Ticker;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.repository.OrgStorageBreakdownRepository;
import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    // ========================================================================
    // Negative-delta clamp diagnostic - WARN throttling
    // ========================================================================

    @Nested
    @DisplayName("negative-delta clamp diagnostic")
    class UnderflowWarnThrottleTests {

        private ListAppender<ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger logger;
        private Level originalLevel;
        /** Drives Caffeine's expiry without sleeping. Caffeine tickers read NANOS. */
        private AtomicLong nanos;
        private StorageBreakdownService throttledService;

        @BeforeEach
        void attachAppender() {
            logger = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(StorageBreakdownService.class);
            originalLevel = logger.getLevel();
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            nanos = new AtomicLong(Duration.ofDays(1).toNanos());
            Ticker ticker = nanos::get;
            throttledService = new StorageBreakdownService(
                    breakdownRepository, orgBreakdownRepository, ticker);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }

        /** Most tests need to SEE the throttled repeats, which only exist at DEBUG. */
        private void captureDebug() {
            logger.setLevel(Level.DEBUG);
        }

        /** The level storage-service and orchestrator actually ship for this package. */
        private void captureAtShippedLevel() {
            logger.setLevel(Level.INFO);
        }

        /** Any negative delta underflows a row that is already at zero. */
        private void rowAtZero(String tenantId, String category) {
            when(breakdownRepository.findById(argThat(id ->
                    id != null
                            && tenantId.equals(id.getTenantId())
                            && category.equals(id.getCategory()))))
                    .thenReturn(Optional.of(new TenantStorageBreakdown(tenantId, category, 0L, 0)));
        }

        /** Same, for any key at all. */
        private void everyRowAtZero() {
            when(breakdownRepository.findById(any()))
                    .thenReturn(Optional.of(new TenantStorageBreakdown(TENANT_ID, CATEGORY_FILES, 0L, 0)));
        }

        private List<ILoggingEvent> clampEvents(Level level) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == level)
                    .filter(e -> e.getFormattedMessage().contains("Negative delta clamped"))
                    .toList();
        }

        @Test
        @DisplayName("first clamp for a tenant+category is logged at WARN")
        void firstClampWarns() {
            captureDebug();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);

            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);

            assertThat(clampEvents(Level.WARN)).hasSize(1);
            assertThat(clampEvents(Level.WARN).get(0).getFormattedMessage())
                    .contains("tenant=" + TENANT_ID)
                    .contains("category=" + CATEGORY_STEP_OUTPUTS)
                    .contains("projectedBytes=-430");
        }

        @Test
        @DisplayName("repeats within the window drop to DEBUG and keep the full diagnostic")
        void repeatsWithinWindowAreDebug() {
            captureDebug();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);

            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            nanos.addAndGet(StorageBreakdownService.UNDERFLOW_WARN_INTERVAL.toNanos() - 1);
            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -433L, 0);
            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -385L, 0);

            // This is the whole point: prod logged 3861 of these in 3 days, 3779 of
            // them the same tenant+category.
            assertThat(clampEvents(Level.WARN)).hasSize(1);
            assertThat(clampEvents(Level.DEBUG)).hasSize(2);
            assertThat(clampEvents(Level.DEBUG).get(0).getFormattedMessage())
                    .contains("tenant=" + TENANT_ID)
                    .contains("projectedBytes=-433");
        }

        @Test
        @DisplayName("at the level this actually ships, exactly ONE line per pair per hour survives")
        void atShippedLevelOnlyOneLineSurvives() {
            // The demotion to DEBUG is not "logged somewhere quieter": no shipped profile
            // enables DEBUG for com.apimarketplace.common.storage, so the repeats are
            // dropped. That is the contract the change exists for, and the DEBUG-level
            // tests above cannot observe it.
            captureAtShippedLevel();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);

            for (int i = 0; i < 50; i++) {
                throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            }

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        }

        @Test
        @DisplayName("the window is measured from the WARN, not from the last clamp")
        void windowIsMeasuredFromTheWarnNotTheLastAccess() {
            // Kills expireAfterWrite -> expireAfterAccess. Under expireAfterAccess each
            // throttled clamp refreshes the entry, so a pair that clamps continuously -
            // exactly the EXECUTION_DATA case this exists for, 3779 clamps in 3 days -
            // would warn ONCE and then never again, permanently. Under expireAfterWrite
            // the hour runs from the warn regardless of traffic.
            captureDebug();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);
            long interval = StorageBreakdownService.UNDERFLOW_WARN_INTERVAL.toNanos();

            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            // Keep clamping all the way through the window, never idling.
            for (int i = 0; i < 10; i++) {
                nanos.addAndGet(interval / 10);
                throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            }

            assertThat(clampEvents(Level.WARN))
                    .as("continuous traffic must not postpone the next warning forever")
                    .hasSize(2);
        }

        @Test
        @DisplayName("the throttle window is one hour, not a value that floods or silences")
        void throttleWindowIsOneHour() {
            // Literal on purpose, same reasoning as the size cap below: asserting against
            // the constant passes for any value of it, including Duration.ofSeconds(1)
            // (the 3861-lines-in-3-days flood restored) and Duration.ofDays(30) (a brand
            // new drift silenced for a month).
            assertThat(StorageBreakdownService.UNDERFLOW_WARN_INTERVAL)
                    .isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("the window reopens once the interval has elapsed")
        void warnsAgainAfterTheWindow() {
            captureDebug();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);

            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            nanos.addAndGet(StorageBreakdownService.UNDERFLOW_WARN_INTERVAL.toNanos() + 1);
            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);

            // Persistent drift must stay visible, just not once per delete.
            assertThat(clampEvents(Level.WARN)).hasSize(2);
        }

        @Test
        @DisplayName("throttling is per tenant+category, so a second CATEGORY still warns")
        void differentCategoryStillWarns() {
            captureDebug();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);
            rowAtZero(TENANT_ID, CATEGORY_FILES);

            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            throttledService.increment(TENANT_ID, CATEGORY_FILES, -12L, 0);

            // A noisy pair must not mask a brand-new drift somewhere else.
            assertThat(clampEvents(Level.WARN)).hasSize(2);
        }

        @Test
        @DisplayName("throttling is per tenant+category, so a second TENANT still warns")
        void differentTenantStillWarns() {
            // Guards the tenant half of the key. Drop it (key = category alone) and prod
            // gets ONE warning for the first drifting tenant while every other tenant on
            // the same category is silently demoted to a level nothing logs - the exact
            // blindness the WARN exists to prevent.
            captureDebug();
            String otherTenant = "tenant-002";
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);
            rowAtZero(otherTenant, CATEGORY_STEP_OUTPUTS);

            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            throttledService.increment(otherTenant, CATEGORY_STEP_OUTPUTS, -430L, 0);

            assertThat(clampEvents(Level.WARN)).hasSize(2);
            assertThat(clampEvents(Level.WARN))
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> assertThat(m).contains("tenant=" + TENANT_ID))
                    .anySatisfy(m -> assertThat(m).contains("tenant=" + otherTenant));
        }

        @Test
        @DisplayName("the throttle holds when many threads clamp the same pair at once")
        void concurrentClampsWarnOnce() throws InterruptedException {
            // increment() is documented as safe for concurrent calls, and this asserts the
            // throttle survives that. Read the limit honestly: each thread first passes
            // through two shared Mockito mocks whose invocation registration is
            // synchronized, so the threads are largely serialised and a get-then-put
            // implementation would still pass here most runs. The guarantee comes from
            // putIfAbsent being atomic, not from this test; what this test does prove is
            // that nothing in the path deadlocks or double-counts under real threads.
            captureDebug();
            everyRowAtZero();
            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(clampEvents(Level.WARN)).hasSize(1);
            assertThat(clampEvents(Level.DEBUG)).hasSize(threads - 1);
        }

        @Test
        @DisplayName("the throttle key set is bounded by a finite ceiling")
        void throttleKeySetIsBounded() {
            // Literals on purpose: asserting against the constant would pass for any
            // value of it, including 0 (clear on every call, throttle defeated) and
            // Integer.MAX_VALUE (unbounded growth on a long-lived singleton).
            assertThat(throttledService.underflowWarnedKeysMaximum()).isEqualTo(5_000L);
            assertThat(StorageBreakdownService.UNDERFLOW_WARN_KEYS_MAX)
                    .isPositive()
                    .isLessThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("crossing the ceiling evicts entries, it does not wipe the whole set")
        void crossingTheCeilingEvictsRatherThanWipes() {
            // The pathology this replaces: a size check plus clear(). After N over the
            // cap that leaves only N entries, so nearly every drifting tenant gets its
            // WARN back at once - the throttle inverts exactly when it matters most.
            // An eviction policy keeps the set full instead.
            // OFF, not INFO: this drives 6000 distinct pairs and every one of them is a
            // first clamp, so at INFO it would dump 6000 WARN lines into the surefire
            // console. The assertion is on the cache, not on the log.
            logger.setLevel(Level.OFF);
            everyRowAtZero();
            int overflow = StorageBreakdownService.UNDERFLOW_WARN_KEYS_MAX + 1_000;

            for (int i = 0; i < overflow; i++) {
                throttledService.increment("tenant-" + i, CATEGORY_STEP_OUTPUTS, -1L, 0);
            }

            long retained = throttledService.underflowWarnedKeysSize();
            assertThat(retained)
                    .as("a bounded cache stays near its ceiling; clear() would leave ~1000")
                    .isLessThanOrEqualTo(StorageBreakdownService.UNDERFLOW_WARN_KEYS_MAX)
                    .isGreaterThan(StorageBreakdownService.UNDERFLOW_WARN_KEYS_MAX * 4L / 5L);
        }

        @Test
        @DisplayName("a quieter log is not a quieter outcome: the increment still runs")
        void throttledClampStillIncrements() {
            captureDebug();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);

            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);

            // The diagnostic is advisory; the SQL-side clamp is what actually applies.
            verify(breakdownRepository, times(2))
                    .incrementUsage(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
        }

        @Test
        @DisplayName("a POSITIVE delta never pays for the diagnostic pre-read")
        void positiveDeltaSkipsThePreRead() {
            // Deleting the `deltaBytes < 0 || deltaCount < 0` guard would be invisible in
            // the log and would add a SELECT to every upload on the hot path.
            captureAtShippedLevel();

            throttledService.increment(TENANT_ID, CATEGORY_FILES, 4096L, 1);

            verify(breakdownRepository, never()).findById(any());
            verify(breakdownRepository).incrementUsage(TENANT_ID, CATEGORY_FILES, 4096L, 1);
        }

        @Test
        @DisplayName("a throttled clamp does not pay for the pre-read either")
        void throttledClampSkipsThePreRead() {
            // The pre-read exists only to build a message that DEBUG discards. Without the
            // short-circuit this is a log fix and not a load fix: 3779 of 3861 clamps in
            // prod would still hit the database to format a line nobody sees.
            captureAtShippedLevel();
            rowAtZero(TENANT_ID, CATEGORY_STEP_OUTPUTS);

            for (int i = 0; i < 20; i++) {
                throttledService.increment(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
            }

            verify(breakdownRepository, times(1)).findById(any());
            verify(breakdownRepository, times(20))
                    .incrementUsage(TENANT_ID, CATEGORY_STEP_OUTPUTS, -430L, 0);
        }

        @Test
        @DisplayName("the throttle key separates tenant from category")
        void throttleKeySeparatesTenantFromCategory() {
            // Concatenating without a separator makes tenant "a" + category "bc"
            // indistinguishable from tenant "ab" + category "c", so one would silence the
            // other for an hour.
            captureDebug();
            rowAtZero("a", "bc");
            rowAtZero("ab", "c");

            throttledService.increment("a", "bc", -1L, 0);
            throttledService.increment("ab", "c", -1L, 0);

            assertThat(clampEvents(Level.WARN)).hasSize(2);
        }

        @Test
        @DisplayName("a negative delta that does NOT underflow logs nothing at all")
        void sufficientBalanceLogsNothing() {
            captureDebug();
            when(breakdownRepository.findById(any()))
                    .thenReturn(Optional.of(
                            new TenantStorageBreakdown(TENANT_ID, CATEGORY_FILES, 10_000L, 5)));

            throttledService.increment(TENANT_ID, CATEGORY_FILES, -430L, -1);

            assertThat(clampEvents(Level.WARN)).isEmpty();
            assertThat(clampEvents(Level.DEBUG)).isEmpty();
        }
    }
}
