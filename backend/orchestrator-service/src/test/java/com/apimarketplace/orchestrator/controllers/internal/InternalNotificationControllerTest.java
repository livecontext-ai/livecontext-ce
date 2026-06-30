package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.notification.client.dto.NotificationEmitResponse;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InternalNotificationController}. Validation paths
 * (400) + idempotent insert (200 created/skipped) + DB failure (500) +
 * Redis failure (200, swallowed).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalNotificationController")
class InternalNotificationControllerTest {

    @Mock private WorkflowRedisPublisher redisPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    private MeterRegistry meterRegistry;
    private InternalNotificationController controller;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        controller = new InternalNotificationController(redisPublisher, meterRegistry);
        Field emField = InternalNotificationController.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(controller, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
    }

    private NotificationEmitRequest validRequest() {
        NotificationEmitRequest r = new NotificationEmitRequest();
        r.setTenantId("user-1");
        // V261 - controller now requires organization_id on the request OR a
        // bound request-scope orgId. Tests are off-thread → set explicitly.
        r.setOrganizationId("user-1");
        r.setCategory("CRED_EXPIRED");
        r.setSeverity("warning");
        r.setSubjectType("CREDENTIAL");
        r.setSubjectId(UUID.randomUUID());
        r.setSourceId("cred-1:20260508");
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "expired");
        payload.put("expiresAt", "2026-05-09T00:00:00.000Z");
        r.setPayload(payload);
        r.setOccurredAt(Instant.parse("2026-05-08T10:00:00Z"));
        return r;
    }

    @Test
    @DisplayName("Valid request + insert → 200, created=true, Redis publish")
    void validRequestInsertsAndPublishes() {
        when(nativeQuery.getResultList()).thenReturn(List.of(42L));

        ResponseEntity<?> resp = controller.emit(validRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        NotificationEmitResponse body = (NotificationEmitResponse) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isCreated()).isTrue();
        assertThat(body.getNotificationId()).isEqualTo(42L);
        verify(redisPublisher).publishNotification(eq("user-1"),
                eq("notification.created"), any());
    }

    @Test
    @DisplayName("ON CONFLICT empty result → 200, created=false, no Redis publish (idempotent)")
    void onConflictDoesNotPublish() {
        when(nativeQuery.getResultList()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.emit(validRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        NotificationEmitResponse body = (NotificationEmitResponse) resp.getBody();
        assertThat(body.isCreated()).isFalse();
        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Null tenantId → 400")
    void nullTenantIdReturns400() {
        NotificationEmitRequest r = validRequest();
        r.setTenantId(null);

        ResponseEntity<?> resp = controller.emit(r);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Null category → 400")
    void nullCategoryReturns400() {
        NotificationEmitRequest r = validRequest();
        r.setCategory(null);

        ResponseEntity<?> resp = controller.emit(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Invalid severity → 400")
    void invalidSeverityReturns400() {
        NotificationEmitRequest r = validRequest();
        r.setSeverity("urgent");  // not in {info,warning,error}

        ResponseEntity<?> resp = controller.emit(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Subject_type not in V176 allow-list → 400 (typo guard)")
    void invalidSubjectTypeReturns400() {
        NotificationEmitRequest r = validRequest();
        r.setSubjectType("WORKFLOWS");  // typo

        ResponseEntity<?> resp = controller.emit(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("All notification subject types accepted")
    void allowedSubjectTypesPass() {
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));
        for (String type : List.of("WORKFLOW", "APPLICATION", "AGENT_TASK", "CREDENTIAL", "TRIGGER",
                "ORG_INVITATION")) {
            NotificationEmitRequest r = validRequest();
            r.setSubjectType(type);
            ResponseEntity<?> resp = controller.emit(r);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("Null subjectId → 400")
    void nullSubjectIdReturns400() {
        NotificationEmitRequest r = validRequest();
        r.setSubjectId(null);

        ResponseEntity<?> resp = controller.emit(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Null sourceId → 400")
    void nullSourceIdReturns400() {
        NotificationEmitRequest r = validRequest();
        r.setSourceId(null);

        ResponseEntity<?> resp = controller.emit(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Payload missing 'status' → 400 (V174 contract)")
    void payloadMissingStatusReturns400() {
        NotificationEmitRequest r = validRequest();
        Map<String, Object> p = new HashMap<>();
        p.put("expiresAt", "2026-05-09T00:00:00.000Z");
        r.setPayload(p);

        ResponseEntity<?> resp = controller.emit(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("DB failure → 500, error counted, Redis NOT called")
    void dbFailureReturns500() {
        when(nativeQuery.getResultList())
                .thenThrow(new DataAccessResourceFailureException("DB down"));

        ResponseEntity<?> resp = controller.emit(validRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verifyNoInteractions(redisPublisher);
        assertThat(meterRegistry.counter("notification.emitter.errors",
                "type", "DataAccessResourceFailureException").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis failure after successful insert → still 200, counted")
    void redisFailureSwallowedAfterInsert() {
        when(nativeQuery.getResultList()).thenReturn(List.of(7L));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("Redis down"))
                .when(redisPublisher).publishNotification(anyString(), anyString(), any());

        ResponseEntity<?> resp = controller.emit(validRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meterRegistry.counter("notification.emitter.errors",
                "type", "RedisPublish").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Payload value: status passed through to native query (V174 captor)")
    void payloadStatusFlowsThroughToInsert() {
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));
        org.mockito.ArgumentCaptor<Object> captor =
                org.mockito.ArgumentCaptor.forClass(Object.class);

        controller.emit(validRequest());

        // Post-V261: positional params shifted by +1 because
        // organization_id became column #2. Payload now sits at position 11
        // (after tenant, org, category, severity, subjectType, subjectId,
        // sourceId, runId, runIdPublic, planVersion → payload).
        verify(nativeQuery).setParameter(eq(11), captor.capture());
        String payloadJson = (String) captor.getValue();
        assertThat(payloadJson).contains("\"status\":\"expired\"");
    }
}
