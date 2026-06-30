package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
