package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import com.apimarketplace.common.storage.repository.OrganizationStorageQuotaRepository;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.repository.TenantStorageQuotaRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for QuotaService.
 * Tests the quota management logic including creation, checking, updating,
 * and limit enforcement. Uses Mockito mocks for repository layer to
 * allow deterministic control over quota state.
 */
@DisplayName("QuotaService Integration Tests")
@ExtendWith(MockitoExtension.class)
class QuotaServiceIntegrationTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final long ONE_GB = 1_073_741_824L;
    // Parallel-agent change (2026-05-12): QuotaService.DEFAULT_MAX_BYTES dropped from
    // 1 GB to 100 MB to align with the FREE plan's `included_storage_bytes` seed.
    // The fallback now matches what the FREE plan's quota sync would otherwise apply.
    private static final long DEFAULT_MAX_BYTES = 104_857_600L; // 100 MB (FREE)

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private TenantStorageQuotaRepository quotaRepository;

    @Mock
    private OrganizationStorageQuotaRepository orgQuotaRepository;

    @Mock
    private StorageBreakdownService breakdownService;

    @Mock
    private AppEditionProvider editionProvider;

    @Captor
    private ArgumentCaptor<TenantStorageQuota> quotaCaptor;

    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        // Integration tests assert cloud enforcement semantics; CE edition tested separately
        // in QuotaServiceTest. lenient() because some tests don't traverse the gate.
        org.mockito.Mockito.lenient().when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        quotaService = new QuotaService(storageRepository, quotaRepository, orgQuotaRepository, breakdownService, editionProvider);
    }

    // ========== getOrCreate / getQuota Tests ==========

    @Nested
    @DisplayName("getQuota - get or create quota")
    class GetOrCreateQuotaTests {

        @Test
        @DisplayName("should create default quota for new tenant with 1GB limit")
        void shouldCreateDefaultQuotaForNewTenant() {
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.empty());
            when(quotaRepository.save(any(TenantStorageQuota.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            TenantStorageQuota result = quotaService.getQuota(TENANT_A);

            assertThat(result).isNotNull();
            assertThat(result.getTenantId()).isEqualTo(TENANT_A);
            assertThat(result.getMaxBytes()).isEqualTo(DEFAULT_MAX_BYTES);
            assertThat(result.getUsedBytes()).isEqualTo(0L);
            assertThat(result.getHardLimitBytes()).isEqualTo(DEFAULT_MAX_BYTES);
            // Soft limit = 80% of FREE-plan default (100 MB).
            assertThat(result.getSoftLimitBytes()).isEqualTo((long) (DEFAULT_MAX_BYTES * 0.8));
        }

        @Test
        @DisplayName("should return existing quota for known tenant without creating new one")
        void shouldReturnExistingQuotaWithoutCreation() {
            TenantStorageQuota existing = new TenantStorageQuota(TENANT_A, 5_000_000L);
            existing.setUsedBytes(1_000_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(existing));

            TenantStorageQuota result = quotaService.getQuota(TENANT_A);

            assertThat(result.getMaxBytes()).isEqualTo(5_000_000L);
            assertThat(result.getUsedBytes()).isEqualTo(1_000_000L);
            verify(quotaRepository, never()).save(any());
        }

        @Test
        @DisplayName("should create separate quotas for different tenants")
        void shouldCreateSeparateQuotasForDifferentTenants() {
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.empty());
            when(quotaRepository.findByTenantId(TENANT_B)).thenReturn(Optional.empty());
            when(quotaRepository.save(any(TenantStorageQuota.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            TenantStorageQuota quotaA = quotaService.getQuota(TENANT_A);
            TenantStorageQuota quotaB = quotaService.getQuota(TENANT_B);

            assertThat(quotaA.getTenantId()).isEqualTo(TENANT_A);
            assertThat(quotaB.getTenantId()).isEqualTo(TENANT_B);
            verify(quotaRepository, times(2)).save(any());
        }
    }

    // ========== checkQuota Tests ==========

    @Nested
    @DisplayName("checkQuota - quota limit enforcement")
    class CheckQuotaTests {

        @Test
        @DisplayName("should return OK when usage is well within limits")
        void shouldReturnOkWhenWithinLimits() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 1_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_A, 500L);

            assertThat(result).isEqualTo(QuotaStatus.OK);
        }

        @Test
        @DisplayName("should return OK when additional bytes fit exactly at the limit")
        void shouldReturnOkWhenExactlyAtLimit() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 5_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            // used(5000) + additional(5000) = 10000 = hardLimit -> canStore returns true
            QuotaStatus result = quotaService.checkQuota(TENANT_A, 5_000L);

            assertThat(result).isEqualTo(QuotaStatus.OK);
        }

        @Test
        @DisplayName("should return HARD_LIMIT_REACHED when projected usage exceeds hard cap after soft is reached")
        void shouldReturnHardLimitWhenProjectedUsageExceedsHardAfterSoft() {
            // maxBytes=10000, used=9000 (above 80% soft=8000), additional=2000 => can't store
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 9_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_A, 2_000L);

            assertThat(result).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("should return HARD_LIMIT_REACHED when below soft limit but cannot store")
        void shouldReturnHardLimitWhenBelowSoftButCannotStore() {
            // maxBytes=10000, softLimit=8000 (80%), hardLimit=10000
            // used=7000 (below soft 8000), additional=4000 => 11000 > 10000 (can't store)
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 7_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_A, 4_000L);

            assertThat(result).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("should return OK for zero additional bytes")
        void shouldReturnOkForZeroAdditionalBytes() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 5_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_A, 0L);

            assertThat(result).isEqualTo(QuotaStatus.OK);
        }

        @Test
        @DisplayName("should auto-create quota and return OK for new tenant with small payload")
        void shouldAutoCreateQuotaForNewTenantAndReturnOk() {
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.empty());
            when(quotaRepository.save(any(TenantStorageQuota.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            QuotaStatus result = quotaService.checkQuota(TENANT_A, 100L);

            assertThat(result).isEqualTo(QuotaStatus.OK);
            verify(quotaRepository).save(any());
        }
    }

    // ========== updateUsage Tests ==========

    @Nested
    @DisplayName("updateUsage - recalculate from actual storage")
    class UpdateUsageTests {

        @Test
        @DisplayName("should recalculate usage from storage repository")
        void shouldRecalculateUsageFromStorage() {
            TenantStorageQuota quota = createQuota(TENANT_A, 100_000L, 0L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));
            when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(45_000L);

            quotaService.updateUsage(TENANT_A);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUsedBytes()).isEqualTo(45_000L);
        }

        @Test
        @DisplayName("should update timestamp on usage update")
        void shouldUpdateTimestampOnUsageUpdate() {
            TenantStorageQuota quota = createQuota(TENANT_A, 100_000L, 0L);
            Instant before = Instant.now();
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));
            when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(10_000L);

            quotaService.updateUsage(TENANT_A);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUpdatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("should handle zero usage correctly")
        void shouldHandleZeroUsage() {
            TenantStorageQuota quota = createQuota(TENANT_A, 100_000L, 50_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));
            when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(0L);

            quotaService.updateUsage(TENANT_A);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUsedBytes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should create quota and update for new tenant")
        void shouldCreateAndUpdateForNewTenant() {
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.empty());
            when(quotaRepository.save(any(TenantStorageQuota.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(500L);

            quotaService.updateUsage(TENANT_A);

            // save is called twice: once for creation, once for update
            verify(quotaRepository, times(2)).save(quotaCaptor.capture());
            TenantStorageQuota lastSaved = quotaCaptor.getValue();
            assertThat(lastSaved.getUsedBytes()).isEqualTo(500L);
        }

        @Test
        @DisplayName("must evict BOTH quotaStatus AND tenantQuota caches")
        void mustEvictBothCaches() throws NoSuchMethodException {
            // Regression guard for the cache-staleness defect: getQuota() is
            // @Cacheable("tenantQuota") and updateUsage() previously evicted only
            // "quotaStatus", so getQuota() kept returning the cached pre-update
            // TenantStorageQuota with stale usedBytes after every updateUsage()
            // (broken for both recalculateUsage and the new EXECUTION_DATA
            // inline refresh path). updateLimits() already evicts both - this
            // pins updateUsage() to the same contract.
            org.springframework.cache.annotation.CacheEvict ann =
                    QuotaService.class.getMethod("updateUsage", String.class)
                            .getAnnotation(org.springframework.cache.annotation.CacheEvict.class);
            assertThat(ann)
                    .as("updateUsage must declare @CacheEvict")
                    .isNotNull();
            assertThat(ann.value())
                    .as("@CacheEvict on updateUsage must list BOTH quotaStatus AND tenantQuota")
                    .containsExactlyInAnyOrder("quotaStatus", "tenantQuota");
            assertThat(ann.key())
                    .as("@CacheEvict must key on #tenantId so multi-tenant writes don't poison each other")
                    .isEqualTo("#tenantId");
        }
    }

    // ========== updateLimits Tests ==========

    @Nested
    @DisplayName("updateLimits - modify tenant limits")
    class UpdateLimitsTests {

        @Test
        @DisplayName("should update max bytes and calculate soft/hard limits")
        void shouldUpdateMaxBytesAndLimits() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 5_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            quotaService.updateLimits(TENANT_A, 50_000L, 0.9);

            verify(quotaRepository).save(quotaCaptor.capture());
            TenantStorageQuota updated = quotaCaptor.getValue();

            assertThat(updated.getMaxBytes()).isEqualTo(50_000L);
            assertThat(updated.getSoftLimitBytes()).isEqualTo(45_000L); // 90% of 50000
            assertThat(updated.getHardLimitBytes()).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("should set soft limit to 0 when ratio is 0")
        void shouldSetSoftLimitToZeroWhenRatioIsZero() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 0L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            quotaService.updateLimits(TENANT_A, 10_000L, 0.0);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getSoftLimitBytes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should update timestamp when limits are changed")
        void shouldUpdateTimestampOnLimitChange() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 0L);
            Instant before = Instant.now();
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            quotaService.updateLimits(TENANT_A, 20_000L, 0.8);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUpdatedAt()).isAfterOrEqualTo(before);
        }
    }

    // ========== getCurrentUsage Tests ==========

    @Nested
    @DisplayName("getCurrentUsage")
    class GetCurrentUsageTests {

        @Test
        @DisplayName("should return actual usage from storage repository")
        void shouldReturnActualUsage() {
            when(storageRepository.calculateTenantUsage(TENANT_A)).thenReturn(12_345L);

            long usage = quotaService.getCurrentUsage(TENANT_A);

            assertThat(usage).isEqualTo(12_345L);
        }

        @Test
        @DisplayName("should return 0 for tenant with no storage")
        void shouldReturnZeroForEmptyTenant() {
            when(storageRepository.calculateTenantUsage(TENANT_B)).thenReturn(0L);

            long usage = quotaService.getCurrentUsage(TENANT_B);

            assertThat(usage).isEqualTo(0L);
        }
    }

    // ========== getUsagePercentage Tests ==========

    @Nested
    @DisplayName("getUsagePercentage")
    class GetUsagePercentageTests {

        @Test
        @DisplayName("should return correct percentage for 50% usage")
        void shouldReturn50PercentForHalfUsage() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 5_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            double percentage = quotaService.getUsagePercentage(TENANT_A);

            assertThat(percentage).isEqualTo(50.0);
        }

        @Test
        @DisplayName("should return 0 for no usage")
        void shouldReturnZeroForNoUsage() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 0L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            double percentage = quotaService.getUsagePercentage(TENANT_A);

            assertThat(percentage).isZero();
        }

        @Test
        @DisplayName("should return 100 for full usage")
        void shouldReturn100ForFullUsage() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 10_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            double percentage = quotaService.getUsagePercentage(TENANT_A);

            assertThat(percentage).isEqualTo(100.0);
        }
    }

    // ========== isSoftLimitReached Tests ==========

    @Nested
    @DisplayName("isSoftLimitReached")
    class IsSoftLimitReachedTests {

        @Test
        @DisplayName("should return true when usage exceeds soft limit")
        void shouldReturnTrueWhenAboveSoftLimit() {
            // maxBytes=10000, softLimit=8000 (80%), used=9000
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 9_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            assertThat(quotaService.isSoftLimitReached(TENANT_A)).isTrue();
        }

        @Test
        @DisplayName("should return false when usage is below soft limit")
        void shouldReturnFalseWhenBelowSoftLimit() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 5_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            assertThat(quotaService.isSoftLimitReached(TENANT_A)).isFalse();
        }

        @Test
        @DisplayName("should return true when exactly at soft limit")
        void shouldReturnTrueWhenExactlyAtSoftLimit() {
            // Soft limit = 80% of 10000 = 8000
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 8_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            assertThat(quotaService.isSoftLimitReached(TENANT_A)).isTrue();
        }
    }

    // ========== isHardLimitReached Tests ==========

    @Nested
    @DisplayName("isHardLimitReached")
    class IsHardLimitReachedTests {

        @Test
        @DisplayName("should return true when usage equals hard limit")
        void shouldReturnTrueWhenAtHardLimit() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 10_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            assertThat(quotaService.isHardLimitReached(TENANT_A)).isTrue();
        }

        @Test
        @DisplayName("should return true when usage exceeds hard limit")
        void shouldReturnTrueWhenAboveHardLimit() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 15_000L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            assertThat(quotaService.isHardLimitReached(TENANT_A)).isTrue();
        }

        @Test
        @DisplayName("should return false when usage is below hard limit")
        void shouldReturnFalseWhenBelowHardLimit() {
            TenantStorageQuota quota = createQuota(TENANT_A, 10_000L, 9_999L);
            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

            assertThat(quotaService.isHardLimitReached(TENANT_A)).isFalse();
        }
    }

    // ========== Multi-Tenant Isolation Tests ==========

    @Nested
    @DisplayName("Multi-Tenant Quota Isolation")
    class MultiTenantIsolationTests {

        @Test
        @DisplayName("should maintain independent quotas for different tenants")
        void shouldMaintainIndependentQuotas() {
            TenantStorageQuota quotaA = createQuota(TENANT_A, 10_000L, 5_000L);
            TenantStorageQuota quotaB = createQuota(TENANT_B, 50_000L, 1_000L);

            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quotaA));
            when(quotaRepository.findByTenantId(TENANT_B)).thenReturn(Optional.of(quotaB));

            assertThat(quotaService.getUsagePercentage(TENANT_A)).isEqualTo(50.0);
            assertThat(quotaService.getUsagePercentage(TENANT_B)).isEqualTo(2.0);
        }

        @Test
        @DisplayName("should check quota independently per tenant")
        void shouldCheckQuotaIndependentlyPerTenant() {
            // Tenant A is near limit
            TenantStorageQuota quotaA = createQuota(TENANT_A, 10_000L, 9_500L);
            // Tenant B has plenty of room
            TenantStorageQuota quotaB = createQuota(TENANT_B, 100_000L, 1_000L);

            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quotaA));
            when(quotaRepository.findByTenantId(TENANT_B)).thenReturn(Optional.of(quotaB));

            // Same payload size: 1000 bytes
            QuotaStatus statusA = quotaService.checkQuota(TENANT_A, 1_000L);
            QuotaStatus statusB = quotaService.checkQuota(TENANT_B, 1_000L);

            // A: 9500 + 1000 = 10500 > 10000 -> hard cap reached
            assertThat(statusA).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
            // B: 1000 + 1000 = 2000 << 100000 -> OK
            assertThat(statusB).isEqualTo(QuotaStatus.OK);
        }

        @Test
        @DisplayName("should update usage independently per tenant")
        void shouldUpdateUsageIndependentlyPerTenant() {
            TenantStorageQuota quotaA = createQuota(TENANT_A, 100_000L, 0L);
            TenantStorageQuota quotaB = createQuota(TENANT_B, 100_000L, 0L);

            when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quotaA));
            when(quotaRepository.findByTenantId(TENANT_B)).thenReturn(Optional.of(quotaB));
            when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(30_000L);
            when(breakdownService.getTotalUsage(TENANT_B)).thenReturn(70_000L);

            quotaService.updateUsage(TENANT_A);
            quotaService.updateUsage(TENANT_B);

            verify(quotaRepository, times(2)).save(quotaCaptor.capture());
            var savedQuotas = quotaCaptor.getAllValues();

            assertThat(savedQuotas).hasSize(2);
            // First save is for tenant A
            assertThat(savedQuotas.get(0).getUsedBytes()).isEqualTo(30_000L);
            // Second save is for tenant B
            assertThat(savedQuotas.get(1).getUsedBytes()).isEqualTo(70_000L);
        }
    }

    // ========== Helper Methods ==========

    private TenantStorageQuota createQuota(String tenantId, long maxBytes, long usedBytes) {
        TenantStorageQuota quota = new TenantStorageQuota(tenantId, maxBytes);
        quota.setUsedBytes(usedBytes);
        return quota;
    }
}
