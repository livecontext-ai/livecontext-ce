package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.FlagFlipAuditEntity;
import com.apimarketplace.orchestrator.repository.FlagFlipAuditRepository;
import com.apimarketplace.orchestrator.services.flag.FlagFlipAuditWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the {@link FlagFlipAuditWriter}'s
 * {@code @Transactional(propagation = MANDATORY)} write contract (P2.1.7).
 *
 * <p>The unit test in {@code FlagFlipAuditWriterTest} verifies the annotation
 * via reflection. This test verifies the actual runtime semantics with a real
 * Spring transaction manager + AOP advice woven onto the writer:
 * <ol>
 *   <li>Calling {@code recordFlip} OUTSIDE a transaction throws
 *       {@link IllegalTransactionStateException} - the writer refuses to
 *       run without a caller-supplied TX, ensuring the audit row CANNOT be
 *       written without the corresponding flag mutation.</li>
 *   <li>Calling {@code recordFlip} INSIDE a transaction that subsequently throws
 *       rolls back BOTH the audit insert AND any flag mutation that would
 *       follow - atomic same-TX semantics, no orphaned audit rows.</li>
 *   <li>A successful TX commits the audit row durably - the success path works.</li>
 * </ol>
 *
 * <p>Together these three contracts encode the rev12 §7.6 invariant
 * "no flip without audit row, no audit row without flip".
 *
 * <p>Uses {@code @SpringBootTest} with a minimal nested {@link TestApp}
 * configuration so transaction-AOP advice is actually woven onto
 * {@code FlagFlipAuditWriter} (the {@code @DataJpaTest} slice does not apply
 * AOP to {@code @Import}-loaded service beans). The nested app scans only
 * the `flag` service package + repository + entity to keep startup fast.
 */
@SpringBootTest(classes = FlagFlipAuditTransactionalTest.TestApp.class)
@ActiveProfiles("integration-test")
@DirtiesContext  // ensure a fresh schema per class to avoid identity-column carry-over
@DisplayName("FlagFlipAuditWriter - @Transactional(MANDATORY) integration contract (P2.1.7)")
class FlagFlipAuditTransactionalTest {

    /**
     * Minimal Spring Boot app for this integration test: scans the entity package,
     * the repository package, and the `flag` service package so AOP advice is
     * woven onto {@link FlagFlipAuditWriter#recordFlip}. No other orchestrator
     * beans are loaded - keeps the context fast and isolated.
     */
    // @Configuration + @EnableAutoConfiguration instead of @SpringBootApplication
    // to avoid collision with sibling @DataJpaTest auto-detection in this package
    // (3 nested @SpringBootApplications would otherwise produce "multiple
    // @SpringBootConfiguration" errors when running the whole package). The
    // outer @SpringBootTest(classes = TestApp.class) wires this explicitly.
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @EntityScan(basePackageClasses = FlagFlipAuditEntity.class)
    @EnableJpaRepositories(basePackageClasses = FlagFlipAuditRepository.class)
    @ComponentScan(basePackageClasses = FlagFlipAuditWriter.class)
    static class TestApp {
    }

    @Autowired private FlagFlipAuditWriter writer;
    @Autowired private FlagFlipAuditRepository repository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        txTemplate = new TransactionTemplate(txManager);
    }

    @Test
    @DisplayName("recordFlip OUTSIDE a TX throws IllegalTransactionStateException - refuses to run without caller TX")
    void rejectsCallOutsideTransaction() {
        // No active transaction at the call site - Propagation.MANDATORY MUST refuse.
        // Without this guarantee, an audit row could be written for a flag mutation
        // that doesn't actually commit, breaking the "no audit without flip" invariant.
        assertThatThrownBy(() -> writer.recordFlip(
                "state-snapshot.elide-running-nodes",
                "tenant-T1",
                "org-test",
                "false",
                "true",
                "ops-user",
                "INC-12345 - staging ramp"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("recordFlip INSIDE a successful TX commits the audit row")
    void commitsInsideSuccessfulTransaction() {
        txTemplate.executeWithoutResult(status -> writer.recordFlip(
                "state-snapshot.elide-running-nodes",
                "tenant-T1",
                "org-test",
                "false",
                "true",
                "ops-user",
                "successful flip"));

        List<FlagFlipAuditEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        FlagFlipAuditEntity row = rows.get(0);
        assertThat(row.getFlagName()).isEqualTo("state-snapshot.elide-running-nodes");
        assertThat(row.getTenantId()).isEqualTo("tenant-T1");
        assertThat(row.getOrganizationId()).isEqualTo("org-test");
        assertThat(row.getOldValue()).isEqualTo("false");
        assertThat(row.getNewValue()).isEqualTo("true");
        assertThat(row.getActor()).isEqualTo("ops-user");
        assertThat(row.getReason()).isEqualTo("successful flip");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordFlip rolls back when caller TX rolls back - fail-the-flip-if-write-fails invariant")
    void rollsBackWithCallerTransaction() {
        // Simulate the production caller pattern: open TX, write audit row,
        // then mutate the flag value. If the flag mutation throws, the entire
        // TX rolls back AND the audit row goes with it - preserving "no audit
        // without flip".
        IllegalStateException simulatedFailure = new IllegalStateException("flag mutation failed");

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            writer.recordFlip(
                    "state-snapshot.elide-running-nodes",
                    "tenant-T1",
                    "org-test",
                    "false",
                    "true",
                    "ops-user",
                    "will be rolled back");
            // The caller's flag mutation fails AFTER the audit insert.
            throw simulatedFailure;
        })).isSameAs(simulatedFailure);

        // Critical: the audit row MUST NOT be persisted because the surrounding
        // TX rolled back. This is the rev12 §7.6 fail-the-flip-if-write-fails
        // contract running in reverse - caller failure rolls back audit too.
        List<FlagFlipAuditEntity> rows = repository.findAll();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("Multiple recordFlip calls in same TX all roll back together")
    void multipleFlipsRollBackTogether() {
        IllegalStateException simulatedFailure = new IllegalStateException("late failure");

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            writer.recordFlip("flag-A", "tenant-T1", "org-test", "false", "true", "ops", "first");
            writer.recordFlip("flag-B", "tenant-T1", "org-test", "true", "false", "ops", "second");
            writer.recordFlip("flag-C", "tenant-T2", "org-test", "1", "2", "ops", "third");
            throw simulatedFailure;
        })).isSameAs(simulatedFailure);

        // All three audit rows go with the rolled-back TX.
        assertThat(repository.findAll()).isEmpty();
    }
}
