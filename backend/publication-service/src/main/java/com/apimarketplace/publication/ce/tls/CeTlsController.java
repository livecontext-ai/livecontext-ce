package com.apimarketplace.publication.ce.tls;

import com.apimarketplace.common.web.AdminRoleGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CE-only, admin-only. Lets the self-hosted admin diagnose and trust a
 * TLS-intercepting antivirus / corporate-proxy CA so cloud features work.
 *
 * <ul>
 *   <li>{@code GET  /api/ce/tls/probe}   - detect interception + read the proxy CA.</li>
 *   <li>{@code POST /api/ce/tls/trust}   - trust a CA PEM (immediate, persisted).</li>
 *   <li>{@code GET  /api/ce/tls/trusted} - list currently trusted custom CAs.</li>
 * </ul>
 *
 * <p>Admin enforcement uses the gateway-injected {@code X-User-Roles} header via
 * {@link AdminRoleGuard} - the same pattern as {@code CeInstallController}
 * (auth-service security is {@code permitAll}, so {@code @PreAuthorize} would
 * not fire). Gated to {@code auth.mode=embedded} so it never exists in cloud.
 */
@RestController
@RequestMapping("/api/ce/tls")
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class CeTlsController {

    private static final Logger log = LoggerFactory.getLogger(CeTlsController.class);

    private final CeTlsProbeService probeService;
    private final CeCustomTrustStore trustStore;

    public CeTlsController(CeTlsProbeService probeService, CeCustomTrustStore trustStore) {
        this.probeService = probeService;
        this.trustStore = trustStore;
    }

    /**
     * Probes the CONFIGURED cloud host only (no caller-supplied target) - this is
     * a diagnostic, not a general fetch, so there is no SSRF surface.
     */
    @GetMapping("/probe")
    public ResponseEntity<?> probe(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return ResponseEntity.ok(probeService.probe());
    }

    @GetMapping("/trusted")
    public ResponseEntity<?> trusted(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return ResponseEntity.ok(Map.of("trusted", trustStore.listTrustedCas()));
    }

    @PostMapping("/trust")
    public ResponseEntity<?> trust(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        Object pemValue = body == null ? null : body.get("pem");
        if (!(pemValue instanceof String pem) || pem.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_request",
                    "message", "Body must contain a non-empty 'pem' certificate field"));
        }

        try {
            CeCustomTrustStore.TrustedCa ca = trustStore.addTrustedCa(pem);
            log.info("[CE-TLS] Admin trusted interceptor CA subject='{}' sha256={}",
                    ca.subject(), ca.sha256());
            return ResponseEntity.ok(Map.of("trusted", true,
                    "subject", ca.subject(),
                    "issuer", ca.issuer(),
                    "sha256", ca.sha256()));
        } catch (java.security.cert.CertificateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_certificate",
                    "message", "The provided value is not a valid X.509 certificate"));
        } catch (Exception e) {
            log.error("[CE-TLS] Failed to persist trusted CA", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "trust_failed",
                    "message", "Could not store the certificate"));
        }
    }
}
