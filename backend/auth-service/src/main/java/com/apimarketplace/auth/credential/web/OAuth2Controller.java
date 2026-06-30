package com.apimarketplace.auth.credential.web;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.auth.credential.domain.OAuth2Models.*;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.OAuth2Service;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * REST controller for OAuth2 authentication flows.
 *
 * <p>Uses centralized infrastructure:
 * <ul>
 *   <li>{@link TenantResolver} for X-User-ID header extraction</li>
 *   <li>GlobalExceptionHandler for error responses</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/credentials/oauth2")
public class OAuth2Controller {

    private static final Logger log = LoggerFactory.getLogger(OAuth2Controller.class);

    private final OAuth2Service oAuth2Service;
    private final TenantResolver tenantResolver;
    private final InternalCredentialService internalCredentialService;

    /**
     * Integrations allowed to mint a browser Picker token (lowercased, both display name and
     * iconSlug forms). Restricts the token exposure to Google Workspace pickers - the only place
     * a drive.file browser token is needed.
     */
    private static final java.util.Set<String> PICKER_INTEGRATIONS = java.util.Set.of(
            "google sheets", "google docs", "google slides", "google drive",
            "googlesheets", "googledocs", "googleslides", "googledrive");

    @org.springframework.beans.factory.annotation.Value("${oauth2.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    public OAuth2Controller(OAuth2Service oAuth2Service, TenantResolver tenantResolver,
                            InternalCredentialService internalCredentialService) {
        this.oAuth2Service = oAuth2Service;
        this.tenantResolver = tenantResolver;
        this.internalCredentialService = internalCredentialService;
    }

    /**
     * POST /api/credentials/oauth2/initiate - Start OAuth2 flow.
     */
    @PostMapping("/initiate")
    public ResponseEntity<OAuth2InitiateResponse> initiate(
            HttpServletRequest httpRequest,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestBody OAuth2InitiateRequest request) {

        String userId = tenantResolver.resolve(httpRequest);
        // PR19 - capture the active workspace at initiate-time so the
        // credential lands in the correct scope on callback (state survives
        // the OAuth bounce through the provider).
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        // App UI locale (next-intl) so the provider renders its consent screen + scope
        // descriptions in the user's language. Optional - absent/blank falls back to the
        // provider's account/browser default.
        String uiLocale = resolveUiLocale(locale);
        log.info("Initiating OAuth2 flow for user {} (org={}) with template {} (locale={})",
                userId, organizationId, request.credentialTemplateId(), uiLocale);

        if (request.credentialTemplateId() == null || request.credentialTemplateId().isBlank()) {
            throw new IllegalArgumentException("Credential template ID is required");
        }

        OAuth2InitiateResponse response = oAuth2Service.initiate(request, userId, organizationId, uiLocale);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/credentials/oauth2/initiate-simple - Simplified OAuth2 initiation.
     */
    @PostMapping("/initiate-simple")
    public ResponseEntity<OAuth2InitiateResponse> initiateSimple(
            HttpServletRequest httpRequest,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestBody OAuth2SimpleInitiateRequest request) {

        String userId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        String uiLocale = resolveUiLocale(locale);
        log.info("Initiating simple OAuth2 flow for user {} (org={}) with template {} (locale={})",
                userId, organizationId, request.credentialTemplateId(), uiLocale);

        if (request.credentialTemplateId() == null || request.credentialTemplateId().isBlank()) {
            throw new IllegalArgumentException("Credential template ID is required");
        }

        OAuth2InitiateResponse response =
                oAuth2Service.initiateSimple(request, userId, organizationId, uiLocale);
        return ResponseEntity.ok(response);
    }

    /**
     * Normalise the explicit {@code ?locale=} app-locale param (the next-intl locale the frontend
     * forwards) into a short language tag for the consent screen (e.g. {@code fr-FR} -> {@code fr}).
     * Returns {@code null} when the param is absent or not a plausible locale - the engine then
     * drops the locale param and the provider uses its own default.
     *
     * <p>We deliberately do NOT fall back to the {@code Accept-Language} header: that is the
     * BROWSER language, and the consent screen must follow the app's OWN locale (a French-browser
     * user on the {@code /en} app must see an English consent screen, per the i18n rule). The
     * {@code [A-Za-z]{2,8}} guard + length cap also keep arbitrary input out of the authorize URL.
     */
    private String resolveUiLocale(String localeParam) {
        if (localeParam == null || localeParam.isBlank()) {
            return null;
        }
        // Keep only the primary subtag (fr-FR -> fr), reject anything non-locale-ish.
        String primary = localeParam.trim().split("[,;]")[0].trim().split("-")[0];
        if (!primary.matches("[A-Za-z]{2,8}")) {
            return null;
        }
        return primary.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * POST /api/credentials/oauth2/picker-token - Mint a short-lived access token for the
     * browser-side Google Drive Picker. Owner-gated (the caller's own credential via X-User-ID),
     * restricted to Google Workspace picker integrations, OAuth2-only (uses the refresh flow, so it
     * can never return an API key), and never returns the refresh token. The Picker needs this token
     * to grant the app per-file drive.file access to an existing file the user selects.
     */
    @PostMapping("/picker-token")
    public ResponseEntity<?> pickerToken(
            HttpServletRequest httpRequest,
            @RequestBody PickerTokenRequest request) {

        String userId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        String integration = request.integration() == null ? "" : request.integration().trim();
        if (!PICKER_INTEGRATIONS.contains(integration.toLowerCase(java.util.Locale.ROOT))) {
            log.warn("picker-token refused for unsupported integration '{}' (user {})", integration, userId);
            return ResponseEntity.status(403).body(Map.of("error", "picker_not_supported"));
        }

        String credentialName = (request.credentialName() != null && !request.credentialName().isBlank())
                ? request.credentialName().trim() : integration;

        // OAuth2-only refresh: returns a fresh access token when a refresh_token exists, and never an
        // API key (an api_key credential has no refresh_token -> empty). Owner-gated: resolves only
        // this user's credential. The refresh token itself is never exposed.
        return internalCredentialService.refreshAccessToken(userId, credentialName, organizationId)
                .<ResponseEntity<?>>map(token -> ResponseEntity.ok(new PickerTokenResponse(token)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "no_google_credential")));
    }

    /**
     * GET /api/credentials/oauth2/has-platform-credentials - Check platform credentials availability.
     */
    @GetMapping("/has-platform-credentials")
    public ResponseEntity<Map<String, Boolean>> hasPlatformCredentials(
            @RequestParam("integration") String integration) {

        var availability = oAuth2Service.getPlatformCredentialsAvailability(integration);
        return ResponseEntity.ok(Map.of(
                "available", availability.available(),
                "showUnverifiedAppWarning", availability.showUnverifiedAppWarning()
        ));
    }

    /**
     * GET /api/credentials/oauth2/callback - OAuth2 callback handler.
     */
    @GetMapping("/callback")
    public void callback(
            HttpServletResponse response,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription) throws IOException {

        log.info("OAuth2 callback received - code: {}, state: {}, error: {}",
                code != null ? "present" : "null", state, error);

        if (error != null) {
            log.error("OAuth2 provider error: {} - {}", error, errorDescription);
            String redirectUrl = frontendUrl + "/dashboard/credentials?error=" + error;
            if (errorDescription != null) {
                redirectUrl += "&error_description=" + URLEncoder.encode(errorDescription, StandardCharsets.UTF_8);
            }
            response.sendRedirect(redirectUrl);
            return;
        }

        if (code == null || code.isBlank()) {
            log.error("Missing authorization code in callback");
            response.sendRedirect(frontendUrl + "/dashboard/credentials?error=missing_code");
            return;
        }

        if (state == null || state.isBlank()) {
            log.error("Missing state in callback");
            response.sendRedirect(frontendUrl + "/dashboard/credentials?error=missing_state");
            return;
        }

        try {
            String redirectUrl = oAuth2Service.handleCallback(code, state);
            response.sendRedirect(redirectUrl);
        } catch (Exception e) {
            log.error("Failed to handle OAuth2 callback: {}", e.getMessage(), e);
            response.sendRedirect(frontendUrl + "/dashboard/credentials?error=callback_failed");
        }
    }

    /**
     * POST /api/credentials/oauth2/refresh/{credentialId} - Refresh an expired token.
     */
    @PostMapping("/refresh/{credentialId}")
    public ResponseEntity<?> refreshToken(
            HttpServletRequest httpRequest,
            @PathVariable Long credentialId) {

        String userId = tenantResolver.resolve(httpRequest);
        log.info("Refreshing token for credential {} user {}", credentialId, userId);

        var credential = oAuth2Service.refreshToken(credentialId, userId);
        return ResponseEntity.ok(credential);
    }
}
