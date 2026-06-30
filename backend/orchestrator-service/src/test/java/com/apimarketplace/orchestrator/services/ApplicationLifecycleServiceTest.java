package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationLifecycleService}.
 *
 * <p>This service centralises the org-scoped acquired-application lookup that
 * the application tool's execute / get / runs / uninstall paths and the
 * internal publication-support endpoint each performed inline. The contract
 * pinned here is the one those call sites relied on: the strict-org
 * {@code APPLICATION} finder is the only repository surface used, the
 * organization id is taken verbatim from the caller (never chosen), and a
 * missing org / publication id short-circuits to empty WITHOUT touching the
 * repository (so a blank org can never widen into a tenant-only leak).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationLifecycleService - org-scoped acquired-clone resolution")
class ApplicationLifecycleServiceTest {

    @Mock private WorkflowRepository workflowRepository;

    private ApplicationLifecycleService service;

    private static final String ORG_ID = "org-abc-123";
    private static final UUID PUB_ID = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ApplicationLifecycleService(workflowRepository);
    }

    @Nested
    @DisplayName("resolveClone - happy path")
    class HappyPath {

        @Test
        @DisplayName("Returns the clone from the strict-org APPLICATION finder, queried by the caller's org")
        void returnsCloneFromStrictOrgFinder() {
            WorkflowEntity clone = mock(WorkflowEntity.class);
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(ORG_ID), eq(PUB_ID), eq(WorkflowEntity.WorkflowType.APPLICATION)))
                    .thenReturn(Optional.of(clone));

            Optional<WorkflowEntity> result = service.resolveClone(ORG_ID, PUB_ID);

            assertThat(result).containsSame(clone);
            // The strict-org APPLICATION finder is the ONLY repository surface
            // touched - never a tenant-only or type-agnostic variant.
            verify(workflowRepository).findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(ORG_ID), eq(PUB_ID), eq(WorkflowEntity.WorkflowType.APPLICATION));
        }

        @Test
        @DisplayName("Empty when the org has no clone for that publication (finder returns empty)")
        void emptyWhenNoCloneForPublication() {
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(ORG_ID), eq(PUB_ID), eq(WorkflowEntity.WorkflowType.APPLICATION)))
                    .thenReturn(Optional.empty());

            assertThat(service.resolveClone(ORG_ID, PUB_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("resolveClone - missing inputs short-circuit before the repository")
    class MissingInputs {

        @Test
        @DisplayName("Null org returns empty and never queries the repository (no tenant-only widening)")
        void nullOrgShortCircuits() {
            assertThat(service.resolveClone(null, PUB_ID)).isEmpty();
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }

        @Test
        @DisplayName("Blank org returns empty and never queries the repository")
        void blankOrgShortCircuits() {
            assertThat(service.resolveClone("   ", PUB_ID)).isEmpty();
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }

        @Test
        @DisplayName("Null publication id returns empty and never queries the repository")
        void nullPublicationShortCircuits() {
            assertThat(service.resolveClone(ORG_ID, null)).isEmpty();
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }
    }
}
