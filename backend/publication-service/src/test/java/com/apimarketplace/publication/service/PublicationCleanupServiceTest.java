package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PublicationCleanupService}.
 */
@ExtendWith(MockitoExtension.class)
class PublicationCleanupServiceTest {

    @Mock
    private EntityManager em;

    @Mock
    private Query selectQuery;

    @Mock
    private Query updateQuery;

    @Mock
    private OrchestratorInternalClient orchestratorClient;

    private PublicationCleanupService service;

    @BeforeEach
    void setUp() {
        service = new PublicationCleanupService(orchestratorClient);
        ReflectionTestUtils.setField(service, "em", em);
    }

    @Nested
    @DisplayName("Annotation verification")
    class AnnotationTests {

        @Test
        @DisplayName("cleanupStalePublications has @Scheduled with daily 3 AM cron")
        void scheduledAnnotationPresent() throws NoSuchMethodException {
            Method method = PublicationCleanupService.class
                    .getMethod("cleanupStalePublications");

            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 3 * * *");
        }

        @Test
        @DisplayName("cleanupStalePublications has @Transactional")
        void transactionalAnnotationPresent() throws NoSuchMethodException {
            Method method = PublicationCleanupService.class
                    .getMethod("cleanupStalePublications");

            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).isNotNull();
        }
    }

    @Nested
    @DisplayName("deactivateOrphanedPublications")
    class DeactivateOrphanedTests {

        @Test
        @DisplayName("Returns count of deactivated orphaned publications")
        void deactivatesOrphans() {
            UUID wf1 = UUID.randomUUID();
            UUID wf2 = UUID.randomUUID();
            UUID wf3 = UUID.randomUUID();

            // Step 1: SELECT returns 3 active workflow IDs
            when(em.createNativeQuery(contains("SELECT")))
                    .thenReturn(selectQuery);
            when(selectQuery.getResultList())
                    .thenReturn(List.of(wf1, wf2, wf3));

            // Step 2: orchestrator says only wf1 exists → wf2 and wf3 are orphans
            when(orchestratorClient.getExistingWorkflowIds(anySet()))
                    .thenReturn(Set.of(wf1));

            // Step 3: UPDATE deactivates 2 orphans
            when(em.createNativeQuery(contains("UPDATE")))
                    .thenReturn(updateQuery);
            when(updateQuery.setParameter(eq("ids"), anySet()))
                    .thenReturn(updateQuery);
            when(updateQuery.executeUpdate()).thenReturn(2);

            int result = service.deactivateOrphanedPublications();

            assertThat(result).isEqualTo(2);
            verify(orchestratorClient).getExistingWorkflowIds(anySet());
            verify(updateQuery).executeUpdate();
        }

        @Test
        @DisplayName("Returns zero when no active publications")
        void noActivePublications() {
            when(em.createNativeQuery(contains("SELECT")))
                    .thenReturn(selectQuery);
            when(selectQuery.getResultList())
                    .thenReturn(List.of());

            int result = service.deactivateOrphanedPublications();

            assertThat(result).isEqualTo(0);
            verifyNoInteractions(orchestratorClient);
        }

        @Test
        @DisplayName("Returns zero when all workflows still exist")
        void allWorkflowsExist() {
            UUID wf1 = UUID.randomUUID();
            UUID wf2 = UUID.randomUUID();

            when(em.createNativeQuery(contains("SELECT")))
                    .thenReturn(selectQuery);
            when(selectQuery.getResultList())
                    .thenReturn(List.of(wf1, wf2));

            when(orchestratorClient.getExistingWorkflowIds(anySet()))
                    .thenReturn(Set.of(wf1, wf2));

            int result = service.deactivateOrphanedPublications();

            assertThat(result).isEqualTo(0);
            verify(orchestratorClient).getExistingWorkflowIds(anySet());
        }
    }

    @Nested
    @DisplayName("cleanupStalePublications (integration)")
    class CleanupIntegrationTests {

        @Test
        @DisplayName("Calls deactivate orphaned publications")
        void callsDeactivate() {
            UUID wf1 = UUID.randomUUID();

            when(em.createNativeQuery(contains("SELECT")))
                    .thenReturn(selectQuery);
            when(selectQuery.getResultList())
                    .thenReturn(List.of(wf1));
            when(orchestratorClient.getExistingWorkflowIds(anySet()))
                    .thenReturn(Set.of()); // wf1 is orphaned
            when(em.createNativeQuery(contains("UPDATE")))
                    .thenReturn(updateQuery);
            when(updateQuery.setParameter(eq("ids"), anySet()))
                    .thenReturn(updateQuery);
            when(updateQuery.executeUpdate()).thenReturn(1);

            service.cleanupStalePublications();

            verify(updateQuery).executeUpdate();
        }

        @Test
        @DisplayName("Handles zero results gracefully")
        void handlesZeroResults() {
            when(em.createNativeQuery(contains("SELECT")))
                    .thenReturn(selectQuery);
            when(selectQuery.getResultList())
                    .thenReturn(List.of());

            service.cleanupStalePublications();

            verifyNoInteractions(orchestratorClient);
        }
    }

    @Nested
    @DisplayName("Null workflow_id - regression for the nightly cleanup NPE")
    class NullWorkflowIdTests {

        /**
         * Production 2026-09-19 03:00, and every night since the first ACTIVE agent publication:
         * V38 made workflow_id nullable because "AGENT publications have no workflow", that null
         * reached the candidate set, and UUID::toString inside getExistingWorkflowIds threw an NPE
         * that escaped that method's try block. The run aborted into the outer catch and all 131
         * WORKFLOW publications went unchecked. The candidate query must select WORKFLOW rows only.
         */
        @Test
        @DisplayName("The candidate query selects WORKFLOW publications only, never AGENT ones")
        void selectRestrictsToWorkflowPublications() {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            when(em.createNativeQuery(contains("SELECT"))).thenReturn(selectQuery);
            when(selectQuery.getResultList()).thenReturn(List.of());

            service.cleanupStalePublications();

            verify(em).createNativeQuery(sql.capture());
            assertThat(sql.getValue())
                    .as("'does the referenced workflow still exist' is meaningless for an AGENT "
                            + "publication, whose subject is agent_config_id")
                    .contains("publication_type = 'WORKFLOW'");
            assertThat(sql.getValue())
                    .as("data-hygiene guard for a malformed WORKFLOW row")
                    .contains("workflow_id IS NOT NULL");
        }

        /**
         * Second gate, exercised independently of the SQL one: a null that reaches the result
         * list anyway (a caller other than the query, a schema change) must not abort the run.
         *
         * <p>Asserting "no UPDATE happened" would be VACUOUS here - aborting produces no UPDATE
         * just as surely as succeeding does, and the outer catch swallows the difference. So the
         * assertions below prove the run actually REACHED its decision: the client was called
         * with the null already stripped, and the error branch never fired.
         */
        @Test
        @DisplayName("A null in the result list does not abort the run before its decision")
        void nullInResultListDoesNotAbortTheRun() {
            UUID live = UUID.randomUUID();
            List<UUID> withNull = new ArrayList<>();
            withNull.add(live);
            withNull.add(null);

            ch.qos.logback.classic.Logger serviceLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(PublicationCleanupService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);

            try {
                when(em.createNativeQuery(contains("SELECT"))).thenReturn(selectQuery);
                when(selectQuery.getResultList()).thenReturn(withNull);
                when(orchestratorClient.getExistingWorkflowIds(anySet())).thenReturn(Set.of(live));

                service.cleanupStalePublications();

                // 1. The decision was reached: the client saw the real id and no null. Passing a
                //    null on would blow up on Set.of(...).contains(null) at the orphan test.
                ArgumentCaptor<Set<UUID>> sent = ArgumentCaptor.forClass(Set.class);
                verify(orchestratorClient).getExistingWorkflowIds(sent.capture());
                assertThat(sent.getValue()).containsExactly(live).doesNotContainNull();

                // 2. The run completed instead of falling into the catch-and-log.
                assertThat(appender.list)
                        .as("an aborted run logs its exception here - that is what the bug looked like")
                        .noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);

                // 3. And the outcome is right: the only real id still exists, so nothing is deactivated.
                verify(em, never()).createNativeQuery(contains("UPDATE"));
            } finally {
                serviceLogger.detachAppender(appender);
                appender.stop();
            }
        }
    }
}
