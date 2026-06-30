package com.apimarketplace.publication.controller;

import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.dto.HighlightOrderRequest;
import com.apimarketplace.publication.service.PublicationHighlightService;
import com.apimarketplace.publication.service.PublicationHighlightService.HighlightedPublication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationHighlightAdminController")
class PublicationHighlightAdminControllerTest {

    @Mock
    private PublicationHighlightService highlightService;

    private GatewayFilterProperties gatewayProps;
    private PublicationHighlightAdminController controller;

    @BeforeEach
    void setUp() {
        gatewayProps = new GatewayFilterProperties();
        gatewayProps.setVerificationEnabled(true);
        controller = new PublicationHighlightAdminController(highlightService, gatewayProps);
    }

    @Test
    @DisplayName("Boot fail-fast refuses to start when gateway HMAC verification is disabled")
    void bootFailsWhenHmacOff() {
        GatewayFilterProperties weakProps = new GatewayFilterProperties();
        weakProps.setVerificationEnabled(false);
        PublicationHighlightAdminController weak =
                new PublicationHighlightAdminController(highlightService, weakProps);

        assertThatThrownBy(weak::assertGatewaySecretEnforced)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.filter.verification-enabled=true");
    }

    @Test
    @DisplayName("Boot succeeds when HMAC is on")
    void bootOkWhenHmacOn() {
        controller.assertGatewaySecretEnforced(); // no throw
    }

    @Test
    @DisplayName("GET refuses non-admin role with 403")
    void getRefusesNonAdmin() {
        ResponseEntity<?> r = controller.listHighlights("USER", DisplayMode.APPLICATION);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(highlightService);
    }

    @Test
    @DisplayName("PUT refuses non-admin role with 403 - service is never called, no DB write")
    void putRefusesNonAdmin() {
        ResponseEntity<?> r = controller.replaceHighlights(
                "42", "USER", DisplayMode.APPLICATION,
                new HighlightOrderRequest(List.of(UUID.randomUUID())));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(highlightService, never()).replaceHighlights(any(), anyList(), anyString());
    }

    @Test
    @DisplayName("PUT refuses missing X-User-Roles header (defaults to USER) with 403")
    void putRefusesEmptyRoles() {
        ResponseEntity<?> r = controller.replaceHighlights(
                "42", "", DisplayMode.APPLICATION,
                new HighlightOrderRequest(List.of()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET admin returns the curated list for the requested displayMode")
    void getAdminReturnsList() {
        when(highlightService.listAdminHighlights(DisplayMode.APPLICATION))
                .thenReturn(List.of());

        ResponseEntity<?> r = controller.listHighlights("ADMIN,USER", DisplayMode.APPLICATION);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(highlightService).listAdminHighlights(DisplayMode.APPLICATION);
    }

    @Test
    @DisplayName("PUT delegates to the service with admin id from X-User-ID")
    void putAcceptsAdmin() {
        UUID id = UUID.randomUUID();
        ResponseEntity<?> r = controller.replaceHighlights(
                "admin-99", "ADMIN", DisplayMode.APPLICATION,
                new HighlightOrderRequest(List.of(id)));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(highlightService).replaceHighlights(DisplayMode.APPLICATION, List.of(id), "admin-99");
    }

    @Test
    @DisplayName("PUT translates IllegalArgumentException from service into 400 with stable error code")
    void putBubblesValidationAs400() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        doThrow(new IllegalArgumentException("INVALID_OR_INACCESSIBLE_PUBLICATIONS"))
                .when(highlightService)
                .replaceHighlights(any(), anyList(), anyString());

        ResponseEntity<?> r = controller.replaceHighlights(
                "admin", "ADMIN", DisplayMode.APPLICATION,
                new HighlightOrderRequest(List.of(a, b)));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().toString()).contains("INVALID_OR_INACCESSIBLE_PUBLICATIONS");
    }
}
