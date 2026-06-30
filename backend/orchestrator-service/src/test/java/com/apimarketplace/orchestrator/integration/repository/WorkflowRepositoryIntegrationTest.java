package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WorkflowRepository}.
 * Tests CRUD operations and custom query methods against H2 in-memory database.
 */
@DataJpaIntegrationTest
class WorkflowRepositoryIntegrationTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";

    private WorkflowEntity createWorkflow(String tenantId, String name, String createdBy) {
        WorkflowEntity workflow = new WorkflowEntity(tenantId, name, createdBy);
        workflow.setId(UUID.randomUUID());
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        // V263 OrgScopedEntity NOT NULL - stamp organizationId before any persist().
        // @DataJpaTest has no request-context binding (no TenantResolver scope), so
        // the listener cannot auto-fill from header. Pattern A: explicit stamp.
        // Reuses tenantId as the org id for test isolation parity.
        workflow.setOrganizationId(tenantId);
        return workflow;
    }

    private WorkflowEntity persistWorkflow(String tenantId, String name, String createdBy) {
        WorkflowEntity workflow = createWorkflow(tenantId, name, createdBy);
        entityManager.persist(workflow);
        entityManager.flush();
        return workflow;
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve workflow by ID")
        void shouldSaveAndRetrieveById() {
            WorkflowEntity workflow = createWorkflow(TENANT_A, "Test Workflow", USER_A);
            workflow.setDescription("A test workflow");

            WorkflowEntity saved = workflowRepository.save(workflow);
            entityManager.flush();
            entityManager.clear();

            Optional<WorkflowEntity> found = workflowRepository.findById(saved.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Test Workflow");
            assertThat(found.get().getDescription()).isEqualTo("A test workflow");
            assertThat(found.get().getTenantId()).isEqualTo(TENANT_A);
            assertThat(found.get().getCreatedBy()).isEqualTo(USER_A);
            assertThat(found.get().getIsActive()).isTrue();
        }

        @Test
        @DisplayName("should update workflow fields")
        void shouldUpdateWorkflowFields() {
            WorkflowEntity workflow = persistWorkflow(TENANT_A, "Original Name", USER_A);
            entityManager.clear();

            WorkflowEntity toUpdate = workflowRepository.findById(workflow.getId()).orElseThrow();
            toUpdate.setName("Updated Name");
            toUpdate.setDescription("Updated description");
            toUpdate.setStatus(WorkflowStatus.DRAFT);
            workflowRepository.save(toUpdate);
            entityManager.flush();
            entityManager.clear();

            WorkflowEntity updated = workflowRepository.findById(workflow.getId()).orElseThrow();
            assertThat(updated.getName()).isEqualTo("Updated Name");
            assertThat(updated.getDescription()).isEqualTo("Updated description");
            assertThat(updated.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        }

        @Test
        @DisplayName("should delete workflow")
        void shouldDeleteWorkflow() {
            WorkflowEntity workflow = persistWorkflow(TENANT_A, "To Delete", USER_A);
            UUID id = workflow.getId();
            entityManager.clear();

            workflowRepository.deleteById(id);
            entityManager.flush();
            entityManager.clear();

            assertThat(workflowRepository.findById(id)).isEmpty();
        }

        @Test
        @DisplayName("should return empty optional for non-existent ID")
        void shouldReturnEmptyForNonExistentId() {
            Optional<WorkflowEntity> result = workflowRepository.findById(UUID.randomUUID());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should persist JSON fields (plan, tags, metadata)")
        void shouldPersistJsonFields() {
            WorkflowEntity workflow = createWorkflow(TENANT_A, "JSON Workflow", USER_A);
            workflow.setPlan(Map.of("triggers", List.of(), "steps", List.of()));
            workflow.setTags(List.of("tag1", "tag2", "production"));
            workflow.setMetadata(Map.of("color", "blue", "priority", 1));
            workflow.setDataInputs(Map.of("input1", "value1"));

            workflowRepository.save(workflow);
            entityManager.flush();
            entityManager.clear();

            WorkflowEntity found = workflowRepository.findById(workflow.getId()).orElseThrow();
            assertThat(found.getPlan()).containsKey("triggers");
            assertThat(found.getTags()).containsExactly("tag1", "tag2", "production");
            assertThat(found.getMetadata()).containsEntry("color", "blue");
            assertThat(found.getDataInputs()).containsEntry("input1", "value1");
        }
    }

    @Nested
    @DisplayName("findByTenantId queries")
    class TenantQueries {

        @Test
        @DisplayName("should find workflows by tenant ID")
        void shouldFindByTenantId() {
            persistWorkflow(TENANT_A, "Workflow A1", USER_A);
            persistWorkflow(TENANT_A, "Workflow A2", USER_A);
            persistWorkflow(TENANT_B, "Workflow B1", USER_B);
            entityManager.clear();

            List<WorkflowEntity> tenantAWorkflows = workflowRepository.findByTenantId(TENANT_A);
            List<WorkflowEntity> tenantBWorkflows = workflowRepository.findByTenantId(TENANT_B);

            assertThat(tenantAWorkflows).hasSize(2);
            assertThat(tenantBWorkflows).hasSize(1);
        }

        @Test
        @DisplayName("should return empty list for unknown tenant")
        void shouldReturnEmptyForUnknownTenant() {
            persistWorkflow(TENANT_A, "Workflow A1", USER_A);
            entityManager.clear();

            List<WorkflowEntity> result = workflowRepository.findByTenantId("unknown-tenant");
            assertThat(result).isEmpty();
        }

    }

    @Nested
    @DisplayName("Count queries")
    class CountQueries {

        @Test
        @DisplayName("should count workflows by tenant")
        void shouldCountByTenantId() {
            persistWorkflow(TENANT_A, "W1", USER_A);
            persistWorkflow(TENANT_A, "W2", USER_A);
            persistWorkflow(TENANT_B, "W3", USER_B);
            entityManager.clear();

            assertThat(workflowRepository.countByTenantId(TENANT_A)).isEqualTo(2);
            assertThat(workflowRepository.countByTenantId(TENANT_B)).isEqualTo(1);
            assertThat(workflowRepository.countByTenantId("nonexistent")).isZero();
        }
    }

    @Nested
    @DisplayName("Webhook and schedule queries")
    class WebhookAndScheduleQueries {

        @Test
        @DisplayName("should find workflows by webhook token")
        void shouldHandleWebhookToken() {
            WorkflowEntity workflow = createWorkflow(TENANT_A, "Webhook Workflow", USER_A);
            workflow.setWebhookToken("unique-token-123");
            workflow.setWebhookCreatedAt(Instant.now());
            entityManager.persist(workflow);
            entityManager.flush();
            entityManager.clear();

            WorkflowEntity found = workflowRepository.findById(workflow.getId()).orElseThrow();
            assertThat(found.getWebhookToken()).isEqualTo("unique-token-123");
            assertThat(found.getWebhookCreatedAt()).isNotNull();
        }

    }

    @Nested
    @DisplayName("Publication/Marketplace queries")
    class PublicationQueries {

        // 2026-05-21 user-reported Applications-page-404 fix: regression
        // coverage for findAcquiredByTenantId + findAcquiredByOrganizationId
        // ships as a controller-layer Mockito test (see
        // InternalPublicationSupportControllerAcquiredTest.java) since this
        // class has a pre-existing systemic issue with V263 fail-loud on
        // OrgScopedEntity persists in @DataJpaIntegrationTest setup. The
        // controller test pins the org/tenant fallback contract without
        // exercising the JPA listener.

        @Test
        @DisplayName("should check if tenant already acquired a publication")
        void shouldCheckExistsByTenantAndPublication() {
            UUID pubId = UUID.randomUUID();
            WorkflowEntity acquired = createWorkflow(TENANT_A, "Acquired", USER_A);
            acquired.setSourcePublicationId(pubId);
            acquired.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
            acquired.setAcquiredAt(Instant.now());
            entityManager.persist(acquired);
            entityManager.flush();
            entityManager.clear();

            assertThat(workflowRepository.existsByTenantIdAndSourcePublicationId(TENANT_A, pubId)).isTrue();
            assertThat(workflowRepository.existsByTenantIdAndSourcePublicationId(TENANT_B, pubId)).isFalse();
            assertThat(workflowRepository.existsByTenantIdAndSourcePublicationId(TENANT_A, UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("typed exists ignores sub-workflow children so an orphan clone cannot block re-acquisition")
        void typedExistsIgnoresWorkflowChildren() {
            UUID pubId = UUID.randomUUID();
            WorkflowEntity child = createWorkflow(TENANT_A, "Orphan child clone", USER_A);
            child.setSourcePublicationId(pubId);
            child.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
            entityManager.persist(child);
            entityManager.flush();
            entityManager.clear();

            // The untyped probe still sees the child (cleanup paths rely on it)...
            assertThat(workflowRepository.existsByTenantIdAndSourcePublicationId(TENANT_A, pubId)).isTrue();
            // ...but "already acquired" must only count the APPLICATION root.
            assertThat(workflowRepository.existsByTenantIdAndSourcePublicationIdAndWorkflowType(
                    TENANT_A, pubId, WorkflowEntity.WorkflowType.APPLICATION)).isFalse();
        }
    }


    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("should enforce tenant isolation on all tenant-scoped queries")
        void shouldEnforceTenantIsolation() {
            persistWorkflow(TENANT_A, "Tenant A Workflow", USER_A);
            persistWorkflow(TENANT_B, "Tenant B Workflow", USER_B);
            entityManager.clear();

            // Tenant A should only see their workflows
            List<WorkflowEntity> tenantAResult = workflowRepository.findByTenantId(TENANT_A);
            assertThat(tenantAResult).allMatch(w -> w.getTenantId().equals(TENANT_A));

            // Tenant B should only see their workflows
            List<WorkflowEntity> tenantBResult = workflowRepository.findByTenantId(TENANT_B);
            assertThat(tenantBResult).allMatch(w -> w.getTenantId().equals(TENANT_B));
        }
    }

    @Nested
    @DisplayName("Retention and lifecycle")
    class RetentionQueries {

        @Test
        @DisplayName("should persist retention days")
        void shouldPersistRetentionDays() {
            WorkflowEntity workflow = createWorkflow(TENANT_A, "With Retention", USER_A);
            workflow.setRetentionDays(90);
            entityManager.persist(workflow);
            entityManager.flush();
            entityManager.clear();

            WorkflowEntity found = workflowRepository.findById(workflow.getId()).orElseThrow();
            assertThat(found.getRetentionDays()).isEqualTo(90);
        }

        @Test
        @DisplayName("should have default retention of 30 days")
        void shouldHaveDefaultRetention() {
            WorkflowEntity workflow = persistWorkflow(TENANT_A, "Default Retention", USER_A);
            entityManager.clear();

            WorkflowEntity found = workflowRepository.findById(workflow.getId()).orElseThrow();
            assertThat(found.getRetentionDays()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("Debug utility queries")
    class DebugQueries {

        @Test
        @DisplayName("should find distinct tenant IDs")
        void shouldFindDistinctTenantIds() {
            persistWorkflow(TENANT_A, "W1", USER_A);
            persistWorkflow(TENANT_A, "W2", USER_A);
            persistWorkflow(TENANT_B, "W3", USER_B);
            entityManager.clear();

            List<String> tenantIds = workflowRepository.findAllDistinctTenantIds();

            assertThat(tenantIds).hasSize(2);
            assertThat(tenantIds).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        }
    }

    @Nested
    @DisplayName("findOrganizationIdById projection (PR-1.d)")
    class FindOrganizationIdById {

        @Test
        @DisplayName("returns the organization_id for an org-owned workflow")
        void returnsOrgIdForOrgWorkflow() {
            WorkflowEntity workflow = createWorkflow(TENANT_A, "Org Workflow", USER_A);
            workflow.setOrganizationId("org-42");
            entityManager.persist(workflow);
            entityManager.flush();
            entityManager.clear();

            Optional<String> result = workflowRepository.findOrganizationIdById(workflow.getId());

            assertThat(result).contains("org-42");
        }

        @Test
        @DisplayName("returns stamped organization_id for a personal workflow (post-V263 NOT NULL)")
        void returnsStampedOrgIdForPersonalWorkflow() {
            // Post-V263: organization_id is NOT NULL, so a "personal workflow" with
            // null org_id is unreachable in src/main (V261 backfilled, V263 enforced).
            // The projection now returns the stamped value (the user's personal org
            // id, which the test setup stamps as TENANT_A). The legacy assertion
            // "isEmpty()" reflected pre-V261 nullable behavior - no longer valid.
            WorkflowEntity workflow = persistWorkflow(TENANT_A, "Personal Workflow", USER_A);
            entityManager.clear();

            Optional<String> result = workflowRepository.findOrganizationIdById(workflow.getId());

            assertThat(result).contains(TENANT_A);
        }

        @Test
        @DisplayName("returns empty for a non-existent workflow id")
        void returnsEmptyForUnknownId() {
            UUID unknown = UUID.randomUUID();

            Optional<String> result = workflowRepository.findOrganizationIdById(unknown);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("updateLastExecutedAt - bulk update bypasses WorkflowRunEntity lazy proxy")
    class UpdateLastExecutedAt {

        @Test
        @DisplayName("stamps lastExecutedAt by id without loading the entity (regression: 'Could not initialize proxy - no session' on schedule-spread thread, intro 2a083618b7 2026-05-05, 13 silent failures/day in prod 2026-05)")
        void stampsLastExecutedAtById() {
            WorkflowEntity workflow = persistWorkflow(TENANT_A, "Scheduled Workflow", USER_A);
            UUID workflowId = workflow.getId();
            Instant before = workflow.getLastExecutedAt();
            assertThat(before).as("freshly persisted workflow has no execution stamp yet").isNull();

            // Clear so the next read MUST come from DB - proves the bulk UPDATE landed.
            entityManager.clear();

            Instant stamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            int updated = workflowRepository.updateLastExecutedAt(workflowId, stamp);

            assertThat(updated).as("one row should be updated").isEqualTo(1);

            WorkflowEntity reloaded = workflowRepository.findById(workflowId).orElseThrow();
            assertThat(reloaded.getLastExecutedAt())
                    .as("lastExecutedAt persisted to DB via bulk UPDATE, not via dirty-checking on a managed entity")
                    .isEqualTo(stamp);
        }

        @Test
        @DisplayName("returns 0 when the workflow id does not exist - caller must not assume success")
        void returnsZeroForUnknownId() {
            int updated = workflowRepository.updateLastExecutedAt(UUID.randomUUID(), Instant.now());
            assertThat(updated).isEqualTo(0);
        }

        @Test
        @DisplayName("subsequent updates overwrite the previous stamp (idempotent for the on-every-fire ReusableTriggerService caller)")
        void subsequentUpdatesOverwrite() {
            WorkflowEntity workflow = persistWorkflow(TENANT_A, "Re-fired Workflow", USER_A);
            UUID workflowId = workflow.getId();
            entityManager.clear();

            Instant first = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
            Instant second = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            workflowRepository.updateLastExecutedAt(workflowId, first);
            workflowRepository.updateLastExecutedAt(workflowId, second);

            WorkflowEntity reloaded = workflowRepository.findById(workflowId).orElseThrow();
            assertThat(reloaded.getLastExecutedAt()).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("findPinnedVersionScopeRows - [id, pinnedVersion, tenantId, organizationId] projection")
    class PinnedVersionScopeRows {

        @Test
        @DisplayName("projects id + pinnedVersion + the scope columns for each requested workflow")
        void projectsScopeColumns() {
            // The query is intentionally UNSCOPED; the strict-workspace filter runs in
            // ApplicationRunVersionBatchService via ScopeGuard.isInStrictScope on these scope columns.
            WorkflowEntity a = createWorkflow(TENANT_A, "App A", USER_A); // organizationId stamped = TENANT_A
            a.setPinnedVersion(5);
            entityManager.persist(a);
            WorkflowEntity b = createWorkflow(TENANT_B, "App B", USER_B);
            b.setPinnedVersion(9);
            entityManager.persist(b);
            entityManager.flush();
            entityManager.clear();

            List<Object[]> rows = workflowRepository.findPinnedVersionScopeRows(List.of(a.getId(), b.getId()));

            assertThat(rows).hasSize(2);
            Object[] rowA = rows.stream().filter(r -> r[0].equals(a.getId())).findFirst().orElseThrow();
            assertThat(rowA[1]).isEqualTo(5);        // pinnedVersion
            assertThat(rowA[2]).isEqualTo(TENANT_A); // tenantId
            assertThat(rowA[3]).isEqualTo(TENANT_A); // organizationId
        }

        @Test
        @DisplayName("an unpinned workflow projects a null pinnedVersion (Inactive badge)")
        void unpinnedProjectsNull() {
            WorkflowEntity a = createWorkflow(TENANT_A, "Unpinned", USER_A); // pinnedVersion left null
            entityManager.persist(a);
            entityManager.flush();
            entityManager.clear();

            List<Object[]> rows = workflowRepository.findPinnedVersionScopeRows(List.of(a.getId()));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)[0]).isEqualTo(a.getId());
            assertThat(rows.get(0)[1]).isNull();
        }
    }
}
