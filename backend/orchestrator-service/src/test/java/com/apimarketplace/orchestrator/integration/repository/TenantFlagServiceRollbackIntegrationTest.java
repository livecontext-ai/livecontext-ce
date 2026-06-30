package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.FlagFlipAuditEntity;
import com.apimarketplace.orchestrator.domain.TenantFlagEntity;
import com.apimarketplace.orchestrator.repository.FlagFlipAuditRepository;
import com.apimarketplace.orchestrator.repository.TenantFlagRepository;
import com.apimarketplace.orchestrator.services.flag.FlagFlipAuditWriter;
import com.apimarketplace.orchestrator.services.flag.TenantFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * Integration test for {@link TenantFlagService#flip} TX rollback contract
 * (audit B round-2 MUST-FIX: pins the cache-vs-DB consistency hazard the
 * unit test cannot exercise).
 *
 * <p>The contract: when {@link FlagFlipAuditWriter#recordFlip} throws inside
 * the {@code flip()} TX, the entire transaction rolls back - the
 * {@code tenant_flags} DB row is NOT persisted, and the in-memory cache is
 * NOT updated. "No flip without audit row, no audit row without flip."
 *
 * <p>This integration test uses a real Spring transaction manager + AOP-woven
 * proxies on {@code TenantFlagService} so {@code @Transactional} actually fires.
 *
 * <p>Pattern matches {@code FlagFlipAuditTransactionalTest} (P2.1.7).
 */
@SpringBootTest(classes = TenantFlagServiceRollbackIntegrationTest.TestApp.class)
@ActiveProfiles("integration-test")
@DirtiesContext
@DisplayName("TenantFlagService - flip() TX rollback contract (P2.3.3 audit-B fix)")
class TenantFlagServiceRollbackIntegrationTest {

    /**
     * Minimal Spring Boot app: scans the entity, repository, and service packages
     * needed for the {@code TenantFlagService} + {@code FlagFlipAuditWriter} graph
     * + JPA. {@code FlagFlipAuditWriter} is mocked via {@code @MockBean} on the
     * test class to inject a controllable failure mode.
     */
    // @Configuration + @EnableAutoConfiguration instead of @SpringBootApplication
    // - see EpochStateRunningElideE2ETest.TestApp for the rationale (multiple
    // nested @SpringBootApplications collide with @DataJpaTest auto-detection).
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @EntityScan(basePackageClasses = {TenantFlagEntity.class, FlagFlipAuditEntity.class})
    @EnableJpaRepositories(basePackageClasses = {TenantFlagRepository.class, FlagFlipAuditRepository.class})
    @ComponentScan(basePackageClasses = TenantFlagService.class,
            // Exclude FlagFlipAuditWriter so @MockBean can stand in for it.
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = FlagFlipAuditWriter.class))
    static class TestApp {
    }

    @Autowired private TenantFlagService service;
    @Autowired private TenantFlagRepository repository;
    @MockBean private FlagFlipAuditWriter auditWriter;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Audit writer throws → tenant_flags DB row IS NOT persisted (TX rolls back) AND cache is NOT updated")
    void auditThrowsRollsBackBothDbAndCache() {
        // Arrange: audit-row write throws inside the @Transactional flip().
        doThrow(new RuntimeException("audit DB down"))
                .when(auditWriter).recordFlip(anyString(), anyString(), anyString(),
                        any(), anyString(), anyString(), anyString());

        // Act + assert: flip propagates the audit exception.
        assertThatThrownBy(() ->
                service.flip("flag.A", "tenant-1", "org-test", true, "ops", "will fail"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit DB down");

        // Critical assertions of the rollback contract:
        // 1. DB row is NOT persisted - rollback unwound the prior repository.save().
        List<TenantFlagEntity> rows = repository.findAll();
        assertThat(rows).isEmpty();

        // 2. Cache is NOT updated - flip() runs in the order:
        //      (a) repository.save(...)         - DB row pending TX commit
        //      (b) auditWriter.recordFlip(...)  - THROWS here in this test
        //      (c) cache.put(...)               - never reached
        //    The throw aborts before step (c), so the cache mutation never
        //    happens. Combined with Spring's @Transactional rollback unwinding
        //    step (a), the result is "no flip without audit row, no audit row
        //    without flip" - the rev12 §7.6 invariant.
        //    Acknowledged hazard (documented in TenantFlagService.flip javadoc):
        //    a rare commit-failure-AFTER-method-return path (after step c
        //    succeeds, then Spring fails to commit) would briefly diverge cache
        //    from DB until the next loadCache. Future hardening:
        //    TransactionSynchronization.afterCommit.
        assertThat(service.isEnabled("flag.A", "tenant-1")).isFalse();
    }

    @Test
    @DisplayName("Successful flip → DB row persisted AND cache updated AND audit recorded")
    void successfulFlipPersistsAll() {
        service.flip("flag.A", "tenant-1", "org-test", true, "ops-user", "INC-123 ramp");

        // DB row durable
        List<TenantFlagEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        TenantFlagEntity row = rows.get(0);
        assertThat(row.getFlagName()).isEqualTo("flag.A");
        assertThat(row.getTenantId()).isEqualTo("tenant-1");
        assertThat(row.getOrganizationId()).isEqualTo("org-test");
        assertThat(row.getValue()).isTrue();
        assertThat(row.getUpdatedBy()).isEqualTo("ops-user");

        // Cache updated
        assertThat(service.isEnabled("flag.A", "tenant-1")).isTrue();

        // Audit was invoked
        org.mockito.Mockito.verify(auditWriter).recordFlip(
                "flag.A", "tenant-1", "org-test", null, "true", "ops-user", "INC-123 ramp");
    }

    @Test
    @DisplayName("Multiple flips in same session - each is its own TX (independent rollback)")
    void multipleFlipsIndependentTransactions() {
        service.flip("flag.A", "tenant-1", "org-test", true, "ops", "first");

        // Now make audit fail on the next call.
        doThrow(new RuntimeException("audit DB down"))
                .when(auditWriter).recordFlip(anyString(), anyString(), anyString(),
                        any(), anyString(), anyString(), anyString());

        assertThatThrownBy(() ->
                service.flip("flag.A", "tenant-2", "org-test", true, "ops", "second fails"))
                .isInstanceOf(RuntimeException.class);

        // First flip's row survives - independent TX boundary.
        List<TenantFlagEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTenantId()).isEqualTo("tenant-1");

        // Cache reflects the same: tenant-1 ON, tenant-2 OFF.
        assertThat(service.isEnabled("flag.A", "tenant-1")).isTrue();
        assertThat(service.isEnabled("flag.A", "tenant-2")).isFalse();
    }
}
