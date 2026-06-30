package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import com.apimarketplace.common.storage.repository.OrganizationStorageQuotaRepository;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.repository.TenantStorageQuotaRepository;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Runtime cache-eviction test for {@link QuotaService}.
 *
 * <p>Pairs with {@code QuotaServiceIntegrationTest.mustEvictBothCaches} (annotation-level
 * reflection guard). That guard proves the annotation is declared correctly; this test
 * proves the Spring AOP proxy actually intercepts {@code updateUsage} and evicts both
 * caches from the live {@link CacheManager}. Without this, a missing {@code @EnableCaching},
 * a self-invocation that bypasses the proxy, or a mis-wired cache manager would all let
 * the reflection guard pass while production still serves stale values - the exact bug
 * that round-2 of the storage-drift fix targeted.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {QuotaService.class, QuotaServiceCacheEvictionTest.CacheTestConfig.class})
@Import(QuotaServiceCacheEvictionTest.CacheTestConfig.class)
@DisplayName("QuotaService runtime cache-eviction (Spring AOP)")
class QuotaServiceCacheEvictionTest {

    private static final String TENANT_A = "tenant-a";

    @TestConfiguration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            // PR18 - orgQuotaStatus + orgQuota names match StorageConstants
            // CACHE_ORG_QUOTA_STATUS + CACHE_ORG_QUOTA so org-side @Cacheable /
            // @CacheEvict resolve. Without these the round-2 cache-eviction
            // tests below would fail with "Cannot find cache named …".
            return new ConcurrentMapCacheManager(
                    "quotaStatus", "tenantQuota",
                    "orgQuotaStatus", "orgQuota");
        }
    }

    // Inject by interface - QuotaService implements QuotaOperations, so Spring
    // wraps it in a JDK dynamic proxy whose runtime type is the interface, not the
    // concrete class. Autowiring by the concrete class fails with the JDK proxy
    // strategy. Production callers also use the interface (auth-client, billing, …).
    @Autowired
    private QuotaOperations quotaService;

    @Autowired
    private CacheManager cacheManager;

    @MockBean
    private StorageRepository storageRepository;

    @MockBean
    private TenantStorageQuotaRepository quotaRepository;

    @MockBean
    private OrganizationStorageQuotaRepository orgQuotaRepository;

    @MockBean
    private StorageBreakdownService breakdownService;

    @MockBean
    private AppEditionProvider editionProvider;

    private Cache tenantQuotaCache;
    private Cache quotaStatusCache;
    private Cache orgQuotaCache;
    private Cache orgQuotaStatusCache;

    @BeforeEach
    void setUp() {
        when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        tenantQuotaCache = cacheManager.getCache("tenantQuota");
        quotaStatusCache = cacheManager.getCache("quotaStatus");
        orgQuotaCache = cacheManager.getCache("orgQuota");
        orgQuotaStatusCache = cacheManager.getCache("orgQuotaStatus");
        tenantQuotaCache.clear();
        quotaStatusCache.clear();
        orgQuotaCache.clear();
        orgQuotaStatusCache.clear();
    }

    @AfterEach
    void tearDown() {
        tenantQuotaCache.clear();
        quotaStatusCache.clear();
        orgQuotaCache.clear();
        orgQuotaStatusCache.clear();
    }

    @Test
    @DisplayName("updateUsage evicts tenantQuota from the live CacheManager (Spring proxy wired)")
    void updateUsageEvictsTenantQuotaAtRuntime() {
        TenantStorageQuota quota = new TenantStorageQuota(TENANT_A, 1_073_741_824L);
        quota.setUsedBytes(123L);
        when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));
        when(quotaRepository.save(quota)).thenReturn(quota);
        when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(456L);

        // Warm the quota row cache via the proxied @Cacheable method.
        quotaService.getQuota(TENANT_A);
        quotaService.checkQuota(TENANT_A, 0L);

        assertThat(tenantQuotaCache.get(TENANT_A))
                .as("getQuota should have populated the tenantQuota cache via @Cacheable")
                .isNotNull();
        assertThat(quotaStatusCache.get(TENANT_A))
                .as("checkQuota decisions include additionalBytes and must not be cached only by tenant")
                .isNull();

        // Trigger the proxied @CacheEvict.
        quotaService.updateUsage(TENANT_A);

        // The row cache must be empty; otherwise the next getQuota would return
        // the cached 123L instead of the fresh 456L.
        assertThat(tenantQuotaCache.get(TENANT_A))
                .as("REGRESSION GUARD: updateUsage MUST evict tenantQuota - without this, the EXECUTION_DATA inline refresh and recalculateUsage both serve stale gauge totals on cache hit")
                .isNull();
    }

    @Test
    @DisplayName("checkQuota re-evaluates each payload size instead of reusing a tenant-only OK")
    void checkQuotaDoesNotReuseTenantOnlyStatusAcrossPayloadSizes() {
        TenantStorageQuota quota = new TenantStorageQuota(TENANT_A, 100L);
        quota.setUsedBytes(0L);
        when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

        assertThat(quotaService.checkQuota(TENANT_A, 1L)).isEqualTo(QuotaStatus.OK);
        assertThat(quotaService.checkQuota(TENANT_A, 101L)).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        assertThat(quotaStatusCache.get(TENANT_A))
                .as("REGRESSION GUARD: a prior small upload check must not cache OK for a later oversized upload")
                .isNull();
    }

    @Test
    @DisplayName("checkQuota recalculates per additionalBytes instead of reusing a tenant-level OK")
    void checkQuotaRecalculatesForAdditionalBytes() {
        TenantStorageQuota quota = new TenantStorageQuota(TENANT_A, 1_000L);
        quota.setUsedBytes(900L);
        when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quota));

        QuotaStatus smallWrite = quotaService.checkQuota(TENANT_A, 50L);
        QuotaStatus largeWrite = quotaService.checkQuota(TENANT_A, 200L);

        assertThat(smallWrite).isEqualTo(QuotaStatus.OK);
        assertThat(largeWrite).isNotEqualTo(QuotaStatus.OK);
        assertThat(quotaStatusCache.get(TENANT_A)).isNull();
        verify(quotaRepository, times(2)).findByTenantId(TENANT_A);
    }

    @Test
    @DisplayName("eviction is keyed per-tenant - updating tenant-A leaves tenant-B's cache intact")
    void evictionIsKeyedPerTenant() {
        String tenantB = "tenant-b";
        TenantStorageQuota quotaA = new TenantStorageQuota(TENANT_A, 1_073_741_824L);
        TenantStorageQuota quotaB = new TenantStorageQuota(tenantB, 1_073_741_824L);
        when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(quotaA));
        when(quotaRepository.findByTenantId(tenantB)).thenReturn(Optional.of(quotaB));
        when(quotaRepository.save(quotaA)).thenReturn(quotaA);
        when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(100L);

        quotaService.getQuota(TENANT_A);
        quotaService.getQuota(tenantB);
        assertThat(tenantQuotaCache.get(TENANT_A)).isNotNull();
        assertThat(tenantQuotaCache.get(tenantB)).isNotNull();

        quotaService.updateUsage(TENANT_A);

        assertThat(tenantQuotaCache.get(TENANT_A))
                .as("tenant-A's cache must be evicted")
                .isNull();
        assertThat(tenantQuotaCache.get(tenantB))
                .as("tenant-B must NOT be touched by tenant-A's updateUsage - key=#tenantId protects multi-tenant isolation")
                .isNotNull();
    }

    // ============================================================
    // PR18 round-2 - org-scope cache eviction (audit-2 finding M2)
    // ============================================================

    @Test
    @DisplayName("updateOrganizationUsage evicts orgQuota")
    void updateOrganizationUsageEvictsOrgQuota() {
        String orgId = "org-cache-test";
        OrganizationStorageQuota q = new OrganizationStorageQuota(orgId, 1_000_000L);
        when(orgQuotaRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(q));
        when(orgQuotaRepository.save(q)).thenReturn(q);
        when(storageRepository.calculateOrganizationUsage(orgId)).thenReturn(123L);

        // Warm the org quota row cache.
        quotaService.getOrganizationQuota(orgId);
        quotaService.checkOrganizationQuota(orgId, 0L);

        assertThat(orgQuotaCache.get(orgId))
                .as("getOrganizationQuota should populate the orgQuota cache")
                .isNotNull();
        assertThat(orgQuotaStatusCache.get(orgId))
                .as("checkOrganizationQuota decisions include additionalBytes and must not be cached only by org")
                .isNull();

        // Trigger eviction.
        quotaService.updateOrganizationUsage(orgId);

        assertThat(orgQuotaCache.get(orgId))
                .as("REGRESSION GUARD: updateOrganizationUsage MUST evict orgQuota - without this, org gauge serves stale used_bytes after a delete or reconcile")
                .isNull();
    }

    @Test
    @DisplayName("checkOrganizationQuota re-evaluates each payload size")
    void checkOrganizationQuotaDoesNotReuseOrgOnlyStatusAcrossPayloadSizes() {
        String orgId = "org-status-size";
        OrganizationStorageQuota quota = new OrganizationStorageQuota(orgId, 100L);
        quota.setUsedBytes(0L);
        when(orgQuotaRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(quota));

        assertThat(quotaService.checkOrganizationQuota(orgId, 1L)).isEqualTo(QuotaStatus.OK);
        assertThat(quotaService.checkOrganizationQuota(orgId, 101L)).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
        assertThat(orgQuotaStatusCache.get(orgId))
                .as("REGRESSION GUARD: a prior small org upload check must not cache OK for a later oversized upload")
                .isNull();
    }

    @Test
    @DisplayName("checkOrganizationQuota recalculates per additionalBytes instead of reusing an org-level OK")
    void checkOrganizationQuotaRecalculatesForAdditionalBytes() {
        String orgId = "org-cache-additional-bytes";
        OrganizationStorageQuota quota = new OrganizationStorageQuota(orgId, 1_000L);
        quota.setUsedBytes(900L);
        when(orgQuotaRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(quota));

        QuotaStatus smallWrite = quotaService.checkOrganizationQuota(orgId, 50L);
        QuotaStatus largeWrite = quotaService.checkOrganizationQuota(orgId, 200L);

        assertThat(smallWrite).isEqualTo(QuotaStatus.OK);
        assertThat(largeWrite).isNotEqualTo(QuotaStatus.OK);
        assertThat(orgQuotaStatusCache.get(orgId)).isNull();
        verify(orgQuotaRepository, times(2)).findByOrganizationId(orgId);
    }

    @Test
    @DisplayName("strict isolation: orgUsage update never touches tenant caches and vice-versa")
    void orgEvictionDoesNotLeakToTenantCacheAndViceVersa() {
        String orgId = "org-iso";
        TenantStorageQuota tq = new TenantStorageQuota(TENANT_A, 1_000_000L);
        OrganizationStorageQuota oq = new OrganizationStorageQuota(orgId, 1_000_000L);
        when(quotaRepository.findByTenantId(TENANT_A)).thenReturn(Optional.of(tq));
        when(orgQuotaRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(oq));
        when(orgQuotaRepository.save(oq)).thenReturn(oq);
        when(quotaRepository.save(tq)).thenReturn(tq);
        when(storageRepository.calculateOrganizationUsage(orgId)).thenReturn(50L);
        when(breakdownService.getTotalUsage(TENANT_A)).thenReturn(50L);

        // Warm row caches. Status decisions are intentionally uncached because
        // the payload size is part of the decision.
        quotaService.getQuota(TENANT_A);
        quotaService.checkQuota(TENANT_A, 0L);
        quotaService.getOrganizationQuota(orgId);
        quotaService.checkOrganizationQuota(orgId, 0L);

        // Update org - tenant caches must stay warm.
        quotaService.updateOrganizationUsage(orgId);

        assertThat(tenantQuotaCache.get(TENANT_A))
                .as("tenant cache MUST stay warm - org eviction is scope-isolated")
                .isNotNull();
        assertThat(quotaStatusCache.get(TENANT_A))
                .as("tenant quota-status is intentionally uncached")
                .isNull();
        assertThat(orgQuotaCache.get(orgId)).isNull();
        assertThat(orgQuotaStatusCache.get(orgId)).isNull();

        // Symmetric: update tenant - org caches must stay warm (warm them first).
        quotaService.getOrganizationQuota(orgId);
        quotaService.checkOrganizationQuota(orgId, 0L);

        quotaService.updateUsage(TENANT_A);

        assertThat(orgQuotaCache.get(orgId))
                .as("org cache MUST stay warm - tenant eviction is scope-isolated")
                .isNotNull();
        assertThat(orgQuotaStatusCache.get(orgId))
                .as("org quota-status is intentionally uncached")
                .isNull();
    }
}
