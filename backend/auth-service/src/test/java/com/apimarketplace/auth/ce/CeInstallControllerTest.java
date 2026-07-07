package com.apimarketplace.auth.ce;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeInstallController")
class CeInstallControllerTest {

    @Mock
    private CeInstallStateService service;

    @Mock
    private AuditLogger auditLogger;

    @Mock
    private AuditLogger.Builder eventBuilder;

    @Mock
    private HttpServletRequest request;

    private CeInstallController controller;

    @BeforeEach
    void setUp() {
        controller = new CeInstallController(service);
        ReflectionTestUtils.setField(controller, "auditLogger", auditLogger);
        // Default prev-state for the controller's audit-emit gate. Tests that
        // care about a specific prev-state override this explicitly.
        org.mockito.Mockito.lenient().when(service.getStatus())
                .thenReturn(new CeStatusView(false, null, "v1", true));
        // Fluent-builder stubs (lenient - not all tests trigger the audit emit).
        org.mockito.Mockito.lenient().when(auditLogger.eventFromRequest(org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(eventBuilder);
        org.mockito.Mockito.lenient().when(eventBuilder.user(org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(eventBuilder);
        org.mockito.Mockito.lenient().when(eventBuilder.success()).thenReturn(eventBuilder);
        org.mockito.Mockito.lenient().when(eventBuilder.detail(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(eventBuilder);
    }

    @Test
    @DisplayName("GET /status returns service view - no auth required")
    void getStatusReturnsView() {
        CeStatusView view = new CeStatusView(false, null, "v1", true);
        when(service.getStatus()).thenReturn(view);

        ResponseEntity<CeStatusView> response = controller.status();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(view);
    }

    @Test
    @DisplayName("GET /status body serializes hasUsers - the JSON field the frontend first-run check consumes")
    void statusJsonCarriesHasUsers() throws Exception {
        when(service.getStatus()).thenReturn(new CeStatusView(false, null, "v1", true, false));

        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(controller.status().getBody());

        assertThat(json).contains("\"hasUsers\":false");
        assertThat(json).contains("\"registrationOpen\":true");
    }

    @Test
    @DisplayName("POST /complete by ADMIN flips state and returns updated view")
    void postCompleteAsAdmin() {
        CeStatusView afterView = new CeStatusView(true, Instant.parse("2026-04-22T10:00:00Z"), "v1", false);
        when(service.markBootstrapped(42L)).thenReturn(afterView);

        ResponseEntity<?> response = controller.complete("42", "USER,ADMIN", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(afterView);
        verify(service).markBootstrapped(42L);
    }

    @Test
    @DisplayName("POST /complete by non-admin → 403 Forbidden, service not called")
    void postCompleteAsNonAdminForbidden() {
        ResponseEntity<?> response = controller.complete("42", "USER", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).markBootstrapped(any());
    }

    @Test
    @DisplayName("POST /complete with empty X-User-ID stores null admin id (audit-only, non-blocking)")
    void postCompleteWithEmptyUserIdHeader() {
        CeStatusView view = new CeStatusView(true, Instant.now(), "v1", false);
        when(service.markBootstrapped(isNull())).thenReturn(view);

        ResponseEntity<?> response = controller.complete("", "USER,ADMIN", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).markBootstrapped(isNull());
    }

    @Test
    @DisplayName("POST /complete with non-numeric X-User-ID is tolerated (stored as null)")
    void postCompleteWithNonNumericUserId() {
        CeStatusView view = new CeStatusView(true, Instant.now(), "v1", false);
        when(service.markBootstrapped(isNull())).thenReturn(view);

        ResponseEntity<?> response = controller.complete("not-a-number", "USER,ADMIN", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).markBootstrapped(isNull());
    }

    @Test
    @DisplayName("POST /complete idempotency: second call returns same view without re-calling save")
    void postCompleteIdempotent() {
        CeStatusView stableView = new CeStatusView(true, Instant.parse("2026-04-22T10:00:00Z"), "v1", false);
        when(service.markBootstrapped(eq(42L))).thenReturn(stableView);

        ResponseEntity<?> first = controller.complete("42", "ADMIN", request);
        ResponseEntity<?> second = controller.complete("42", "ADMIN", request);

        assertThat(first.getBody()).isEqualTo(stableView);
        assertThat(second.getBody()).isEqualTo(stableView);
        // Service is called twice; its internal idempotency (tested in CeInstallStateServiceTest)
        // guarantees both calls return the same timestamp.
        verify(service, org.mockito.Mockito.times(2)).markBootstrapped(42L);
    }

    @Test
    @DisplayName("PUT /registration { open:true } by ADMIN delegates to service and returns view")
    void putRegistrationAsAdminPersists() {
        CeStatusView reopened = new CeStatusView(true, Instant.now(), "v1", true);
        when(service.setRegistrationOpen(eq(true))).thenReturn(reopened);

        ResponseEntity<?> response = controller.setRegistration(
                java.util.Map.of("open", true), "42", "ADMIN", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(reopened);
        verify(service).setRegistrationOpen(true);
    }

    @Test
    @DisplayName("PUT /registration by non-admin returns 403 - admin-only mutation")
    void putRegistrationAsNonAdminDenied() {
        ResponseEntity<?> response = controller.setRegistration(
                java.util.Map.of("open", true), "42", "USER", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).setRegistrationOpen(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("PUT /registration with missing 'open' field returns 400")
    void putRegistrationWithInvalidBodyReturns400() {
        ResponseEntity<?> response = controller.setRegistration(
                java.util.Map.of("foo", "bar"), "42", "ADMIN", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).setRegistrationOpen(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("PUT /registration emits audit event on actual state transition (closed → open)")
    void putRegistrationEmitsAuditOnStateChange() {
        // prev: closed; new: open → emit CE_REGISTRATION_OPENED
        when(service.getStatus()).thenReturn(new CeStatusView(true, Instant.now(), "v1", false));
        when(service.setRegistrationOpen(eq(true)))
                .thenReturn(new CeStatusView(true, Instant.now(), "v1", true));

        controller.setRegistration(java.util.Map.of("open", true), "42", "ADMIN", request);

        verify(auditLogger).eventFromRequest(eq(AuditEventTypes.CE_REGISTRATION_OPENED), eq(request));
        verify(eventBuilder).write();
    }

    @Test
    @DisplayName("PUT /registration does NOT emit audit event on no-op toggle (same value)")
    void putRegistrationDoesNotEmitAuditOnNoop() {
        // prev: closed; new: closed (toggle redundant) → no audit emit
        when(service.getStatus()).thenReturn(new CeStatusView(true, Instant.now(), "v1", false));
        when(service.setRegistrationOpen(eq(false)))
                .thenReturn(new CeStatusView(true, Instant.now(), "v1", false));

        controller.setRegistration(java.util.Map.of("open", false), "42", "ADMIN", request);

        verify(auditLogger, never()).eventFromRequest(org.mockito.ArgumentMatchers.anyString(), any());
    }

    @Test
    @DisplayName("POST /complete emits CE_REGISTRATION_CLOSED audit on first bootstrap (wizard completion)")
    void postCompleteEmitsAuditOnFirstBootstrap() {
        // prev: not bootstrapped; new: bootstrapped → emit close audit with reason=wizard_complete
        when(service.getStatus()).thenReturn(new CeStatusView(false, null, "v1", true));
        when(service.markBootstrapped(42L))
                .thenReturn(new CeStatusView(true, Instant.now(), "v1", false));

        controller.complete("42", "USER,ADMIN", request);

        verify(auditLogger).eventFromRequest(eq(AuditEventTypes.CE_REGISTRATION_CLOSED), eq(request));
        verify(eventBuilder).detail(eq("reason"), eq("wizard_complete"));
        verify(eventBuilder).write();
    }
}
