package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.OrgStorageUsageHistory;
import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.StorageUsageHistory;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import com.apimarketplace.common.storage.service.QuotaService;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.services.storage.StorageHistoryService;
import com.apimarketplace.orchestrator.services.storage.StorageReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for StorageQuotaController.
 * Covers: getQuota, getBreakdown, recalculateUsage (full reconciliation).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageQuotaController")
class StorageQuotaControllerTest {

    private static final String TENANT_ID = "tenant-001";

    @Mock
    private QuotaService quotaService;

    @Mock
    private StorageBreakdownService breakdownService;

    @Mock
    private StorageReconciliationService reconciliationService;

    @Mock
    private StorageHistoryService historyService;

    private StorageQuotaController controller;

    @BeforeEach
    void setUp() {
        controller = new StorageQuotaController(quotaService, breakdownService, reconciliationService, historyService);
    }

    private TenantStorageQuota createQuota(String tenantId, long usedBytes, long maxBytes) {
        TenantStorageQuota quota = new TenantStorageQuota(tenantId, maxBytes);
        quota.setUsedBytes(usedBytes);
        quota.setSoftLimitBytes((long) (maxBytes * 0.8));
        return quota;
    }

    private OrganizationStorageQuota createOrgQuota(String organizationId, long usedBytes, long maxBytes) {
        OrganizationStorageQuota quota = new OrganizationStorageQuota(organizationId, maxBytes);
        quota.setUsedBytes(usedBytes);
        quota.setSoftLimitBytes((long) (maxBytes * 0.8));
        return quota;
    }

    // ========================================================================
    // GET /api/storage/quota
    // ========================================================================

    @Nested
    @DisplayName("getQuota()")
    class GetQuotaTests {

        @Test
        @DisplayName("should return 200 with quota information")
        void shouldReturn200WithQuota() {
            TenantStorageQuota quota = createQuota(TENANT_ID, 1024L, 1_048_576L);
            when(quotaService.getQuota(TENANT_ID)).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.getQuota(TENANT_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().tenantId()).isEqualTo(TENANT_ID);
            assertThat(response.getBody().usedBytes()).isEqualTo(1024L);
            assertThat(response.getBody().maxBytes()).isEqualTo(1_048_576L);
            verify(quotaService).getQuota(TENANT_ID);
        }

        @Test
        @DisplayName("should return zero usage for new tenant")
        void shouldReturnZeroForNewTenant() {
            TenantStorageQuota quota = createQuota("new-tenant", 0L, 1_073_741_824L);
            when(quotaService.getQuota("new-tenant")).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.getQuota("new-tenant");

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().usedBytes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return correct status field")
        void shouldReturnCorrectStatus() {
            TenantStorageQuota quota = createQuota(TENANT_ID, 500_000L, 1_000_000L);
            when(quotaService.getQuota(TENANT_ID)).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.getQuota(TENANT_ID);

            assertThat(response.getBody().status()).isNotNull();
        }
    }

    // ========================================================================
    // GET /api/storage/quota/breakdown
    // ========================================================================

    @Nested
    @DisplayName("getBreakdown()")
    class GetBreakdownTests {

        @Test
        @DisplayName("should return all 9 categories when all exist")
        void shouldReturnAllCategories() {
            List<TenantStorageBreakdown> breakdown = List.of(
                    new TenantStorageBreakdown(TENANT_ID, "STEP_OUTPUTS", 10000L, 50),
                    new TenantStorageBreakdown(TENANT_ID, "FILES", 20000L, 30),
                    new TenantStorageBreakdown(TENANT_ID, "EXECUTION_DATA", 30000L, 100),
                    new TenantStorageBreakdown(TENANT_ID, "AGENTS", 5000L, 10),
                    new TenantStorageBreakdown(TENANT_ID, "INTERFACES", 3000L, 5),
                    new TenantStorageBreakdown(TENANT_ID, "CONVERSATIONS", 15000L, 200),
                    new TenantStorageBreakdown(TENANT_ID, "CONFIGURATION", 2000L, 20),
                    new TenantStorageBreakdown(TENANT_ID, "DATATABLES", 25000L, 500),
                    new TenantStorageBreakdown(TENANT_ID, "PUBLICATIONS", 8000L, 3)
            );
            when(breakdownService.getBreakdown(TENANT_ID)).thenReturn(breakdown);

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown(TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(9);
            assertThat(response.getBody()).extracting(StorageBreakdownDto::category)
                    .containsExactly("STEP_OUTPUTS", "FILES", "EXECUTION_DATA", "AGENTS",
                            "INTERFACES", "CONVERSATIONS", "CONFIGURATION", "DATATABLES", "PUBLICATIONS");
        }

        @Test
        @DisplayName("should return empty list for new tenant")
        void shouldReturnEmptyForNewTenant() {
            when(breakdownService.getBreakdown("new-tenant")).thenReturn(Collections.emptyList());

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown("new-tenant", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        @DisplayName("should correctly map entity to DTO fields")
        void shouldMapEntityToDto() {
            TenantStorageBreakdown entity = new TenantStorageBreakdown(TENANT_ID, "FILES", 99999L, 42);
            when(breakdownService.getBreakdown(TENANT_ID)).thenReturn(List.of(entity));

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown(TENANT_ID, null);

            StorageBreakdownDto dto = response.getBody().get(0);
            assertThat(dto.category()).isEqualTo("FILES");
            assertThat(dto.usedBytes()).isEqualTo(99999L);
            assertThat(dto.itemCount()).isEqualTo(42);
        }

        @Test
        @DisplayName("should handle single category breakdown")
        void shouldHandleSingleCategory() {
            when(breakdownService.getBreakdown(TENANT_ID))
                    .thenReturn(List.of(new TenantStorageBreakdown(TENANT_ID, "STEP_OUTPUTS", 1024L, 1)));

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown(TENANT_ID, null);

            assertThat(response.getBody()).hasSize(1);
        }

        @Test
        @DisplayName("should handle zero-byte categories")
        void shouldHandleZeroByteCategories() {
            when(breakdownService.getBreakdown(TENANT_ID))
                    .thenReturn(List.of(
                            new TenantStorageBreakdown(TENANT_ID, "FILES", 0L, 0),
                            new TenantStorageBreakdown(TENANT_ID, "AGENTS", 100L, 1)
                    ));

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown(TENANT_ID, null);

            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).usedBytes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should handle very large byte values")
        void shouldHandleLargeValues() {
            long oneTerabyte = 1_099_511_627_776L;
            when(breakdownService.getBreakdown(TENANT_ID))
                    .thenReturn(List.of(new TenantStorageBreakdown(TENANT_ID, "EXECUTION_DATA", oneTerabyte, 1_000_000)));

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown(TENANT_ID, null);

            assertThat(response.getBody().get(0).usedBytes()).isEqualTo(oneTerabyte);
            assertThat(response.getBody().get(0).itemCount()).isEqualTo(1_000_000);
        }

        @Test
        @DisplayName("should handle special characters in tenant ID")
        void shouldHandleSpecialTenantId() {
            String specialId = "auth0|user_123@example.com";
            when(breakdownService.getBreakdown(specialId)).thenReturn(Collections.emptyList());

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown(specialId, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(breakdownService).getBreakdown(specialId);
        }
    }

    // ========================================================================
    // POST /api/storage/quota/recalculate
    // ========================================================================

    @Nested
    @DisplayName("recalculateUsage()")
    class RecalculateUsageTests {

        @Test
        @DisplayName("should call reconciliation service and return updated quota")
        void shouldReconcileAndReturnQuota() {
            TenantStorageQuota quota = createQuota(TENANT_ID, 500_000L, 1_073_741_824L);
            when(quotaService.getQuota(TENANT_ID)).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.recalculateUsage(TENANT_ID, null);

            verify(reconciliationService).reconcileTenant(TENANT_ID);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().usedBytes()).isEqualTo(500_000L);
        }

        @Test
        @DisplayName("should call reconcileTenant before getQuota (correct order)")
        void shouldReconcileBeforeGettingQuota() {
            TenantStorageQuota quota = createQuota(TENANT_ID, 0L, 1_073_741_824L);
            when(quotaService.getQuota(TENANT_ID)).thenReturn(quota);

            controller.recalculateUsage(TENANT_ID, null);

            var inOrder = inOrder(reconciliationService, quotaService);
            inOrder.verify(reconciliationService).reconcileTenant(TENANT_ID);
            inOrder.verify(quotaService).getQuota(TENANT_ID);
        }

        @Test
        @DisplayName("should return quota with correct DTO mapping after reconciliation")
        void shouldReturnCorrectDtoMapping() {
            TenantStorageQuota quota = createQuota(TENANT_ID, 5_368_709_120L, 10_737_418_240L);
            when(quotaService.getQuota(TENANT_ID)).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.recalculateUsage(TENANT_ID, null);

            StorageQuotaDto dto = response.getBody();
            assertThat(dto.tenantId()).isEqualTo(TENANT_ID);
            assertThat(dto.maxBytes()).isEqualTo(10_737_418_240L);
            assertThat(dto.usedBytes()).isEqualTo(5_368_709_120L);
        }

        @Test
        @DisplayName("should handle special characters in tenant ID")
        void shouldHandleSpecialTenantId() {
            String specialId = "google-oauth2|12345";
            TenantStorageQuota quota = createQuota(specialId, 0L, 1_073_741_824L);
            when(quotaService.getQuota(specialId)).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.recalculateUsage(specialId, null);

            verify(reconciliationService).reconcileTenant(specialId);
            assertThat(response.getBody().tenantId()).isEqualTo(specialId);
        }
    }

    // ========================================================================
    // Org-scope routing (Issue #149)
    // ========================================================================

    @Nested
    @DisplayName("Org-scope routing (Issue #149)")
    class OrgScopeTests {

        private static final String ORG_ID = "org-42";

        @Test
        @DisplayName("regression: getQuota with X-Organization-ID refreshes org breakdown before reading quota")
        void quotaWithOrgRefreshesOrgBreakdownBeforeReadingQuota() {
            OrganizationStorageQuota quota = createOrgQuota(ORG_ID, 435_000_000L, 1_000_000_000L);
            when(quotaService.getOrganizationQuota(ORG_ID)).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.getQuota(TENANT_ID, ORG_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().tenantId()).isEqualTo(ORG_ID);
            assertThat(response.getBody().usedBytes()).isEqualTo(435_000_000L);
            var inOrder = inOrder(reconciliationService, quotaService);
            inOrder.verify(reconciliationService).refreshOrgBreakdown(ORG_ID);
            inOrder.verify(quotaService).getOrganizationQuota(ORG_ID);
            verify(quotaService, never()).getQuota(anyString());
        }

        @Test
        @DisplayName("getBreakdown with X-Organization-ID returns the org rollup, not the tenant view")
        void breakdownWithOrgReturnsOrgRollup() {
            List<OrgStorageBreakdown> orgRows = List.of(
                    new OrgStorageBreakdown(ORG_ID, "FILES", 1024L, 5),
                    new OrgStorageBreakdown(ORG_ID, "CONFIGURATION", 8192L, 3)
            );
            when(breakdownService.getOrgBreakdown(ORG_ID)).thenReturn(orgRows);

            ResponseEntity<List<StorageBreakdownDto>> response = controller.getBreakdown(TENANT_ID, ORG_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody()).extracting(StorageBreakdownDto::category)
                    .containsExactly("FILES", "CONFIGURATION");
            verify(reconciliationService).refreshOrgBreakdown(ORG_ID);
            verify(breakdownService).getOrgBreakdown(ORG_ID);
            verify(breakdownService, never()).getBreakdown(anyString());
        }

        @Test
        @DisplayName("getBreakdown with blank organizationId falls back to the tenant rollup")
        void breakdownWithBlankOrgFallsBackToTenant() {
            when(breakdownService.getBreakdown(TENANT_ID)).thenReturn(Collections.emptyList());

            controller.getBreakdown(TENANT_ID, "   ");

            verify(breakdownService).getBreakdown(TENANT_ID);
            verify(breakdownService, never()).getOrgBreakdown(anyString());
            verify(reconciliationService, never()).refreshOrgBreakdown(anyString());
        }

        @Test
        @DisplayName("recalculate with X-Organization-ID returns the org quota, not the tenant quota")
        void recalculateWithOrgReturnsOrgQuota() {
            OrganizationStorageQuota quota = createOrgQuota(ORG_ID, 1234L, 10_000L);
            when(quotaService.getOrganizationQuota(ORG_ID)).thenReturn(quota);

            ResponseEntity<StorageQuotaDto> response = controller.recalculateUsage(TENANT_ID, ORG_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().tenantId()).isEqualTo(ORG_ID);
            assertThat(response.getBody().usedBytes()).isEqualTo(1234L);
            verify(reconciliationService).reconcileOrganization(ORG_ID);
            verify(quotaService).updateOrganizationUsage(ORG_ID);
            verify(quotaService).getOrganizationQuota(ORG_ID);
            verify(reconciliationService, never()).reconcileTenant(anyString());
            verify(quotaService, never()).getQuota(anyString());
        }

        @Test
        @DisplayName("getHistory with X-Organization-ID returns org snapshots")
        void historyWithOrgReturnsOrgSnapshots() {
            LocalDate today = LocalDate.now();
            List<OrgStorageUsageHistory> orgSeries = List.of(
                    new OrgStorageUsageHistory(ORG_ID, "FILES", 1024L, 5, today.minusDays(1)),
                    new OrgStorageUsageHistory(ORG_ID, "FILES", 2048L, 6, today)
            );
            when(historyService.getOrgHistory(ORG_ID, 30)).thenReturn(orgSeries);

            ResponseEntity<List<StorageHistoryDto>> response = controller.getHistory(TENANT_ID, ORG_ID, 30);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).category()).isEqualTo("FILES");
            assertThat(response.getBody().get(1).usedBytes()).isEqualTo(2048L);
            verify(historyService).getOrgHistory(ORG_ID, 30);
            verify(historyService, never()).getHistory(anyString(), anyInt());
        }

        @Test
        @DisplayName("getHistory without organizationId returns tenant snapshots (regression guard)")
        void historyWithoutOrgReturnsTenantSnapshots() {
            when(historyService.getHistory(TENANT_ID, 30)).thenReturn(
                    List.of(new StorageUsageHistory(TENANT_ID, "FILES", 1024L, 5, LocalDate.now())));

            ResponseEntity<List<StorageHistoryDto>> response = controller.getHistory(TENANT_ID, null, 30);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            verify(historyService).getHistory(TENANT_ID, 30);
            verify(historyService, never()).getOrgHistory(anyString(), anyInt());
        }

        @Test
        @DisplayName("recalculate with X-Organization-ID reconciles only the active org")
        void recalculateWithOrgRunsOrgReconcilerOnly() {
            OrganizationStorageQuota quota = createOrgQuota(ORG_ID, 100L, 1_000_000L);
            when(quotaService.getOrganizationQuota(ORG_ID)).thenReturn(quota);

            controller.recalculateUsage(TENANT_ID, ORG_ID);

            verify(reconciliationService).reconcileOrganization(ORG_ID);
            verify(reconciliationService, never()).reconcileTenant(anyString());
            verify(quotaService).updateOrganizationUsage(ORG_ID);
        }

        @Test
        @DisplayName("recalculate without organizationId does NOT call reconcileOrganization (regression guard)")
        void recalculateWithoutOrgSkipsOrgReconcile() {
            TenantStorageQuota quota = createQuota(TENANT_ID, 100L, 1_000_000L);
            when(quotaService.getQuota(TENANT_ID)).thenReturn(quota);

            controller.recalculateUsage(TENANT_ID, null);

            verify(reconciliationService, never()).reconcileOrganization(anyString());
            verify(reconciliationService).reconcileTenant(TENANT_ID);
        }
    }

    // ========================================================================
    // DTO Tests
    // ========================================================================

    @Nested
    @DisplayName("StorageQuotaDto formatting")
    class StorageQuotaDtoTests {

        @Test
        @DisplayName("should format zero bytes as '0 B'")
        void shouldFormatZeroBytes() {
            StorageQuotaDto dto = new StorageQuotaDto(TENANT_ID, 0L, 0L, 0L, 0L, 0L, 0.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).isEqualTo("0 B");
        }

        @Test
        @DisplayName("should format bytes in B range")
        void shouldFormatBRange() {
            StorageQuotaDto dto = new StorageQuotaDto(TENANT_ID, 500L, 0L, 0L, 0L, 0L, 0.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).isEqualTo("500 B");
        }

        @Test
        @DisplayName("should format bytes in KB range")
        void shouldFormatKbRange() {
            StorageQuotaDto dto = new StorageQuotaDto(TENANT_ID, 2048L, 0L, 0L, 0L, 0L, 0.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).containsPattern("2[.,]0 KB");
        }

        @Test
        @DisplayName("should format bytes in MB range")
        void shouldFormatMbRange() {
            StorageQuotaDto dto = new StorageQuotaDto(TENANT_ID, 10_485_760L, 0L, 0L, 0L, 0L, 0.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).containsPattern("10[.,]0 MB");
        }

        @Test
        @DisplayName("should format bytes in GB range")
        void shouldFormatGbRange() {
            StorageQuotaDto dto = new StorageQuotaDto(TENANT_ID, 1_073_741_824L, 0L, 0L, 0L, 0L, 0.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).containsPattern("1[.,]00 GB");
        }
    }

    // ========================================================================
    // StorageBreakdownDto Tests
    // ========================================================================

    @Nested
    @DisplayName("StorageBreakdownDto")
    class StorageBreakdownDtoTests {

        @Test
        @DisplayName("should create record with correct values")
        void shouldCreateRecord() {
            Instant now = Instant.now();
            StorageBreakdownDto dto = new StorageBreakdownDto("FILES", 1024L, 5, now);

            assertThat(dto.category()).isEqualTo("FILES");
            assertThat(dto.usedBytes()).isEqualTo(1024L);
            assertThat(dto.itemCount()).isEqualTo(5);
            assertThat(dto.calculatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("should support null calculatedAt")
        void shouldSupportNullCalculatedAt() {
            StorageBreakdownDto dto = new StorageBreakdownDto("AGENTS", 0L, 0, null);
            assertThat(dto.calculatedAt()).isNull();
        }

        @Test
        @DisplayName("should implement equals for records")
        void shouldImplementEquals() {
            Instant now = Instant.now();
            StorageBreakdownDto dto1 = new StorageBreakdownDto("FILES", 1024L, 5, now);
            StorageBreakdownDto dto2 = new StorageBreakdownDto("FILES", 1024L, 5, now);

            assertThat(dto1).isEqualTo(dto2);
            assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different categories")
        void shouldNotBeEqualForDifferentCategories() {
            Instant now = Instant.now();
            StorageBreakdownDto dto1 = new StorageBreakdownDto("FILES", 1024L, 5, now);
            StorageBreakdownDto dto2 = new StorageBreakdownDto("AGENTS", 1024L, 5, now);

            assertThat(dto1).isNotEqualTo(dto2);
        }
    }
}
