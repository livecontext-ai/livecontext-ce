package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ChangelogSeenService;
import com.apimarketplace.auth.service.ChangelogSeenService.ChangelogState;
import com.apimarketplace.auth.service.ChangelogSeenService.SeenOutcome;
import com.apimarketplace.auth.web.ChangelogController.ChangelogStateResponse;
import com.apimarketplace.auth.web.ChangelogController.SeenRequest;
import com.apimarketplace.auth.web.ChangelogController.SeenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wire contract of the per-user changelog state.
 *
 * <p>What the client reads back is the whole policy input: a null {@code seenKey} means
 * "announce", the same key means "stay quiet", and {@code accountCreatedAt} is what lets a fresh
 * signup be sealed instead of interrupted. The JSON SHAPE of those nulls is pinned in
 * {@link ChangelogControllerRoutingTest}, which serializes through a real message converter; this
 * class pins the values themselves.
 */
class ChangelogControllerTest {

    private ChangelogSeenService service;
    private ChangelogController controller;

    @BeforeEach
    void setUp() {
        service = mock(ChangelogSeenService.class);
        controller = new ChangelogController(service);
    }

    @Test
    @DisplayName("state: a missing user header is unauthorized, and the service is never called")
    void stateWithoutUserHeaderIsUnauthorized() {
        ResponseEntity<ChangelogStateResponse> response = controller.getState(null, "2026-09-entry");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(service, never()).state(anyLong(), anyString());
    }

    @Test
    @DisplayName("state: a blank or non-numeric user header is unauthorized, never user 0")
    void stateWithNonNumericUserHeaderIsUnauthorized() {
        assertThat(controller.getState("   ", "2026-09-entry").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // "not-a-number" must not be coerced into a user id: the gateway and the CE filter both
        // inject a numeric id, so anything else is an unauthenticated caller.
        assertThat(controller.getState("not-a-number", "2026-09-entry").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(service, never()).state(anyLong(), anyString());
    }

    @Test
    @DisplayName("state: the acknowledged key and the account instant are reported as-is")
    void stateSerializesTheAcknowledgement() {
        when(service.state(42L, "2026-09-entry"))
                .thenReturn(new ChangelogState(true, "2026-09-entry", false));

        ChangelogStateResponse body = controller.getState("42", "2026-09-entry").getBody();

        assertThat(body).isNotNull();
        assertThat(body.enabled()).isTrue();
        assertThat(body.seenKey()).isEqualTo("2026-09-entry");
        assertThat(body.seal()).isFalse();
    }

    @Test
    @DisplayName("state: a never-acknowledged user reports a null key, which is what makes the client announce")
    void stateReportsNullsForANeverAcknowledgedUser() {
        when(service.state(42L, "2026-09-entry")).thenReturn(new ChangelogState(true, null, false));

        ChangelogStateResponse body = controller.getState("42", "2026-09-entry").getBody();

        assertThat(body).isNotNull();
        assertThat(body.seenKey()).isNull();
        assertThat(body.seal()).isFalse();
    }

    @Test
    @DisplayName("state: a disabled deployment answers enabled=false, which is what silences the client")
    void disabledStateIsReported() {
        when(service.state(42L, "2026-09-entry")).thenReturn(new ChangelogState(false, null, false));

        assertThat(controller.getState("42", "2026-09-entry").getBody().enabled()).isFalse();
    }

    @Test
    @DisplayName("state: the entry key travels to the service, which owns the seal decision")
    void entryKeyIsPassedThrough() {
        when(service.state(42L, "2026-09-entry")).thenReturn(new ChangelogState(true, null, true));

        // The server decides the seal because only it knows when this install first served the
        // entry; the controller must not invent that answer or drop the key on the way.
        assertThat(controller.getState("42", "2026-09-entry").getBody().seal()).isTrue();
        verify(service).state(42L, "2026-09-entry");
    }

    @Test
    @DisplayName("state: a caller with no entry passes null through rather than a placeholder")
    void missingEntryKeyIsPassedAsNull() {
        when(service.state(42L, null)).thenReturn(new ChangelogState(true, null, false));

        assertThat(controller.getState("42", null).getBody().seal()).isFalse();
        verify(service).state(42L, null);
    }

    @Test
    @DisplayName("seen: a valid key is acknowledged and echoed back")
    void seenAcknowledgesAValidKey() {
        when(service.markSeen(42L, "2026-09-entry")).thenReturn(SeenOutcome.RECORDED);

        ResponseEntity<?> response = controller.markSeen("42", new SeenRequest("2026-09-entry"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new SeenResponse(true, "2026-09-entry"));
        verify(service).markSeen(42L, "2026-09-entry");
    }

    @Test
    @DisplayName("seen: a malformed key is rejected before it can reach the row, and is not echoed")
    void seenRejectsAMalformedKey() {
        ResponseEntity<?> response = controller.markSeen("42", new SeenRequest("<script>"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Set<String> keys = ((Map<?, ?>) response.getBody()).keySet().stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        assertThat(keys).contains("error").doesNotContain("received");
        verify(service, never()).markSeen(anyLong(), anyString());
    }

    @Test
    @DisplayName("seen: a missing body or key is rejected, not stored as null")
    void seenRejectsAMissingKey() {
        assertThat(controller.markSeen("42", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.markSeen("42", new SeenRequest(null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).markSeen(anyLong(), anyString());
    }

    @Test
    @DisplayName("seen: a missing user header is unauthorized, and nothing is written")
    void seenWithoutUserHeaderIsUnauthorized() {
        ResponseEntity<?> response = controller.markSeen(null, new SeenRequest("2026-09-entry"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(service, never()).markSeen(anyLong(), anyString());
    }

    @Test
    @DisplayName("seen: a disabled deployment answers 200 with enabled=false rather than an error")
    void seenOnADisabledDeploymentIsNotAnError() {
        when(service.markSeen(42L, "2026-09-entry")).thenReturn(SeenOutcome.DISABLED);

        ResponseEntity<?> response = controller.markSeen("42", new SeenRequest("2026-09-entry"));

        // Not an error: the client asked for something this deployment does not do. It reads
        // `enabled` and stops announcing, which is what a switched-off feature looks like.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new SeenResponse(false, null));
    }

    @Test
    @DisplayName("seen for a deleted user answers 404, not 500 and not 401")
    void changelogSeenForDeletedUserAnswers404() {
        // Prod 2026-09-22: 5 x HTTP 500 from a session whose account no longer existed. 404 and not
        // 401: the web client turns a 401 into a token refresh and then a forced login redirect,
        // and a background acknowledgement must not be what logs someone out.
        when(service.markSeen(404L, "2026-09-entry")).thenReturn(SeenOutcome.UNKNOWN_USER);

        ResponseEntity<?> response = controller.markSeen("404", new SeenRequest("2026-09-entry"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "user not found"));
        verify(service).markSeen(404L, "2026-09-entry");
    }
}
