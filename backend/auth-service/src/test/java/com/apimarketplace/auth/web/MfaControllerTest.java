package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.MfaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MfaController")
class MfaControllerTest {

    @Mock private MfaService mfaService;
    @InjectMocks private MfaController controller;

    @Test
    @DisplayName("returns the caller's two-factor status")
    void returnsStatus() {
        MfaService.MfaStatus status = new MfaService.MfaStatus(true, false, List.of(), false, false, null);
        when(mfaService.getStatus(42L)).thenReturn(status);

        ResponseEntity<?> response = controller.getStatus("42");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(status);
    }

    @Test
    @DisplayName("refuses a request without a user id")
    void unauthorizedWithoutUser() {
        assertThat(controller.getStatus(null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.getStatus("abc").getStatusCode().value()).isEqualTo(401);
        verify(mfaService, never()).getStatus(any());
    }

    @Test
    @DisplayName("answers 503 rather than a guessed status when Keycloak cannot be read")
    void unavailableWhenKeycloakDown() {
        when(mfaService.getStatus(42L))
                .thenThrow(new MfaService.MfaStatusUnavailableException("down", new IllegalStateException("503")));

        assertThat(controller.getStatus("42").getStatusCode().value()).isEqualTo(503);
    }
}
