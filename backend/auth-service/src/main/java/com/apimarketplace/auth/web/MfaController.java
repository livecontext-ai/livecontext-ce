package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.MfaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Two-factor status of the signed-in account, read by the settings Security tab.
 *
 * <p>Enrolling and removing an authenticator app happen on Keycloak pages
 * (application-initiated actions), never through this service, so no secret or code
 * ever transits here. The one write: reading the status removes recovery codes left
 * behind by the removal of the last app (see {@link MfaService#getStatus}), since
 * Keycloak would otherwise demand one at every sign-in.
 */
@RestController
@RequestMapping("/api/me/mfa")
public class MfaController {

    private static final Logger log = LoggerFactory.getLogger(MfaController.class);

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @GetMapping
    public ResponseEntity<?> getStatus(@RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        try {
            return ResponseEntity.ok(mfaService.getStatus(userId));
        } catch (MfaService.MfaStatusUnavailableException e) {
            log.warn("Two-factor status unavailable for userId={}: {}", userId,
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Two-factor status is temporarily unavailable"));
        }
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
