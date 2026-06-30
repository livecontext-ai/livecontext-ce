package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.NotificationReadStateEntity;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bell read-side service unit tests. Reads from the materialised
 * {@code orchestrator.notifications} table (post-V172) - this rewrite
 * replaces the prior on-demand-against-workflow_runs version.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService (post-V172 materialised)")
class NotificationServiceTest {

    @Mock private NotificationReadStateRepository readStateRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    /**
     * P1: NotificationService now dispatches to a list of resolvers keyed
     * by {@code subject_type}. Tests build a real {@link WorkflowSubjectNameResolver}
     * around the mocked {@link WorkflowRepository} so the WORKFLOW path
     * exercises the full dispatcher branch instead of the legacy
     * {@code resolveWorkflowNames} private method.
     */
    private WorkflowSubjectNameResolver workflowResolver;

    private NotificationService service;

    private static final String TENANT_ID = "tenant-7";

    @BeforeEach
    void setUp() throws Exception {
        workflowResolver = new WorkflowSubjectNameResolver(workflowRepository);
        service = new NotificationService(
                readStateRepository, workflowRunRepository, java.util.List.<SubjectNameResolver>of(workflowResolver));
        Field emField = NotificationService.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(service, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(nativeQuery);
        // Default: every referenced run is treated as live. Stale-row tests
        // override this to simulate a run that has been retention-purged.
        // doAnswer (not when().thenAnswer) so re-stubbing in a test does not
        // re-invoke the answer with the null arg from when()'s evaluator.
        org.mockito.Mockito.lenient()
                .doAnswer(invocation -> {
                    java.util.Collection<String> in = invocation.getArgument(1);
                    return in == null ? java.util.List.of() : new java.util.ArrayList<>(in);
                })
                .when(workflowRunRepository)
                .findExistingRunIdPublics(anyString(), any());
        // Post-V261: NotificationService.resolveLiveRunIdPublics routes to the
        // org-scoped finder when activeOrgId != null. Tests pass TENANT_ID as
        // activeOrgId (2-arg getNotifications(userId, activeOrgId)), so the
        // org variant is the one actually invoked. Mirror the tenant default.
        org.mockito.Mockito.lenient()
                .doAnswer(invocation -> {
                    java.util.Collection<String> in = invocation.getArgument(1);
                    return in == null ? java.util.List.of() : new java.util.ArrayList<>(in);
                })
                .when(workflowRunRepository)
                .findExistingRunIdPublicsByOrganizationId(anyString(), any());
    }

    /**
     * Row tuple matching the SELECT shape: subject_type, subject_id, category,
     * run_id_public, severity, occurred_at, integration, credential_id,
     * trigger_kind. The last three columns are subject-type-specific (null for
     * unrelated rows) and default to null in this helper.
     * <p>NotificationService reads payload columns defensively via
     * {@code row.length > N ? ... : null}, so legacy 8-element rows from
     * older test paths still work - but new helpers project all 9 columns so
     * the test surface matches the production SELECT shape.
     */
    private Object[] row(String subjectType, UUID subjectId, String category, String runIdPublic, String severity, Instant occurredAt) {
        return new Object[]{ subjectType, subjectId, category, runIdPublic, severity, Timestamp.from(occurredAt), null, null, null };
    }

    /** Convenience overload defaulting to RUN_FAILED for tests that pre-date P2a multi-category. */
    private Object[] row(String subjectType, UUID subjectId, String runIdPublic, String severity, Instant occurredAt) {
        return row(subjectType, subjectId, "RUN_FAILED", runIdPublic, severity, occurredAt);
    }

    /**
     * CREDENTIAL row variant with the P7 payload columns populated. Mirrors what
     * the live native query projects for a {@code CRED_EXPIRED} bell row:
     * {@code payload->>'integration'} and {@code payload->>'credentialId'}.
     */
    private Object[] credentialRow(UUID subjectId, String category, String severity,
                                    Instant occurredAt, String integration, String credentialId) {
        return new Object[]{ "CREDENTIAL", subjectId, category, null, severity,
                Timestamp.from(occurredAt), integration, credentialId, null };
    }

    /**
     * TRIGGER row variant with the {@code payload->>'triggerKind'} column populated.
     * Mirrors what {@link com.apimarketplace.trigger.service.TriggerLifecycleManager}
     * writes when emitting {@code WEBHOOK_TRIGGER_DISABLED} for a disabled
     * schedule / webhook / chat / form. Used to pin the bell's per-row icon +
     * the deep-link tab on /app/settings/public-access.
     */
    private Object[] triggerRow(UUID subjectId, String category, String severity,
                                 Instant occurredAt, String triggerKind) {
        return new Object[]{ "TRIGGER", subjectId, category, null, severity,
                Timestamp.from(occurredAt), null, null, triggerKind };
    }

    /** Row variant returning Instant directly at index 5 (Hibernate 6 + Postgres timestamptz behavior). */
    private Object[] rowWithInstantOccurredAt(String subjectType, UUID subjectId, String runIdPublic, String severity, Instant occurredAt) {
        return new Object[]{ subjectType, subjectId, "RUN_FAILED", runIdPublic, severity, occurredAt, null, null, null };
    }

    @Test
    @DisplayName("Empty result set returns empty items + zero unread")
    void emptyResultSet() {
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(List.of());

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items()).isEmpty();
        assertThat(response.unreadCount()).isZero();
    }

    @Test
    @DisplayName("First-time user (Instant.EPOCH cursor) sees every event as unread")
    void firstTimeUserAllUnread() {
        UUID workflowId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(60);

        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "run_abc", "error", occurredAt)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("My Workflow");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items()).hasSize(1);
        NotificationItem item = response.items().get(0);
        assertThat(item.subjectName()).isEqualTo("My Workflow");
        assertThat(item.runIdPublic()).isEqualTo("run_abc");
        assertThat(item.severity()).isEqualTo("error");
        assertThat(item.count()).isEqualTo(1);
        assertThat(item.unread()).isTrue();
        assertThat(response.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Multiple failures of the same workflow aggregate to one item; latest run wins")
    void aggregatesMultipleFailuresPerWorkflow() {
        UUID workflowId = UUID.randomUUID();
        Instant first = Instant.now().minusSeconds(300);
        Instant later = Instant.now().minusSeconds(60);

        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "run_newest", "error", later),
                (Object) row("WORKFLOW", workflowId, "run_oldest", "error", first)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("Daily Report");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items()).hasSize(1);
        NotificationItem item = response.items().get(0);
        assertThat(item.count()).isEqualTo(2);
        assertThat(item.runIdPublic()).isEqualTo("run_newest"); // most-recent occurredAt
        assertThat(item.lastEventAt()).isEqualTo(later);
        assertThat(item.firstEventAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("Events newer than last_seen_at are unread; older are read")
    void readVsUnreadDiscrimination() {
        UUID wfA = UUID.randomUUID();
        UUID wfB = UUID.randomUUID();
        Instant lastSeen = Instant.now().minusSeconds(120);
        Instant beforeSeen = lastSeen.minusSeconds(60);
        Instant afterSeen = lastSeen.plusSeconds(60);

        when(readStateRepository.findByUserId(TENANT_ID))
                .thenReturn(Optional.of(new NotificationReadStateEntity(TENANT_ID, lastSeen)));
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", wfA, "run_old", "error", beforeSeen),
                (Object) row("WORKFLOW", wfB, "run_new", "error", afterSeen)));
        WorkflowEntity wA = new WorkflowEntity(); wA.setId(wfA); wA.setName("A");
        WorkflowEntity wB = new WorkflowEntity(); wB.setId(wfB); wB.setName("B");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wA, wB));

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        NotificationItem itemA = response.items().stream().filter(i -> i.subjectName().equals("A")).findFirst().orElseThrow();
        NotificationItem itemB = response.items().stream().filter(i -> i.subjectName().equals("B")).findFirst().orElseThrow();
        assertThat(itemA.unread()).isFalse();
        assertThat(itemB.unread()).isTrue();
        assertThat(response.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Bucket cap retains exactly MAX_BUCKETS most-recent workflows; rows are pre-sorted DESC by occurred_at")
    void bucketCapPreventsExplosion() {
        // 50 distinct workflows pre-sorted DESC by occurred_at (matches the
        // SELECT's ORDER BY). With MAX_BUCKETS=40 (P2a bump), the cap retains
        // the first 40 distinct (= the 40 most-recent) and silently drops the
        // older 10.
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        List<Object> rows = new ArrayList<>();
        List<WorkflowEntity> workflows = new ArrayList<>();
        List<UUID> expectedRetainedIds = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 50; i++) {
            UUID wfId = UUID.randomUUID();
            // i=0 has the most recent timestamp (now); i=49 oldest (now - 49s).
            rows.add(row("WORKFLOW", wfId, "run_" + i, "error", now.minusSeconds(i)));
            WorkflowEntity wf = new WorkflowEntity(); wf.setId(wfId); wf.setName("W" + i);
            workflows.add(wf);
            if (i < 40) expectedRetainedIds.add(wfId);
        }
        when(nativeQuery.getResultList()).thenReturn(rows);
        when(workflowRepository.findAllById(any())).thenReturn(workflows);

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        // Hard-coded 40 - guards against silent constant change.
        assertThat(response.items()).hasSize(40);
        assertThat(NotificationService.MAX_BUCKETS).isEqualTo(40);
        // Retention order: most-recent 40 IDs win, oldest 10 are dropped.
        List<UUID> actualRetainedIds = response.items().stream()
                .map(NotificationItem::subjectId)
                .toList();
        assertThat(actualRetainedIds).containsExactlyElementsOf(expectedRetainedIds);
    }

    @Test
    @DisplayName("Hibernate 6 returns Instant directly for timestamptz - aggregator must coerce, not cast (E2E regression)")
    void aggregatorAcceptsInstantOccurredAtFromHibernate6() {
        // Pre-fix: ((Timestamp) row[5]).toInstant() threw ClassCastException because
        // Hibernate 6 returns Instant directly for Postgres timestamptz columns. The
        // unit-test fixtures used Timestamp.from() and missed the production shape.
        // Found via P3 E2E: GET /api/notifications returned 500 INTERNAL_ERROR.
        UUID workflowId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(60);

        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) rowWithInstantOccurredAt("WORKFLOW", workflowId, "run_h6", "error", occurredAt)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("H6 Workflow");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        // Must not throw ClassCastException. Pre-fix this would crash with
        // "Instant cannot be cast to Timestamp".
        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).lastEventAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("Buckets separately by (subject_id, category) - RUN_FAILED + APPROVAL_PENDING for same workflow surface as 2 items, not 1 conflated")
    void bucketsSeparatelyByCategoryForSameWorkflow() {
        UUID workflowId = UUID.randomUUID();
        Instant approvalAt = Instant.now().minusSeconds(60);
        Instant failureAt = Instant.now().minusSeconds(30);  // newer

        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "RUN_FAILED",        "run_failed",  "error", failureAt),
                (Object) row("WORKFLOW", workflowId, "APPROVAL_PENDING",  "run_pending", "info",  approvalAt)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("Daily Approval Workflow");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        // Two distinct items - categories MUST NOT conflate even for same workflow.
        assertThat(response.items()).hasSize(2);

        NotificationItem failedItem = response.items().stream()
                .filter(i -> "RUN_FAILED".equals(i.category())).findFirst().orElseThrow();
        NotificationItem pendingItem = response.items().stream()
                .filter(i -> "APPROVAL_PENDING".equals(i.category())).findFirst().orElseThrow();

        assertThat(failedItem.severity()).isEqualTo("error");
        assertThat(failedItem.runIdPublic()).isEqualTo("run_failed");
        assertThat(pendingItem.severity()).isEqualTo("info");
        assertThat(pendingItem.runIdPublic()).isEqualTo("run_pending");
        // Same workflow id under both - bell renders one row per category.
        assertThat(failedItem.subjectId()).isEqualTo(workflowId);
        assertThat(pendingItem.subjectId()).isEqualTo(workflowId);
        assertThat(failedItem.subjectName()).isEqualTo("Daily Approval Workflow");
    }

    @Test
    @DisplayName("Deleted workflow → bucket dropped from response (regression: Issue 3 - inbox click landed on unknown workflow)")
    void deletedWorkflowBucketDropped() {
        // Pre-fix: rendered with DELETED_WORKFLOW_LABEL but stayed clickable -
        // landing the user on /app/workflow/{deleted-id} which 404'd. Surfacing
        // an unactionable row is worse than silence; the bucket is now skipped.
        UUID workflowId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(60);

        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "run_deleted", "error", occurredAt)));
        // Workflow row missing (deleted post-emit)
        when(workflowRepository.findAllById(any())).thenReturn(List.of());

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items()).isEmpty();
        assertThat(response.unreadCount()).isZero();
    }

    @Test
    @DisplayName("Run referenced by bucket no longer exists in workflow_runs → runIdPublic nullified, bucket kept (regression: Issue 3 - click landed on unknown runId)")
    void purgedRunNullifiesRunIdPublic() {
        UUID workflowId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(60);

        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "run_purged", "error", occurredAt)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("Live Workflow");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));
        // Override the @BeforeEach default: the referenced run was retention-purged.
        // doReturn - when().thenReturn would invoke the existing doAnswer first
        // with a null arg (Mockito recording quirk). Post-V261 the service routes
        // to the org-scoped finder because the test passes a non-null activeOrgId.
        org.mockito.Mockito.doReturn(List.of())
                .when(workflowRunRepository)
                .findExistingRunIdPublicsByOrganizationId(anyString(), any());

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items()).hasSize(1);
        // Workflow stays - the user needs to know it failed. But the click
        // target is downgraded from /run/{id} to /app/workflow/{id} so the
        // user lands on a page that exists.
        assertThat(response.items().get(0).subjectName()).isEqualTo("Live Workflow");
        assertThat(response.items().get(0).runIdPublic()).isNull();
    }

    @Test
    @DisplayName("Constructor throws on duplicate subjectType() across resolvers - prevents silent shadowing under Spring bean ordering")
    void constructorRejectsDuplicateSubjectType() {
        WorkflowSubjectNameResolver first = new WorkflowSubjectNameResolver(workflowRepository);
        WorkflowSubjectNameResolver second = new WorkflowSubjectNameResolver(workflowRepository);
        // Both expose subjectType() == "WORKFLOW". The second registration
        // would otherwise silently overwrite the first depending on
        // Spring's @Component scan order - the constructor must fail-fast.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new NotificationService(
                        readStateRepository, workflowRunRepository, java.util.List.<SubjectNameResolver>of(first, second)));
    }

    @Test
    @DisplayName("Subject-type dispatcher routes WORKFLOW rows to WorkflowSubjectNameResolver - name resolution path stays correct after P1 refactor")
    void dispatcherRoutesToWorkflowResolver() {
        UUID workflowId = UUID.randomUUID();
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "run_x", "error", now)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("Renamed-Live");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items().get(0).subjectName()).isEqualTo("Renamed-Live");
        verify(workflowRepository).findAllById(any());
    }

    @Test
    @DisplayName("Unknown subject_type → bucket dropped (no resolver bean → no name → no clickable row)")
    void unknownSubjectTypeBucketDropped() {
        // Same logic as deleted-workflow: a row whose name cannot be resolved
        // is unactionable. We log once-per-JVM but do not surface the row.
        UUID subjectId = UUID.randomUUID();
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("APPLICATION", subjectId, "run_y", "error", now)));

        NotificationsResponse response = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(response.items()).isEmpty();
        // workflowRepository must NOT be called - the dispatcher correctly skipped the unknown type.
        verify(workflowRepository, never()).findAllById(any());
    }

    // ========================================================================
    // P3+ pagination & bulk delete (paginated bell + delete-batch endpoint)
    // ========================================================================

    @Test
    @DisplayName("getNotifications(page=0, size=2) returns first 2 items + hasMore=true when 3 buckets exist")
    void paginationFirstPage() {
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        Instant now = Instant.now();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", a, "run_a", "error", now),
                (Object) row("WORKFLOW", b, "run_b", "error", now.minusSeconds(60)),
                (Object) row("WORKFLOW", c, "run_c", "error", now.minusSeconds(120))));
        WorkflowEntity wA = new WorkflowEntity(); wA.setId(a); wA.setName("A");
        WorkflowEntity wB = new WorkflowEntity(); wB.setId(b); wB.setName("B");
        WorkflowEntity wC = new WorkflowEntity(); wC.setId(c); wC.setName("C");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wA, wB, wC));

        NotificationsResponse resp = service.getNotifications(TENANT_ID, TENANT_ID,0, 2);

        assertThat(resp.items()).hasSize(2);
        assertThat(resp.page()).isEqualTo(0);
        assertThat(resp.size()).isEqualTo(2);
        assertThat(resp.hasMore()).isTrue();
        // unreadCount stays GLOBAL (3) even though only 2 items returned - pagination
        // must not change the badge as the user navigates pages.
        assertThat(resp.unreadCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getNotifications page=1 size=2 returns last bucket + hasMore=false")
    void paginationLastPage() {
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        Instant now = Instant.now();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", a, "run_a", "error", now),
                (Object) row("WORKFLOW", b, "run_b", "error", now.minusSeconds(60)),
                (Object) row("WORKFLOW", c, "run_c", "error", now.minusSeconds(120))));
        // All three workflows must resolve, otherwise the stale-row gate added
        // for Issue 3 drops them and the page slice falls below the assertion.
        WorkflowEntity wA = new WorkflowEntity(); wA.setId(a); wA.setName("A");
        WorkflowEntity wB = new WorkflowEntity(); wB.setId(b); wB.setName("B");
        WorkflowEntity wC = new WorkflowEntity(); wC.setId(c); wC.setName("C");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wA, wB, wC));

        NotificationsResponse resp = service.getNotifications(TENANT_ID, TENANT_ID,1, 2);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.page()).isEqualTo(1);
        assertThat(resp.hasMore()).isFalse();
    }

    @Test
    @DisplayName("getNotifications page beyond data → empty items, hasMore=false (no overflow)")
    void paginationBeyondReturnsEmpty() {
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(List.of());

        NotificationsResponse resp = service.getNotifications(TENANT_ID, TENANT_ID,10, 5);

        assertThat(resp.items()).isEmpty();
        assertThat(resp.hasMore()).isFalse();
        assertThat(resp.page()).isEqualTo(10);
    }

    @Test
    @DisplayName("getNotifications negative page clamped to 0; oversized size clamped to MAX_BUCKETS")
    void paginationClampsBadInput() {
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(List.of());

        NotificationsResponse resp = service.getNotifications(TENANT_ID, TENANT_ID,-5, 9999);

        assertThat(resp.page()).isEqualTo(0);
        assertThat(resp.size()).isEqualTo(NotificationService.MAX_BUCKETS);
    }

    @Test
    @DisplayName("deleteBuckets with empty list → 0 (no-op, no DB call)")
    void deleteBucketsEmptyNoOp() {
        int deleted = service.deleteBuckets(TENANT_ID, TENANT_ID,List.of());

        assertThat(deleted).isZero();
        verify(entityManager, never()).createQuery(anyString());
    }

    @Test
    @DisplayName("deleteBuckets null list → 0 (no-op, no DB call)")
    void deleteBucketsNullNoOp() {
        int deleted = service.deleteBuckets(TENANT_ID, TENANT_ID,null);

        assertThat(deleted).isZero();
        verify(entityManager, never()).createQuery(anyString());
    }

    @Test
    @DisplayName("deleteBuckets builds JPQL with paired (subjectId, category) predicates and returns row count")
    void deleteBucketsExecutesJpql() {
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        org.mockito.Mockito.when(entityManager.createQuery(anyString())).thenReturn(jpqlQuery());
        when(jpqlQuery().setParameter(org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(jpqlQuery());
        when(jpqlQuery().executeUpdate()).thenReturn(3);

        int deleted = service.deleteBuckets(TENANT_ID, TENANT_ID,List.of(
                new NotificationService.BucketRef(s1, "RUN_FAILED"),
                new NotificationService.BucketRef(s2, "APPROVAL_PENDING")));

        assertThat(deleted).isEqualTo(3);
        // tenant_id at param 1; (subject, category) pairs at 2,3 / 4,5
        verify(jpqlQuery()).setParameter(1, TENANT_ID);
        verify(jpqlQuery()).setParameter(2, s1);
        verify(jpqlQuery()).setParameter(3, "RUN_FAILED");
        verify(jpqlQuery()).setParameter(4, s2);
        verify(jpqlQuery()).setParameter(5, "APPROVAL_PENDING");
    }

    @Test
    @DisplayName("deleteBuckets filters out null entries / null fields - only well-formed pairs reach JPQL")
    void deleteBucketsFiltersMalformedEntries() {
        UUID s1 = UUID.randomUUID();
        org.mockito.Mockito.when(entityManager.createQuery(anyString())).thenReturn(jpqlQuery());
        when(jpqlQuery().setParameter(org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(jpqlQuery());
        when(jpqlQuery().executeUpdate()).thenReturn(1);

        // Mix of malformed entries + 1 valid → only the valid one survives.
        java.util.List<NotificationService.BucketRef> input = new java.util.ArrayList<>();
        input.add(null);
        input.add(new NotificationService.BucketRef(null, "X"));
        input.add(new NotificationService.BucketRef(UUID.randomUUID(), null));
        input.add(new NotificationService.BucketRef(UUID.randomUUID(), ""));
        input.add(new NotificationService.BucketRef(s1, "RUN_FAILED"));

        int deleted = service.deleteBuckets(TENANT_ID, TENANT_ID,input);

        assertThat(deleted).isEqualTo(1);
        // Only the valid pair was bound.
        verify(jpqlQuery()).setParameter(2, s1);
        verify(jpqlQuery()).setParameter(3, "RUN_FAILED");
        // No 4th param means only one pair was processed.
        verify(jpqlQuery(), never()).setParameter(4, (Object) null);
    }

    @Test
    @DisplayName("deleteBuckets all-malformed input → 0 (no JPQL executed)")
    void deleteBucketsAllMalformedNoOp() {
        java.util.List<NotificationService.BucketRef> input = new java.util.ArrayList<>();
        input.add(null);
        input.add(new NotificationService.BucketRef(null, null));
        input.add(new NotificationService.BucketRef(UUID.randomUUID(), "  "));

        int deleted = service.deleteBuckets(TENANT_ID, TENANT_ID,input);

        assertThat(deleted).isZero();
        verify(entityManager, never()).createQuery(anyString());
    }

    /** Lazy holder for the JPQL Query mock - created on first access to keep single-test mocks isolated. */
    @org.mockito.Mock private jakarta.persistence.Query jpqlQueryMock;
    private jakarta.persistence.Query jpqlQuery() { return jpqlQueryMock; }

    // ========================================================================
    // P7 - multi-subject-type bell rows (CREDENTIAL CRED_EXPIRED)
    //
    // Closes the prod bug where CRED_EXPIRED rows routed to /app/workflow/<uuid>:
    // the bell DTO didn't expose subjectType and the synthetic CREDENTIAL UUID
    // was being treated as a workflow id, 404'ing the click.
    // ========================================================================

    /**
     * Build a NotificationService instance with a stubbed CREDENTIAL resolver so
     * the dispatcher accepts {@code subject_type='CREDENTIAL'} rows. Without this
     * helper, the CREDENTIAL bucket would be silently dropped by the
     * deleted-subject gate (Issue 3 regression behavior).
     */
    private NotificationService serviceWithCredentialResolver() throws Exception {
        SubjectNameResolver credResolver = new SubjectNameResolver() {
            @Override public String subjectType() { return "CREDENTIAL"; }
            @Override public java.util.Map<UUID, String> resolveNames(java.util.Set<UUID> ids) {
                java.util.Map<UUID, String> out = new java.util.HashMap<>();
                ids.forEach(id -> out.put(id, "test"));
                return out;
            }
        };
        NotificationService svc = new NotificationService(
                readStateRepository, workflowRunRepository,
                java.util.List.<SubjectNameResolver>of(workflowResolver, credResolver));
        Field emField = NotificationService.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(svc, entityManager);
        return svc;
    }

    @Test
    @DisplayName("CREDENTIAL row surfaces subjectType + integration + credentialId - bell routes to /app/settings/credentials, not /app/workflow (prod-bug regression)")
    void credentialRowExposesSubjectTypeAndPayloadFields() throws Exception {
        // Mirrors the live prod bug: OAuth2RefreshScheduler emitted a CRED_EXPIRED
        // row for credential id=51 / integration=googlecalendar; bell mis-routed
        // because subjectType wasn't on the DTO.
        UUID credUuid = UUID.nameUUIDFromBytes("cred-51".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) credentialRow(credUuid, "CRED_EXPIRED", "warning", now,
                        "googlecalendar", "51")));

        NotificationsResponse resp = serviceWithCredentialResolver().getNotifications(TENANT_ID, TENANT_ID);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        assertThat(item.subjectType()).isEqualTo("CREDENTIAL");
        assertThat(item.subjectId()).isEqualTo(credUuid);
        assertThat(item.subjectName()).isEqualTo("test");
        assertThat(item.integration()).isEqualTo("googlecalendar");
        assertThat(item.credentialId()).isEqualTo("51");
        assertThat(item.category()).isEqualTo("CRED_EXPIRED");
        assertThat(item.severity()).isEqualTo("warning");
        // run_id_public stays null for non-WORKFLOW rows - emitter contract.
        assertThat(item.runIdPublic()).isNull();
    }

    @Test
    @DisplayName("Non-CREDENTIAL row never leaks integration/credentialId - payload columns return null even if emitter ever poisons them (subject-type isolation)")
    void nonCredentialRowsDoNotLeakPayloadFields() {
        // Defense against a future emitter that puts integration/credentialId
        // keys into a WORKFLOW payload by mistake. The SELECT projects them
        // unconditionally (Postgres ->> returns NULL for missing keys), but the
        // unit-test fixture mirrors what the live DB would return: NULL for
        // non-CREDENTIAL rows because the producers don't emit those keys.
        UUID workflowId = UUID.randomUUID();
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "RUN_FAILED", "run_x", "error", now)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("Daily");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        NotificationsResponse resp = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        assertThat(item.subjectType()).isEqualTo("WORKFLOW");
        assertThat(item.integration()).isNull();
        assertThat(item.credentialId()).isNull();
    }

    @Test
    @DisplayName("CREDENTIAL row with null payload.credentialId surfaces gracefully - frontend routes to /app/settings/credentials without focus param")
    void credentialRowWithNullCredentialIdRoutesGracefully() throws Exception {
        UUID credUuid = UUID.randomUUID();
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                // Legacy or buggy emitter: no credentialId in payload.
                (Object) credentialRow(credUuid, "CRED_EXPIRED", "warning", now, "gmail", null)));

        NotificationsResponse resp = serviceWithCredentialResolver().getNotifications(TENANT_ID, TENANT_ID);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        assertThat(item.subjectType()).isEqualTo("CREDENTIAL");
        assertThat(item.integration()).isEqualTo("gmail");
        assertThat(item.credentialId()).isNull();
    }

    /**
     * Build a NotificationService instance with a stubbed TRIGGER resolver so
     * the dispatcher accepts {@code subject_type='TRIGGER'} rows. Mirrors the
     * {@link #serviceWithCredentialResolver} pattern.
     */
    private NotificationService serviceWithTriggerResolver() throws Exception {
        SubjectNameResolver triggerResolver = new SubjectNameResolver() {
            @Override public String subjectType() { return SubjectNameResolver.TRIGGER; }
            @Override public java.util.Map<UUID, String> resolveNames(java.util.Set<UUID> ids) {
                java.util.Map<UUID, String> out = new java.util.HashMap<>();
                ids.forEach(id -> out.put(id, "Daily Email Digest"));
                return out;
            }
        };
        NotificationService svc = new NotificationService(
                readStateRepository, workflowRunRepository,
                java.util.List.<SubjectNameResolver>of(workflowResolver, triggerResolver));
        Field emField = NotificationService.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(svc, entityManager);
        return svc;
    }

    @Test
    @DisplayName("TRIGGER row surfaces triggerKind from payload - bell uses it for icon + tab deep-link on /app/settings/public-access")
    void triggerRowSurfacesTriggerKindForDeepLink() throws Exception {
        // Closes the prod UX bug where the user clicked "1 disabled cron" in
        // the inbox and landed on /app/workflow (no actionable surface for a
        // suspended schedule). The bell now routes to
        // /app/settings/public-access?tab={triggerKind} so the user lands on
        // the right tab - but only if triggerKind reaches the DTO.
        UUID triggerId = UUID.randomUUID();
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) triggerRow(triggerId, "WEBHOOK_TRIGGER_DISABLED", "warning", now, "schedule")));

        NotificationsResponse resp = serviceWithTriggerResolver().getNotifications(TENANT_ID, TENANT_ID);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        assertThat(item.subjectType()).isEqualTo("TRIGGER");
        assertThat(item.subjectId()).isEqualTo(triggerId);
        assertThat(item.triggerKind()).isEqualTo("schedule");
        // Non-TRIGGER payload columns stay null - guards against a future
        // SELECT change that accidentally widens the projection.
        assertThat(item.integration()).isNull();
        assertThat(item.credentialId()).isNull();
        // run_id_public stays null for non-WORKFLOW rows - emitter contract.
        assertThat(item.runIdPublic()).isNull();
    }

    @Test
    @DisplayName("TRIGGER row with legacy emitter (no payload.triggerKind) keeps triggerKind=null - bell falls back to default tab without crashing")
    void triggerRowWithNullTriggerKindStaysNull() throws Exception {
        // Defense for in-flight migration where an older emitter writes
        // TRIGGER rows without the triggerKind key. payload->>'triggerKind'
        // returns NULL in that case; the DTO must surface it as null so the
        // frontend's `item.triggerKind ?? 'webhook'` fallback kicks in.
        UUID triggerId = UUID.randomUUID();
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) triggerRow(triggerId, "WEBHOOK_TRIGGER_DISABLED", "warning", now, null)));

        NotificationsResponse resp = serviceWithTriggerResolver().getNotifications(TENANT_ID, TENANT_ID);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        assertThat(item.subjectType()).isEqualTo("TRIGGER");
        assertThat(item.triggerKind()).isNull();
    }

    @Test
    @DisplayName("Non-TRIGGER row never leaks triggerKind - projection stays subject-type-isolated like integration/credentialId")
    void nonTriggerRowsDoNotLeakTriggerKind() {
        // Symmetric to nonCredentialRowsDoNotLeakPayloadFields: a WORKFLOW row
        // must never carry a triggerKind even though the SELECT projects the
        // column unconditionally. In live DB this holds because emitters
        // don't put triggerKind into non-TRIGGER payloads; the test pins the
        // contract so a future emitter bug surfaces here.
        UUID workflowId = UUID.randomUUID();
        Instant now = Instant.now();
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) row("WORKFLOW", workflowId, "RUN_FAILED", "run_x", "error", now)));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(workflowId);
        wf.setName("Daily");
        when(workflowRepository.findAllById(any())).thenReturn(List.of(wf));

        NotificationsResponse resp = service.getNotifications(TENANT_ID, TENANT_ID);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        assertThat(item.subjectType()).isEqualTo("WORKFLOW");
        assertThat(item.triggerKind()).isNull();
    }

    @Test
    @DisplayName("AggBucket captures integration/credentialId from first row (newest, ORDER BY occurred_at DESC) - staler payloads in later rows are ignored")
    void aggBucketCapturesFirstHitNewestPayload() throws Exception {
        // Two CRED_EXPIRED rows for the same credentialId at different epochDays
        // (= different source_ids = both rows survive ON CONFLICT). Newest wins.
        UUID credUuid = UUID.randomUUID();
        Instant newer = Instant.now();
        Instant older = newer.minusSeconds(86_400);
        when(readStateRepository.findByUserId(TENANT_ID)).thenReturn(Optional.empty());
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                // SELECT ordered DESC: newer first.
                (Object) credentialRow(credUuid, "CRED_EXPIRED", "warning", newer, "googlecalendar", "51"),
                (Object) credentialRow(credUuid, "CRED_EXPIRED", "warning", older, "STALE_INTEGRATION", "STALE_ID")));

        NotificationsResponse resp = serviceWithCredentialResolver().getNotifications(TENANT_ID, TENANT_ID);

        assertThat(resp.items()).hasSize(1);
        NotificationItem item = resp.items().get(0);
        // Newest values win - staler row's payload must be ignored.
        assertThat(item.integration()).isEqualTo("googlecalendar");
        assertThat(item.credentialId()).isEqualTo("51");
        assertThat(item.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("markAllAsRead executes a native UPSERT (no UPDATE-then-INSERT race)")
    void markAllAsReadUpsertsCursor() {
        when(nativeQuery.executeUpdate()).thenReturn(1);

        service.markAllAsRead(TENANT_ID);

        // Single native UPSERT - verify the SQL is the ON CONFLICT form
        // (would-have-been-thrown DataIntegrityViolationException pattern is gone).
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("INSERT INTO orchestrator.notification_read_state")
                .contains("ON CONFLICT (user_id) DO UPDATE");
        verify(readStateRepository, never()).save(any());
    }
}
