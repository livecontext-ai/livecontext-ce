package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import com.apimarketplace.common.storage.domain.QuotaStatus;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("QuotaService Tests")
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    private static final String TENANT_ID = "tenant-123";
    // Matches QuotaService.DEFAULT_MAX_BYTES (V198: bumped from 1 GB → 100 MB
    // to align the library fallback with the FREE plan's seed value).
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
        // Default to enforced quotas; CE Free tests override the explicit
        // unlimited-resource bypass. Use lenient() because not every test path
        // consults the gate.
        org.mockito.Mockito.lenient().when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        quotaService = new QuotaService(storageRepository, quotaRepository, orgQuotaRepository, breakdownService, editionProvider);
    }

    @Nested
    @DisplayName("checkQuota")
    class CheckQuotaTests {

        @Test
        @DisplayName("should return OK when tenant has enough quota")
        void returnOkWhenEnoughQuota() {
            TenantStorageQuota quota = createQuota(1000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_ID, 100L);

            assertThat(result).isEqualTo(QuotaStatus.OK);
        }

        @Test
        @DisplayName("should return HARD_LIMIT_REACHED when projected usage exceeds hard cap after soft is reached")
        void returnHardLimitReachedWhenProjectedUsageExceedsHardAfterSoft() {
            // Regression: pre-fix this returned SOFT_LIMIT_REACHED, and callers
            // only block HARD_LIMIT_REACHED, so a workspace already above soft
            // could keep writing past the hard cap.
            TenantStorageQuota quota = createQuota(1000L, 900L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_ID, 200L);

            assertThat(result).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("should return HARD_LIMIT_REACHED when cannot store additional bytes")
        void returnHardLimitReachedWhenCannotStore() {
            // Hard limit = 1000, used = 950, trying to add 100 = 1050 > 1000
            TenantStorageQuota quota = createQuota(1000L, 950L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_ID, 100L);

            assertThat(result).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("should create default quota for new tenant")
        void createDefaultQuotaForNewTenant() {
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            quotaService.checkQuota(TENANT_ID, 100L);

            verify(quotaRepository).save(quotaCaptor.capture());
            TenantStorageQuota saved = quotaCaptor.getValue();
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getMaxBytes()).isEqualTo(DEFAULT_MAX_BYTES);
        }

        @Test
        @DisplayName("CE edition → returns OK regardless of usage; quota repository not consulted")
        void checkQuotaInCeReturnsOkRegardlessOfUsage() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(true);

            // Usage at hard limit - would normally trigger HARD_LIMIT_REACHED in cloud
            QuotaStatus result = quotaService.checkQuota(TENANT_ID, Long.MAX_VALUE);

            assertThat(result).isEqualTo(QuotaStatus.OK);
            // CE short-circuits before any repository lookup
            verify(quotaRepository, never()).findByTenantId(any());
            verify(quotaRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cloud edition → existing enforcement preserved (regression guard)")
        void checkQuotaInCloudStillEnforcesHardLimit() {
            // editionProvider default is false (CLOUD) per setUp
            TenantStorageQuota quota = createQuota(1000L, 950L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_ID, 100L);

            assertThat(result).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("huge additionalBytes cannot overflow into OK")
        void hugeAdditionalBytesCannotOverflowIntoOk() {
            TenantStorageQuota quota = createQuota(1000L, 1L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_ID, Long.MAX_VALUE);

            assertThat(result).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("Self-Hosted Enterprise does not inherit CE Free unlimited storage bypass")
        void selfHostedEnterpriseStillEnforcesStorageQuota() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
            TenantStorageQuota quota = createQuota(1000L, 950L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            QuotaStatus result = quotaService.checkQuota(TENANT_ID, 100L);

            assertThat(result).isIn(QuotaStatus.HARD_LIMIT_REACHED, QuotaStatus.SOFT_LIMIT_REACHED);
            verify(quotaRepository).findByTenantId(TENANT_ID);
        }
    }

    @Nested
    @DisplayName("updateUsage")
    class UpdateUsageTests {

        @Test
        @DisplayName("should update usage from breakdown service (not storageRepository)")
        void updateUsageFromBreakdownService() {
            TenantStorageQuota quota = createQuota(1000L, 0L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));
            when(breakdownService.getTotalUsage(TENANT_ID)).thenReturn(500L);

            quotaService.updateUsage(TENANT_ID);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUsedBytes()).isEqualTo(500L);
            // Should NOT call storageRepository.calculateTenantUsage
            verify(storageRepository, never()).calculateTenantUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should update timestamp on usage update")
        void updateTimestampOnUsageUpdate() {
            TenantStorageQuota quota = createQuota(1000L, 0L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));
            when(breakdownService.getTotalUsage(TENANT_ID)).thenReturn(500L);

            quotaService.updateUsage(TENANT_ID);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should set usage to zero when breakdown returns zero")
        void shouldSetZeroUsage() {
            TenantStorageQuota quota = createQuota(1000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));
            when(breakdownService.getTotalUsage(TENANT_ID)).thenReturn(0L);

            quotaService.updateUsage(TENANT_ID);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUsedBytes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should handle very large usage from breakdown")
        void shouldHandleLargeUsage() {
            long tenGB = 10_737_418_240L;
            TenantStorageQuota quota = createQuota(tenGB, 0L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));
            when(breakdownService.getTotalUsage(TENANT_ID)).thenReturn(tenGB);

            quotaService.updateUsage(TENANT_ID);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getUsedBytes()).isEqualTo(tenGB);
        }

        @Test
        @DisplayName("should create default quota for new tenant during updateUsage")
        void shouldCreateDefaultForNewTenant() {
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(breakdownService.getTotalUsage(TENANT_ID)).thenReturn(100L);

            quotaService.updateUsage(TENANT_ID);

            // save called twice: once for createDefaultQuota, once for updateUsage
            verify(quotaRepository, atLeast(1)).save(any());
        }
    }

    @Nested
    @DisplayName("getQuota")
    class GetQuotaTests {

        @Test
        @DisplayName("should return existing quota")
        void returnExistingQuota() {
            TenantStorageQuota quota = createQuota(2000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            TenantStorageQuota result = quotaService.getQuota(TENANT_ID);

            assertThat(result.getMaxBytes()).isEqualTo(2000L);
            assertThat(result.getUsedBytes()).isEqualTo(500L);
        }

        @Test
        @DisplayName("should create and return default quota for new tenant")
        void createAndReturnDefaultQuotaForNewTenant() {
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantStorageQuota result = quotaService.getQuota(TENANT_ID);

            assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(result.getMaxBytes()).isEqualTo(DEFAULT_MAX_BYTES);
            verify(quotaRepository).save(any());
        }
    }

    @Nested
    @DisplayName("updateLimits")
    class UpdateLimitsTests {

        @Test
        @DisplayName("should update max bytes")
        void updateMaxBytes() {
            TenantStorageQuota quota = createQuota(1000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            quotaService.updateLimits(TENANT_ID, 5000L, 0.8);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getMaxBytes()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("should calculate soft limit based on ratio")
        void calculateSoftLimitBasedOnRatio() {
            TenantStorageQuota quota = createQuota(1000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            quotaService.updateLimits(TENANT_ID, 10000L, 0.7);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getSoftLimitBytes()).isEqualTo(7000L);
        }

        @Test
        @DisplayName("should set hard limit equal to max bytes")
        void setHardLimitEqualToMaxBytes() {
            TenantStorageQuota quota = createQuota(1000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            quotaService.updateLimits(TENANT_ID, 8000L, 0.8);

            verify(quotaRepository).save(quotaCaptor.capture());
            assertThat(quotaCaptor.getValue().getHardLimitBytes()).isEqualTo(8000L);
        }
    }

    @Nested
    @DisplayName("getCurrentUsage")
    class GetCurrentUsageTests {

        @Test
        @DisplayName("should return current usage from storage repository")
        void returnCurrentUsageFromStorageRepository() {
            when(storageRepository.calculateTenantUsage(TENANT_ID)).thenReturn(12345L);

            long result = quotaService.getCurrentUsage(TENANT_ID);

            assertThat(result).isEqualTo(12345L);
        }
    }

    @Nested
    @DisplayName("getUsagePercentage")
    class GetUsagePercentageTests {

        @Test
        @DisplayName("should return correct usage percentage")
        void returnCorrectUsagePercentage() {
            TenantStorageQuota quota = createQuota(1000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            double result = quotaService.getUsagePercentage(TENANT_ID);

            assertThat(result).isEqualTo(50.0);
        }

        @Test
        @DisplayName("should return 0 for empty usage")
        void returnZeroForEmptyUsage() {
            TenantStorageQuota quota = createQuota(1000L, 0L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            double result = quotaService.getUsagePercentage(TENANT_ID);

            assertThat(result).isZero();
        }
    }

    @Nested
    @DisplayName("isSoftLimitReached")
    class IsSoftLimitReachedTests {

        @Test
        @DisplayName("should return true when soft limit reached")
        void returnTrueWhenSoftLimitReached() {
            TenantStorageQuota quota = createQuota(1000L, 850L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            boolean result = quotaService.isSoftLimitReached(TENANT_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when below soft limit")
        void returnFalseWhenBelowSoftLimit() {
            TenantStorageQuota quota = createQuota(1000L, 500L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            boolean result = quotaService.isSoftLimitReached(TENANT_ID);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("isHardLimitReached")
    class IsHardLimitReachedTests {

        @Test
        @DisplayName("should return true when hard limit reached")
        void returnTrueWhenHardLimitReached() {
            TenantStorageQuota quota = createQuota(1000L, 1000L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            boolean result = quotaService.isHardLimitReached(TENANT_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when below hard limit")
        void returnFalseWhenBelowHardLimit() {
            TenantStorageQuota quota = createQuota(1000L, 999L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(quota));

            boolean result = quotaService.isHardLimitReached(TENANT_ID);

            assertThat(result).isFalse();
        }
    }

    // Helper method
    private TenantStorageQuota createQuota(long maxBytes, long usedBytes) {
        TenantStorageQuota quota = new TenantStorageQuota(TENANT_ID, maxBytes);
        quota.setUsedBytes(usedBytes);
        return quota;
    }

    // ============================================================
    // PR18 - Organization-scope tests (strict-isolation contract).
    // The same checkQuota / getQuota / updateUsage / updateLimits behaviours
    // must hold when the scope is keyed by organization_id instead of
    // tenant_id, and the two scopes MUST be independent (a soft/hard limit
    // hit on one side never affects the other).
    // ============================================================

    @Nested
    @DisplayName("Organization-scope checkOrganizationQuota")
    class CheckOrganizationQuotaTests {

        private static final String ORG_ID = "org-42";

        @Test
        @DisplayName("returns OK when org has headroom")
        void returnsOkWhenOrgHasHeadroom() {
            OrganizationStorageQuota q = createOrgQuota(1000L, 500L);
            when(orgQuotaRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(q));

            assertThat(quotaService.checkOrganizationQuota(ORG_ID, 100L)).isEqualTo(QuotaStatus.OK);
        }

        @Test
        @DisplayName("returns HARD_LIMIT_REACHED when adding bytes would exceed cap")
        void returnsHardLimitWhenCapExceeded() {
            OrganizationStorageQuota q = createOrgQuota(1000L, 950L);
            when(orgQuotaRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(q));

            assertThat(quotaService.checkOrganizationQuota(ORG_ID, 200L))
                    .isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        }

        @Test
        @DisplayName("CE edition short-circuits to OK and never reads org quota row")
        void ceReturnsOkRegardlessOfUsage() {
            when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(true);

            QuotaStatus result = quotaService.checkOrganizationQuota(ORG_ID, Long.MAX_VALUE);

            assertThat(result).isEqualTo(QuotaStatus.OK);
            verify(orgQuotaRepository, never()).findByOrganizationId(any());
        }

        @Test
        @DisplayName("auto-creates default FREE quota row when org has none")
        void autoCreatesDefaultRowForNewOrg() {
            when(orgQuotaRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
            when(orgQuotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            quotaService.checkOrganizationQuota(ORG_ID, 1L);

            ArgumentCaptor<OrganizationStorageQuota> cap = ArgumentCaptor.forClass(OrganizationStorageQuota.class);
            verify(orgQuotaRepository).save(cap.capture());
            assertThat(cap.getValue().getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(cap.getValue().getMaxBytes()).isEqualTo(DEFAULT_MAX_BYTES);
        }
    }

    @Nested
    @DisplayName("checkQuotaForScope routing (strict isolation entry point)")
    class CheckQuotaForScopeTests {

        @Test
        @DisplayName("orgId null → tenant scope; tenant repo consulted, org repo untouched")
        void nullOrgIdRoutesToTenantScope() {
            TenantStorageQuota tenantQuota = createQuota(1000L, 100L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(tenantQuota));

            QuotaStatus result = quotaService.checkQuotaForScope(TENANT_ID, null, 50L);

            assertThat(result).isEqualTo(QuotaStatus.OK);
            verify(quotaRepository).findByTenantId(TENANT_ID);
            verify(orgQuotaRepository, never()).findByOrganizationId(any());
        }

        @Test
        @DisplayName("orgId blank → tenant scope (blank treated like null)")
        void blankOrgIdRoutesToTenantScope() {
            TenantStorageQuota tenantQuota = createQuota(1000L, 100L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(tenantQuota));

            quotaService.checkQuotaForScope(TENANT_ID, "  ", 50L);

            verify(quotaRepository).findByTenantId(TENANT_ID);
            verify(orgQuotaRepository, never()).findByOrganizationId(any());
        }

        @Test
        @DisplayName("orgId present → org scope; org repo consulted, tenant repo untouched")
        void presentOrgIdRoutesToOrgScope() {
            String orgId = "org-99";
            OrganizationStorageQuota orgQuota = createOrgQuota(2000L, 100L);
            when(orgQuotaRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(orgQuota));

            QuotaStatus result = quotaService.checkQuotaForScope(TENANT_ID, orgId, 50L);

            assertThat(result).isEqualTo(QuotaStatus.OK);
            verify(orgQuotaRepository).findByOrganizationId(orgId);
            verify(quotaRepository, never()).findByTenantId(any());
        }

        @Test
        @DisplayName("strict isolation: org cap hit does NOT affect tenant scope decision (regression)")
        void orgCapHitDoesNotLeakToTenantScope() {
            // Org is at hard cap
            OrganizationStorageQuota orgQuota = createOrgQuota(1000L, 1000L);
            when(orgQuotaRepository.findByOrganizationId("org-x")).thenReturn(Optional.of(orgQuota));
            // Tenant has plenty
            TenantStorageQuota tenantQuota = createQuota(1000L, 100L);
            when(quotaRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(tenantQuota));

            // Same tenant queried with org context → HARD_LIMIT
            QuotaStatus orgResult = quotaService.checkQuotaForScope(TENANT_ID, "org-x", 50L);
            // Same tenant queried in personal context → OK
            QuotaStatus personalResult = quotaService.checkQuotaForScope(TENANT_ID, null, 50L);

            assertThat(orgResult).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
            assertThat(personalResult).isEqualTo(QuotaStatus.OK);
        }
    }

    @Nested
    @DisplayName("updateOrganizationUsage / updateOrganizationLimits")
    class OrganizationUpdateTests {

        private static final String ORG_ID = "org-42";

        @Test
        @DisplayName("updateOrganizationUsage reads SUM(size_bytes) from storage and saves it")
        void updateOrgUsageReadsStorageRepo() {
            OrganizationStorageQuota q = createOrgQuota(1000L, 0L);
            when(orgQuotaRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(q));
            when(storageRepository.calculateOrganizationUsage(ORG_ID)).thenReturn(777L);

            quotaService.updateOrganizationUsage(ORG_ID);

            ArgumentCaptor<OrganizationStorageQuota> cap = ArgumentCaptor.forClass(OrganizationStorageQuota.class);
            verify(orgQuotaRepository).save(cap.capture());
            assertThat(cap.getValue().getUsedBytes()).isEqualTo(777L);
            // Org usage MUST NOT route through tenant breakdown service (separate scope).
            verify(breakdownService, never()).getTotalUsage(anyString());
        }

        @Test
        @DisplayName("updateOrganizationLimits sets max/soft/hard from owner plan")
        void updateOrgLimitsAppliesRatio() {
            OrganizationStorageQuota q = createOrgQuota(1000L, 100L);
            when(orgQuotaRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(q));

            quotaService.updateOrganizationLimits(ORG_ID, 10_000L, 0.8);

            ArgumentCaptor<OrganizationStorageQuota> cap = ArgumentCaptor.forClass(OrganizationStorageQuota.class);
            verify(orgQuotaRepository).save(cap.capture());
            assertThat(cap.getValue().getMaxBytes()).isEqualTo(10_000L);
            assertThat(cap.getValue().getSoftLimitBytes()).isEqualTo(8_000L);
            assertThat(cap.getValue().getHardLimitBytes()).isEqualTo(10_000L);
        }
    }

    private OrganizationStorageQuota createOrgQuota(long maxBytes, long usedBytes) {
        OrganizationStorageQuota q = new OrganizationStorageQuota("org-42", maxBytes);
        q.setUsedBytes(usedBytes);
        return q;
    }
}
