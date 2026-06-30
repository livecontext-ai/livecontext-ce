package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.NotificationReadStateRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V220 regression: pins {@link NotificationService} scope-predicate selection
 * + parameter binding. The pre-V220 read filtered tenant_id only, leaking
 * personal-scope rows into org workspaces and hiding org rows from the
 * matching teammate. V220 added {@code organization_id} and the service
 * branches at query-build time so each scope hits its appropriate index.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService - V220 org-scope routing")
class NotificationServiceOrgScopeTest {

    @Mock private NotificationReadStateRepository readStateRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;
    @Mock private Query jpqlQuery;

    private NotificationService service;

    private static final String TENANT_ID = "tenant-V220";
    private static final String ORG_ID = "org-V220";

    @BeforeEach
    void setUp() throws Exception {
        WorkflowSubjectNameResolver wfResolver = new WorkflowSubjectNameResolver(workflowRepository);
        SubjectNameResolver orgInvitationResolver = new SubjectNameResolver() {
            @Override
            public String subjectType() {
                return SubjectNameResolver.ORG_INVITATION;
            }

            @Override
            public java.util.Map<UUID, String> resolveNames(java.util.Set<UUID> subjectIds) {
                return subjectIds.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> "Org invite"));
            }
        };
        service = new NotificationService(
                readStateRepository, workflowRunRepository,
                List.<SubjectNameResolver>of(wfResolver, orgInvitationResolver));
        Field emField = NotificationService.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(service, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        lenient().when(workflowRunRepository.findExistingRunIdPublics(anyString(), any()))
                .thenAnswer(invocation -> {
                    java.util.Collection<String> in = invocation.getArgument(1);
                    return in == null ? List.of() : new java.util.ArrayList<>(in);
                });
        lenient().when(workflowRunRepository.findExistingRunIdPublicsByOrganizationId(anyString(), any()))
                .thenAnswer(invocation -> {
                    java.util.Collection<String> in = invocation.getArgument(1);
                    return in == null ? List.of() : new java.util.ArrayList<>(in);
                });
    }

    private Object[] workflowRow(UUID workflowId, Instant occurredAt) {
        return new Object[]{
                "WORKFLOW", workflowId, "RUN_FAILED", "run_pub", "error",
                Timestamp.from(occurredAt), null, null, null
        };
    }

    private Object[] orgInvitationRow(UUID invitationId, Instant occurredAt) {
        return new Object[]{
                "ORG_INVITATION", invitationId, "ORG_INVITATION_PENDING", null, "info",
                Timestamp.from(occurredAt), null, null, null
        };
    }

    @Test
    @DisplayName("Org scope (activeOrgId != null) → SELECT uses organization_id = :orgId, NOT tenant_id (strict workspace isolation)")
    void orgScopeSelectsByOrganizationId() {
        UUID wfId = UUID.randomUUID();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) workflowRow(wfId, Instant.now())));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(wfId);
        wf.setName("Org WF");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        NotificationsResponse resp = service.getNotifications(TENANT_ID, ORG_ID, 0, 15);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        // Org scope predicate is strict-org. Personal-scope IS NULL clause MUST
        // NOT appear in the WHERE clause (would silently widen the read to
        // legacy rows). Anchored regex on the WHERE predicate so the assertion
        // doesn't trip on stray "organization_id IS NULL" appearing in a
        // comment or in a different scope.
        assertThat(java.util.regex.Pattern
                .compile("\\borganization_id\\s*=\\s*:orgId\\b")
                .matcher(sql).find())
                .as("WHERE clause must use organization_id = :orgId - got: %s", sql)
                .isTrue();
        assertThat(java.util.regex.Pattern
                .compile("\\borganization_id\\s+IS\\s+NULL\\b")
                .matcher(sql).find())
                .as("organization_id IS NULL must NOT appear in org-scope SQL - got: %s", sql)
                .isFalse();
        // Post-V261 the strict-org branch binds only orgId. The legacy tenantId
        // bind was removed when the personal-scope IS NULL fallback was dropped.
        verify(nativeQuery).setParameter("orgId", ORG_ID);
        assertThat(resp.items()).hasSize(1);
    }

    @Test
    @DisplayName("Org scope returns user-level ORG_INVITATION_PENDING rows so CE invite notifications stay visible")
    void orgScopeReturnsUserLevelOrganizationInvitations() {
        UUID invitationId = UUID.randomUUID();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) orgInvitationRow(invitationId, Instant.now())));

        NotificationsResponse resp = service.getNotifications(TENANT_ID, ORG_ID, 0, 15);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        assertThat(item.subjectId()).isEqualTo(invitationId);
        assertThat(item.subjectType()).isEqualTo("ORG_INVITATION");
        assertThat(item.category()).isEqualTo("ORG_INVITATION_PENDING");
        assertThat(item.subjectName()).isEqualTo("Org invite");
    }

    @Test
    @DisplayName("Two-arg overload (orgId) routes to org scope with default page size")
    void twoArgOverloadRoutesToOrgScope() {
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(List.of());

        NotificationsResponse resp = service.getNotifications(TENANT_ID, ORG_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("organization_id = :orgId");
        // Default page size = MAX_BUCKETS (consumed by DashboardController.getHomeStatus).
        assertThat(resp.size()).isEqualTo(NotificationService.MAX_BUCKETS);
    }

    @Test
    @DisplayName("deleteBuckets in org scope binds organization_id; tenant_id is NOT part of the WHERE clause")
    void deleteBucketsOrgScopeBindsOrgId() {
        UUID s1 = UUID.randomUUID();
        when(entityManager.createQuery(anyString())).thenReturn(jpqlQuery);
        when(jpqlQuery.setParameter(anyInt(), any())).thenReturn(jpqlQuery);
        when(jpqlQuery.executeUpdate()).thenReturn(1);

        int deleted = service.deleteBuckets(TENANT_ID, ORG_ID, List.of(
                new NotificationService.BucketRef(s1, "RUN_FAILED")));

        assertThat(deleted).isEqualTo(1);
        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpqlCaptor.capture());
        String jpql = jpqlCaptor.getValue();
        // Post-V261 org scope: WHERE n.organizationId = ?1. tenantId is
        // intentionally absent - any org-teammate can clear bell rows they can
        // see (mirrors PR28 conversation sharing semantics).
        assertThat(jpql).contains("n.organizationId = ?1");
        // Parameter 1 = orgId. tenantId is not bound in org scope.
        verify(jpqlQuery).setParameter(1, ORG_ID);
    }

    @Test
    @DisplayName("Org scope routes liveness check via findExistingRunIdPublicsByOrganizationId - teammate-owned run survives, deep-link is NOT nullified")
    void orgScopeUsesOrgFinderForRunLiveness() {
        // Bug fixed: resolveLiveRunIdPublics was tenant-only. In an org
        // workspace the run row is usually owned by a teammate
        // (tenant_id == teammate_user_id, organization_id == orgId), so the
        // tenant-scoped existence query returned empty → every bell row had
        // latestRunIdPublic = null → click landed on a 404 page.
        UUID wfId = UUID.randomUUID();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) workflowRow(wfId, Instant.now())));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(wfId);
        wf.setName("Team WF");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        // Teammate-owned run: org finder returns the live runIdPublic. The
        // service MUST consult the org finder when activeOrgId != null. The
        // tenant finder default-stub (BeforeEach) returns the input echo -
        // pre-fix it would have been called and "run_pub" would have round-
        // tripped, masking the bug. The {@code verify(.., never())} below is
        // the guard.
        when(workflowRunRepository.findExistingRunIdPublicsByOrganizationId(
                org.mockito.ArgumentMatchers.eq(ORG_ID),
                any())).thenReturn(List.of("run_pub"));

        NotificationsResponse resp = service.getNotifications(TENANT_ID, ORG_ID, 0, 15);

        assertThat(resp.items()).hasSize(1);
        // Deep-link survives - this is the regression guard.
        assertThat(resp.items().get(0).runIdPublic()).isEqualTo("run_pub");
        // Org finder was hit; tenant finder MUST NOT have been called in org scope.
        verify(workflowRunRepository).findExistingRunIdPublicsByOrganizationId(
                org.mockito.ArgumentMatchers.eq(ORG_ID), any());
        verify(workflowRunRepository, org.mockito.Mockito.never())
                .findExistingRunIdPublics(org.mockito.ArgumentMatchers.eq(TENANT_ID), any());
    }

    @Test
    @DisplayName("Null activeOrgId short-circuits getNotifications to empty without touching the DB (post-V261)")
    void nullActiveOrgShortCircuitsToEmpty() {
        NotificationsResponse resp = service.getNotifications(TENANT_ID, null, 0, 15);

        assertThat(resp.items()).isEmpty();
        verify(entityManager, org.mockito.Mockito.never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Null activeOrgId makes deleteBuckets a no-op returning 0 without JPQL execution (post-V261)")
    void deleteBucketsNullOrgIsNoOp() {
        int deleted = service.deleteBuckets(TENANT_ID, null, List.of(
                new NotificationService.BucketRef(UUID.randomUUID(), "RUN_FAILED")));

        assertThat(deleted).isZero();
        verify(entityManager, org.mockito.Mockito.never()).createQuery(anyString());
    }
}
