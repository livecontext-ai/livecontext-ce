package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.agent.cloud.CloudLlmSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * CE-side REST API for managing the cloud account link.
 * Allows CE admins to link/unlink their LiveContext cloud account via OAuth.
 *
 * Only active when marketplace.mode=remote (CE instances).
 */
@RestController
@RequestMapping("/api/cloud-link")
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class CloudLinkController {

    private static final Logger logger = LoggerFactory.getLogger(CloudLinkController.class);

    private final CloudLinkService cloudLinkService;
    private final String frontendUrl;

    public CloudLinkController(CloudLinkService cloudLinkService,
                               @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.cloudLinkService = cloudLinkService;
        this.frontendUrl = trimTrailingSlash(frontendUrl);
    }

    /**
     * GET /api/cloud-link/status
     * Returns the current cloud account link status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestHeader("X-User-ID") Long tenantId) {
        return ResponseEntity.ok(cloudLinkService.getLinkStatus(tenantId));
    }

    @GetMapping("/llm-source")
    public ResponseEntity<Map<String, Object>> getLlmSource(
            @RequestHeader("X-User-ID") Long tenantId) {
        return ResponseEntity.ok(Map.of("source", cloudLinkService.getLlmSource(tenantId).name()));
    }

    @GetMapping("/catalog-source")
    public ResponseEntity<Map<String, Object>> getCatalogSource(
            @RequestHeader("X-User-ID") Long tenantId) {
        return ResponseEntity.ok(Map.of("source", cloudLinkService.getCatalogSource(tenantId).name()));
    }

    /**
     * GET /api/cloud-link/usage-summary
     * Mirrors the bound cloud account's credit usage summary so a CLOUD-linked CE can show
     * the relay spend - which is metered against the cloud account, not the CE's local ledger.
     * Returns {@code {"available": false}} when not linked/registered or the cloud is
     * unreachable, so the CE usage view falls back to its (empty) local ledger.
     */
    @GetMapping("/usage-summary")
    public ResponseEntity<Map<String, Object>> usageSummary(
            @RequestHeader("X-User-ID") Long tenantId) {
        Map<String, Object> summary = cloudLinkService.fetchCloudUsageSummary(tenantId);
        if (summary == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/cloud-link/usage-history
     * Mirrors ONLY this CE's relay rows from the bound cloud account's usage history (the
     * cloud-side query is scoped to CE_LLM_RELAY in {@link CloudLinkService}); the cloud
     * account's other ledger activity is never exposed to the CE. {@code {"available": false}}
     * when not linked/registered or unreachable → the CE falls back to its local history. Any
     * {@code sourceType} query param is ignored on purpose: a CE only ever views its relay slice.
     */
    @GetMapping("/usage-history")
    public ResponseEntity<Map<String, Object>> usageHistory(
            @RequestHeader("X-User-ID") Long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        Map<String, Object> history = cloudLinkService.fetchCloudUsageHistory(tenantId, page, size);
        if (history == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return ResponseEntity.ok(history);
    }

    /**
     * GET /api/cloud-link/reward-stats
     * Mirrors the bound cloud account's referral code + invite progress so a
     * CLOUD-linked CE can show "invite friends". {@code {"available": false}} when
     * not linked/registered or the cloud is unreachable, so the CE shows the
     * connect-first state.
     */
    @GetMapping("/reward-stats")
    public ResponseEntity<Map<String, Object>> rewardStats(
            @RequestHeader("X-User-ID") Long tenantId) {
        Map<String, Object> stats = cloudLinkService.fetchCloudRewardStats(tenantId);
        if (stats == null) {
            return ResponseEntity.ok(Map.of("available", false));
        }
        return ResponseEntity.ok(stats);
    }

    /**
     * POST /api/cloud-link/redeem
     * Redeems a reward code on the bound cloud account (the referee acts as their cloud
     * user). Relays the cloud's status and typed body. Body: { "code": "..." }.
     */
    @PostMapping("/redeem")
    public ResponseEntity<Map<String, Object>> redeemReward(
            @RequestHeader("X-User-ID") Long tenantId,
            @RequestBody(required = false) Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        CloudLinkService.CloudRedeemResult result = cloudLinkService.redeemRewardOnCloud(tenantId, code);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PutMapping("/llm-source")
    public ResponseEntity<Map<String, Object>> setLlmSource(
            @RequestHeader("X-User-ID") Long tenantId,
            @RequestBody Map<String, String> body) {
        CloudLlmSource source = parseRequestedSource(body == null ? null : body.get("source"));
        if (source == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_LLM_SOURCE"));
        }
        try {
            CloudLlmSource saved = cloudLinkService.setLlmSource(tenantId, source);
            return ResponseEntity.ok(Map.of("source", saved.name()));
        } catch (CloudLinkService.CloudAccountNotLinkedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "CLOUD_LINK_REQUIRED"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "CLOUD_LINK_NOT_READY"));
        }
    }

    @PutMapping("/catalog-source")
    public ResponseEntity<Map<String, Object>> setCatalogSource(
            @RequestHeader("X-User-ID") Long tenantId,
            @RequestBody Map<String, String> body) {
        CloudLlmSource source = parseRequestedSource(body == null ? null : body.get("source"));
        if (source == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_CATALOG_SOURCE"));
        }
        try {
            CloudLlmSource saved = cloudLinkService.setCatalogSource(tenantId, source);
            return ResponseEntity.ok(Map.of("source", saved.name()));
        } catch (CloudLinkService.CloudAccountNotLinkedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "CLOUD_LINK_REQUIRED"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "CLOUD_LINK_NOT_READY"));
        }
    }

    private static CloudLlmSource parseRequestedSource(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CloudLlmSource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * GET /api/cloud-link/auth-url
     * Generates the Keycloak authorization URL with PKCE for OAuth flow.
     */
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl(
            @RequestHeader("X-User-ID") Long tenantId,
            @RequestParam(value = "returnPath", required = false) String returnPath) {
        return ResponseEntity.ok(cloudLinkService.generateAuthUrl(tenantId, returnPath));
    }

    /**
     * GET /api/cloud-link/callback
     * Receives the OAuth authorization code on the backend so the code never
     * appears in the frontend URL. The frontend later completes the link by
     * posting the state only from an authenticated session.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        HttpHeaders headers = callbackSecurityHeaders();
        String frontendReturnPath;
        try {
            frontendReturnPath = cloudLinkService.receiveCallback(code, state);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(headers, HttpStatus.BAD_REQUEST);
        }
        String redirect = frontendUrl + frontendReturnPath
                + "?cloud_link_callback=1&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
        headers.set(HttpHeaders.LOCATION, redirect);
        return new ResponseEntity<>(headers, HttpStatus.SEE_OTHER);
    }

    /**
     * POST /api/cloud-link/connect
     * Completes the cloud link from the backend-stored callback code.
     * Body: { "state": "..." }
     */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestHeader("X-User-ID") Long tenantId,
            @RequestBody Map<String, String> body) {

        String state = body.get("state");

        if (state == null || state.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "state is required"));
        }

        try {
            cloudLinkService.linkAccount(tenantId, state);
            Map<String, Object> status = cloudLinkService.getLinkStatus(tenantId);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // A TLS-intercepting antivirus/proxy (no trusted CA) surfaces here as a
            // PKIX failure on the token exchange. Report it DISTINCTLY (not the
            // generic 500 that the UI mislabels as "Invalid or expired state"), so
            // the CE setup wizard can offer the one-click "trust this proxy CA" flow.
            if (isTlsTrustFailure(e)) {
                logger.warn("Cloud link blocked by a TLS-intercepting proxy/AV (untrusted CA) "
                        + "for tenant {}: {}", tenantId, e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "error", "cloud_tls_untrusted",
                                "message", "The connection to the cloud is being intercepted by an "
                                        + "antivirus or corporate proxy whose certificate is not "
                                        + "trusted. Trust it to continue."));
            }
            logger.error("Failed to link cloud account for tenant {}: {}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to link cloud account: " + e.getMessage()));
        }
    }

    /**
     * True when the throwable chain is a TLS trust failure - the signature of an
     * intercepting proxy/AV whose root CA the JVM does not trust (PKIX path
     * building failed / unable to find valid certification path / SSLHandshake).
     */
    static boolean isTlsTrustFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof javax.net.ssl.SSLException
                    || c instanceof java.security.cert.CertificateException
                    || c instanceof java.security.cert.CertPathBuilderException) {
                return true;
            }
            String msg = c.getMessage();
            if (msg != null
                    && (msg.contains("PKIX path building failed")
                        || msg.contains("unable to find valid certification path")
                        || msg.contains("SunCertPathBuilderException"))) {
                return true;
            }
            if (c.getCause() == c) {
                break; // defensive: self-referential cause
            }
        }
        return false;
    }

    /**
     * DELETE /api/cloud-link/disconnect
     * Removes the cloud account link.
     */
    @DeleteMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(
            @RequestHeader("X-User-ID") Long tenantId) {
        cloudLinkService.unlinkAccount(tenantId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:3000";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static HttpHeaders callbackSecurityHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        headers.set("Referrer-Policy", "no-referrer");
        return headers;
    }
}
