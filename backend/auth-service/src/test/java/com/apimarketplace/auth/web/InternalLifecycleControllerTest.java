package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.LifecycleEventRequest;
import com.apimarketplace.auth.lifecycle.ExternalLifecycleEventService;
import com.apimarketplace.auth.lifecycle.LifecycleEmailService;
import com.apimarketplace.auth.lifecycle.UserLifecycleContextService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("InternalLifecycleController - activation signal and events from other services")
class InternalLifecycleControllerTest {

    private final UserLifecycleContextService service = mock(UserLifecycleContextService.class);
    private final ExternalLifecycleEventService events = mock(ExternalLifecycleEventService.class);
    private final InternalLifecycleController controller = new InternalLifecycleController(service, events);

    private static final Map<String, Object> TROPHY = Map.of("kind", "popularity", "badge_code", "popularity_1",
            "tier", "BRONZE");

    @Test
    @DisplayName("a known user is recorded and answered 204, whether or not it was the first time")
    void knownUserRecorded() {
        when(service.resolveUserId("7")).thenReturn(Optional.of(7L));
        when(service.recordActivation(7L)).thenReturn(false);

        ResponseEntity<Void> response = controller.activation("7");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).recordActivation(7L);
    }

    @Test
    @DisplayName("an unknown or missing user is 404 and records nothing")
    void unknownUser() {
        when(service.resolveUserId(null)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.activation(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).recordActivation(anyLong());
    }

    @Test
    @DisplayName("events: an accepted event answers 202")
    void eventAccepted() {
        when(service.resolveUserId("7")).thenReturn(Optional.of(7L));
        when(events.accept(7L, "badge.unlocked", TROPHY)).thenReturn(LifecycleEmailService.Dispatch.QUEUED);

        ResponseEntity<Void> response = controller.event("7", new LifecycleEventRequest("badge.unlocked", TROPHY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("Regression (month consumed by the kill switch): lifecycle emails off answers 204, never 202, so the sender does not record a send")
    void eventInactiveIsNoContent() {
        when(service.resolveUserId("7")).thenReturn(Optional.of(7L));
        when(events.accept(anyLong(), anyString(), any())).thenReturn(LifecycleEmailService.Dispatch.INACTIVE);

        assertThat(controller.event("7", new LifecycleEventRequest("badge.unlocked", TROPHY)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("events: a full Resend queue answers 503 so the sender retries later instead of losing it")
    void eventBusyIs503() {
        when(service.resolveUserId("7")).thenReturn(Optional.of(7L));
        when(events.accept(anyLong(), anyString(), any())).thenReturn(LifecycleEmailService.Dispatch.BUSY);

        assertThat(controller.event("7", new LifecycleEventRequest("recap.monthly", Map.of())).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("events: an event or payload off the allow-list answers 400")
    void eventOffListIs400() {
        when(service.resolveUserId("7")).thenReturn(Optional.of(7L));
        when(events.accept(anyLong(), any(), any())).thenThrow(new IllegalArgumentException("nope"));

        assertThat(controller.event("7", new LifecycleEventRequest("credits.exhausted", Map.of())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.event("7", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("events: an unknown user answers 404 and nothing is sent")
    void eventUnknownUserIs404() {
        when(service.resolveUserId("ghost")).thenReturn(Optional.empty());

        assertThat(controller.event("ghost", new LifecycleEventRequest("badge.unlocked", TROPHY)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(events);
    }
}
