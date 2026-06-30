package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V366 (ADR-0010) real-Postgres e2e of the per-workspace usage READ path. Boots
 * the auth-service context against a real PostgreSQL (Testcontainers) and drives
 * the actual {@link CreditService} usage reads through real JPA + real SQL - no
 * mocks - to prove the {@code (:orgId IS NULL OR e.organizationId = :orgId)} JPQL
 * actually FILTERS rows (the one gap a mocked-repo unit test cannot cover).
 *
 * <p>Ledger rows are seeded directly via the repository with explicit
 * {@code organization_id} values (the WRITE-path stamping from the active
 * workspace scope is covered by the {@code CreditServiceTest} ArgumentCaptor unit
 * tests; here we also confirm the column round-trips to Postgres). The reads then
 * partition:
 * <ul>
 *   <li>a per-workspace filter returns ONLY that workspace's rows;</li>
 *   <li>a null filter ("All workspaces") returns every row, including the
 *       unattributed (NULL-org) legacy row.</li>
 * </ul>
 *
 * <p>Cloud/metered context (the edition guard rejects {@code credit.unlimited=true}
 * under {@code app.edition=cloud}). The reads don't need a subscription - balance
 * is not asserted; only the consumption breakdown / history, which read the seeded
 * rows. Schema is generated from the entities ({@code ddl-auto=create-drop}), so it
 * includes the new V366 {@code organization_id} column.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
@DisplayName("V366 per-workspace usage ledger - real beans on real Postgres")
class WorkspaceUsageLedgerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("apimarketplace")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("promo-it-init.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=auth");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.properties.hibernate.default_schema", () -> "auth");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired private CreditService creditService;
    @Autowired private CreditLedgerRepository ledgerRepository;

    private static final Long USER = 7001L;
    private static final String ORG_A = "00000000-0000-4000-8000-00000000000a";
    private static final String ORG_B = "00000000-0000-4000-8000-00000000000b";

    private void seedConsumption(String orgId, String sourceId) {
        CreditLedgerEntry e = new CreditLedgerEntry();
        e.setUserId(USER);
        e.setExecutorUserId(USER);
        e.setOrganizationId(orgId);            // NULL = unattributed (legacy/system)
        e.setAmount(new BigDecimal("-1.0000")); // a consumption (amount < 0)
        e.setBalanceAfter(BigDecimal.ZERO);
        e.setSourceType("WORKFLOW_NODE");
        e.setSourceId(sourceId);
        ledgerRepository.save(e);
    }

    @BeforeEach
    void seed() {
        ledgerRepository.deleteAll();
        // 2 in workspace A, 1 in workspace B, 1 with no workspace (legacy/system).
        seedConsumption(ORG_A, "it-a-1");
        seedConsumption(ORG_A, "it-a-2");
        seedConsumption(ORG_B, "it-b-1");
        seedConsumption(null, "it-legacy-1");
    }

    @Test
    @DisplayName("the organization_id column round-trips to Postgres (NULL preserved)")
    void organizationIdRoundTrips() {
        Map<String, String> orgBySource = ledgerRepository.findAll().stream()
                .collect(Collectors.toMap(CreditLedgerEntry::getSourceId, e -> String.valueOf(e.getOrganizationId())));

        assertThat(orgBySource).containsEntry("it-a-1", ORG_A);
        assertThat(orgBySource).containsEntry("it-b-1", ORG_B);
        assertThat(orgBySource).containsEntry("it-legacy-1", "null"); // organization_id IS NULL
    }

    @Test
    @DisplayName("usage summary FILTERS by workspace; null filter aggregates ALL incl. legacy")
    void summaryFiltersByWorkspace() {
        assertThat(workflowNodeCount(creditService.getUsageSummary(USER, ORG_A))).isEqualTo(2);
        assertThat(workflowNodeCount(creditService.getUsageSummary(USER, ORG_B))).isEqualTo(1);
        // null filter ("All workspaces") = every workspace + the unattributed legacy row.
        assertThat(workflowNodeCount(creditService.getUsageSummary(USER, null))).isEqualTo(4);
    }

    @Test
    @DisplayName("paged history FILTERS by workspace; null filter returns all")
    void historyFiltersByWorkspace() {
        assertThat(creditService.getUsageHistory(USER, ORG_A, PageRequest.of(0, 50)).getTotalElements()).isEqualTo(2);
        assertThat(creditService.getUsageHistory(USER, ORG_B, PageRequest.of(0, 50)).getTotalElements()).isEqualTo(1);
        assertThat(creditService.getUsageHistory(USER, null, PageRequest.of(0, 50)).getTotalElements()).isEqualTo(4);
    }

    @SuppressWarnings("unchecked")
    private long workflowNodeCount(Map<String, Object> summary) {
        Map<String, Map<String, Object>> byType = (Map<String, Map<String, Object>>) summary.get("breakdownByType");
        Map<String, Object> wf = byType == null ? null : byType.get("WORKFLOW_NODE");
        return wf == null ? 0 : ((Number) wf.get("count")).longValue();
    }
}
