package com.apimarketplace.trigger.integration.repository;

import com.apimarketplace.trigger.repository.TriggerStateAuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TriggerStateAuditLogRepository} -
 * <strong>regression guard</strong> for the V169 boot bug.
 *
 * <p>The original commit shipped with {@code @GeneratedValue + @SequenceGenerator}
 * on the non-PK {@code seq} field, which Hibernate rejects at metadata-binding
 * time with {@code AnnotationException: "Property … is annotated
 * @GeneratedValue but is not part of an identifier"}. The bug surfaced only
 * at trigger-service boot against real Postgres because the unit tests
 * mocked the repository - the entity-mapping layer was never exercised.
 *
 * <p>This test boots the full JPA context (per {@link DataJpaIntegrationTest}'s
 * {@code @DataJpaTest} meta-annotation). The mere act of {@code @Autowire}-ing
 * the repository succeeds only if Hibernate accepted the entity's annotations
 * at metadata binding. If a future commit regresses to {@code @GeneratedValue}
 * on a non-identifier field, the context refresh fails before this method
 * body runs, and the test reports {@code ApplicationContextException} with
 * the {@code AnnotationException} root cause.
 *
 * <p>Per CLAUDE.md "Bug fix → regression test" rule.
 *
 * <p>Note: full persist-and-readback testing of the sequence-backed seq
 * column requires a Postgres sequence DEFAULT that Hibernate's H2 auto-DDL
 * does not reproduce. Such testing is covered by the live E2E pass against
 * real Postgres (V169 applied via Flyway, audit row written with seq=1, …).
 */
@DataJpaIntegrationTest
class TriggerStateAuditLogRepositoryIntegrationTest {

    @Autowired
    private TriggerStateAuditLogRepository auditLogRepository;

    @Test
    @DisplayName("entity mapping accepted by Hibernate at metadata binding (regression: V169 @GeneratedValue boot bug)")
    void entityMappingAcceptedAtBoot() {
        // If a future commit regresses the entity annotations (e.g. re-introduces
        // @GeneratedValue on the non-PK seq field, or adds @SequenceGenerator
        // without @GeneratedValue, or breaks the @Generated/insertable=false
        // pairing), Hibernate's AnnotationBinder fails during context refresh.
        // The @DataJpaTest framework propagates that failure, so this test fails
        // with a clear stack trace pointing at the entity - exactly the diagnostic
        // the live-Postgres boot would have produced, but caught at PR-CI time.
        assertThat(auditLogRepository).isNotNull();
    }
}
