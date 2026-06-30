package com.apimarketplace.orchestrator.controllers.notification;

import com.apimarketplace.orchestrator.services.notification.NotificationService;
import com.apimarketplace.orchestrator.services.notification.NotificationService.BucketRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V220 follow-up - pins the controller-edge category allow-list. The service
 * layer's WHERE clause already prevents cross-workspace deletes, but the
 * allow-list rejects unknown categories at the request boundary so a typoed
 * or attacker-controlled body never reaches the JPQL parameter binder.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationController - V220 category allow-list")
class NotificationControllerTest {

    @Mock private NotificationService service;
    @InjectMocks private NotificationController controller;

    private static final String TENANT = "user-7";
    private static final String ORG = "org-7";

    @Test
    @DisplayName("deleteBatch - known categories pass through to the service unchanged")
    void knownCategoriesPassThrough() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        UUID s4 = UUID.randomUUID();
        UUID s5 = UUID.randomUUID();
        List<BucketRef> body = List.of(
                new BucketRef(s1, "RUN_FAILED"),
                new BucketRef(s2, "APPROVAL_PENDING"),
                new BucketRef(s3, "ORG_INVITATION_PENDING"),
                new BucketRef(s4, "AGENT_TASK_ASSIGNED"),
                new BucketRef(s5, "BRIDGE_LOW_CREDIT"));
        when(service.deleteBuckets(eq(TENANT), eq(ORG), any())).thenReturn(5);

        ResponseEntity<Map<String, Object>> resp = controller.deleteBatch(TENANT, ORG, body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BucketRef>> captor =
                (ArgumentCaptor<List<BucketRef>>) (Object) ArgumentCaptor.forClass(List.class);
        verify(service).deleteBuckets(eq(TENANT), eq(ORG), captor.capture());
        assertThat(captor.getValue()).hasSize(5);
        assertThat(captor.getValue()).extracting(BucketRef::category)
                .containsExactly("RUN_FAILED", "APPROVAL_PENDING", "ORG_INVITATION_PENDING",
                        "AGENT_TASK_ASSIGNED", "BRIDGE_LOW_CREDIT");
        assertThat(resp.getBody()).containsEntry("deleted", 5);
    }

    @Test
    @DisplayName("deleteBatch - AGENT_TASK_AWAITING_REVIEW passes through (regression: missing from allow-list made the row undeletable, reappearing after the settle refetch)")
    void awaitingReviewCategoryPassesThrough() {
        // Producer: AgentTaskService emits AGENT_TASK_AWAITING_REVIEW, which the
        // bell renders (it has an i18n label). Pre-fix this category was absent
        // from KNOWN_CATEGORIES, so delete-batch stripped it at the edge, the
        // service deleted 0 rows, and the frontend's optimistic removal was
        // reverted by the onSettled refetch - the row never disappeared in
        // real time. It must now reach the service untouched.
        UUID subject = UUID.randomUUID();
        List<BucketRef> body = List.of(new BucketRef(subject, "AGENT_TASK_AWAITING_REVIEW"));
        when(service.deleteBuckets(eq(TENANT), eq(ORG), any())).thenReturn(1);

        ResponseEntity<Map<String, Object>> resp = controller.deleteBatch(TENANT, ORG, body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BucketRef>> captor =
                (ArgumentCaptor<List<BucketRef>>) (Object) ArgumentCaptor.forClass(List.class);
        verify(service).deleteBuckets(eq(TENANT), eq(ORG), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).category()).isEqualTo("AGENT_TASK_AWAITING_REVIEW");
        assertThat(captor.getValue().get(0).subjectId()).isEqualTo(subject);
        assertThat(resp.getBody()).containsEntry("deleted", 1);
    }

    @Test
    @DisplayName("deleteBatch - unknown category is stripped at the controller edge before reaching the service")
    void unknownCategoryStripped() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        List<BucketRef> body = List.of(
                new BucketRef(s1, "RUN_FAILED"),
                // Unknown / attacker-controlled category - must be dropped.
                new BucketRef(s2, "DROP TABLE notifications;"));
        when(service.deleteBuckets(eq(TENANT), eq(ORG), any())).thenReturn(1);

        controller.deleteBatch(TENANT, ORG, body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BucketRef>> captor =
                (ArgumentCaptor<List<BucketRef>>) (Object) ArgumentCaptor.forClass(List.class);
        verify(service).deleteBuckets(eq(TENANT), eq(ORG), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).category()).isEqualTo("RUN_FAILED");
    }

    @Test
    @DisplayName("deleteBatch - null or blank category buckets are dropped (defense in depth, never reach service)")
    void nullCategoryDropped() {
        UUID s1 = UUID.randomUUID();
        List<BucketRef> body = new java.util.ArrayList<>();
        body.add(new BucketRef(s1, "RUN_FAILED"));
        body.add(new BucketRef(UUID.randomUUID(), null));
        body.add(null);
        when(service.deleteBuckets(eq(TENANT), eq(ORG), any())).thenReturn(1);

        controller.deleteBatch(TENANT, ORG, body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BucketRef>> captor =
                (ArgumentCaptor<List<BucketRef>>) (Object) ArgumentCaptor.forClass(List.class);
        verify(service).deleteBuckets(eq(TENANT), eq(ORG), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("deleteBatch - null body is forwarded as-is (service handles null/empty as 0-row no-op)")
    void nullBodyForwarded() {
        when(service.deleteBuckets(eq(TENANT), eq(ORG), any())).thenReturn(0);

        ResponseEntity<Map<String, Object>> resp = controller.deleteBatch(TENANT, ORG, null);

        verify(service).deleteBuckets(eq(TENANT), eq(ORG), eq(null));
        assertThat(resp.getBody()).containsEntry("deleted", 0);
    }
}
