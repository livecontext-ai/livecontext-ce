package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.IpHashService;
import com.apimarketplace.auth.service.RequestAuditContext;
import com.apimarketplace.auth.service.SquatRecoveryTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SquatRecoveryController")
class SquatRecoveryControllerTest {

    @Mock private SquatRecoveryTokenService tokenService;
    @Mock private CeLinkService ceLinkService;
    @Mock private IpHashService ipHashService;
    @Mock private HttpServletRequest httpRequest;

    private SquatRecoveryController controller;

    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long VICTIM_ID = 42L;

    @BeforeEach
    void setUp() {
        controller = new SquatRecoveryController(tokenService, ceLinkService, ipHashService);
    }

    @Test
    @DisplayName("valid token → adminRevoke with reason=SQUAT_RECOVERY → 204")
    void consume_valid_token_revokes() {
        when(tokenService.peek("the-token"))
                .thenReturn(Optional.of(new SquatRecoveryTokenService.TokenBinding(INSTALL, VICTIM_ID)));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(ceLinkService.adminRevoke(eq(INSTALL), eq(CeLink.RevokeReason.SQUAT_RECOVERY),
                eq(VICTIM_ID), any(RequestAuditContext.class))).thenReturn(true);

        ResponseEntity<Void> response = controller.consume("the-token", httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(ceLinkService).adminRevoke(eq(INSTALL), eq(CeLink.RevokeReason.SQUAT_RECOVERY),
                eq(VICTIM_ID), any(RequestAuditContext.class));
    }

    @Test
    @DisplayName("unknownOrExpiredOrConsumedToken - all 3 collapse to a single 404 shape (no oracle)")
    void unknown_token_returns_404() {
        when(tokenService.peek("bogus")).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.consume("bogus", httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(ceLinkService);
    }

    @Test
    @DisplayName("revokeThrowsReturns503AndPreservesToken - transient DB failure must NOT burn the recovery link")
    void revoke_throws_preserves_token() {
        when(tokenService.peek("the-token"))
                .thenReturn(Optional.of(new SquatRecoveryTokenService.TokenBinding(INSTALL, VICTIM_ID)));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(ceLinkService.adminRevoke(eq(INSTALL), eq(CeLink.RevokeReason.SQUAT_RECOVERY),
                eq(VICTIM_ID), any(RequestAuditContext.class)))
                .thenThrow(new RuntimeException("DB hiccup"));

        ResponseEntity<Void> response = controller.consume("the-token", httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // Crucial: token NOT invalidated - victim can click the same link again.
        verify(tokenService, org.mockito.Mockito.never()).invalidate(any());
    }

    @Test
    @DisplayName("successPathInvalidatesTokenOnce - single-use enforced via explicit delete after revoke")
    void success_invalidates_token() {
        when(tokenService.peek("the-token"))
                .thenReturn(Optional.of(new SquatRecoveryTokenService.TokenBinding(INSTALL, VICTIM_ID)));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(ceLinkService.adminRevoke(eq(INSTALL), eq(CeLink.RevokeReason.SQUAT_RECOVERY),
                eq(VICTIM_ID), any(RequestAuditContext.class))).thenReturn(true);

        controller.consume("the-token", httpRequest);

        verify(tokenService).invalidate("the-token");
    }

    @Test
    @DisplayName("validTokenButInstallIdGone - 404 (race window where DB purge ran between mint and consume)")
    void valid_token_but_install_gone_returns_404() {
        when(tokenService.peek("the-token"))
                .thenReturn(Optional.of(new SquatRecoveryTokenService.TokenBinding(INSTALL, VICTIM_ID)));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(ceLinkService.adminRevoke(eq(INSTALL), eq(CeLink.RevokeReason.SQUAT_RECOVERY),
                eq(VICTIM_ID), any(RequestAuditContext.class))).thenReturn(false);

        ResponseEntity<Void> response = controller.consume("the-token", httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
