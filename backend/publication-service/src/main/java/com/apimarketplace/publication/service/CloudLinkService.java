package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.apimarketplace.agent.cloud.CloudLlmSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Manages the CE-to-cloud OAuth account link.
 * Handles code exchange, token storage (AES-GCM encrypted), refresh, and link status.
 *
 * Only active when marketplace.mode=remote (CE instances).
 */
public class CloudLinkService {

    private static final Logger logger = LoggerFactory.getLogger(CloudLinkService.class);
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final Duration PENDING_AUTH_FLOW_TTL = Duration.ofMinutes(30);
    private static final String DEFAULT_FRONTEND_CALLBACK_PATH = "/app/settings/cloud-account";
    /**
     * Source type the cloud relay stamps on every CE-originated LLM call (mirror of
     * {@code CloudLlmRelayController.SOURCE_TYPE} in agent-service). A CLOUD-linked CE must
     * see ONLY its own relay activity - never the cloud account's other usage (cloud web app,
     * marketplace purchases, plan grants, or other installs sharing the same account). The
     * scope is enforced HERE, server-side, so the cloud returns only the relevant rows:
     * optimized (the history query is filtered at the cloud DB, not dumped then trimmed) and
     * leak-free (the CE never receives the cloud account's unrelated ledger). Symmetric with
     * the cloud, which likewise never shows a CE install's local BYOK ledger.
     */
    private static final String CE_RELAY_SOURCE_TYPE = "CE_LLM_RELAY";
    private static final String LEGACY_DEFAULT_ENCRYPTION_KEY = "change-me-in-" + "production";

    private final CeCloudLinkRepository cloudLinkRepository;
    private final String keycloakUrl;
    private final String clientId;
    private final String redirectUri;
    private final byte[] encryptionKey;
    private final String cloudApiUrl;
    private final String ceVersion;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // In-memory PKCE verifier storage (short-lived, keyed by state)
    private final Map<String, PendingAuthFlow> pendingAuthFlows = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PendingAuthFlow> eldest) {
            return size() > 50; // keep at most 50 pending flows
        }
    };

    private record PendingAuthFlow(
            String codeVerifier,
            Long tenantId,
            String authCode,
            Instant createdAt,
            String frontendReturnPath) {
        PendingAuthFlow withAuthCode(String authCode) {
            return new PendingAuthFlow(codeVerifier, tenantId, authCode, createdAt, frontendReturnPath);
        }

        boolean isExpired(Instant now) {
            return !createdAt.plus(PENDING_AUTH_FLOW_TTL).isAfter(now);
        }
    }

    public CloudLinkService(CeCloudLinkRepository cloudLinkRepository,
                            String keycloakUrl,
                            String clientId,
                            String redirectUri,
                            String encryptionKey,
                            String cloudApiUrl,
                            String ceVersion,
                            ObjectMapper objectMapper) {
        this(cloudLinkRepository, keycloakUrl, clientId, redirectUri, encryptionKey,
                cloudApiUrl, ceVersion, objectMapper, new RestTemplate(), Clock.systemUTC());
    }

    /** Test-friendly constructor - allows injecting a mocked RestTemplate. */
    CloudLinkService(CeCloudLinkRepository cloudLinkRepository,
                     String keycloakUrl,
                     String clientId,
                     String redirectUri,
                     String encryptionKey,
                     String cloudApiUrl,
                     String ceVersion,
                     ObjectMapper objectMapper,
                     RestTemplate restTemplate) {
        this(cloudLinkRepository, keycloakUrl, clientId, redirectUri, encryptionKey,
                cloudApiUrl, ceVersion, objectMapper, restTemplate, Clock.systemUTC());
    }

    /** Test-friendly constructor - allows deterministic pending-state expiry checks. */
    CloudLinkService(CeCloudLinkRepository cloudLinkRepository,
                     String keycloakUrl,
                     String clientId,
                     String redirectUri,
                     String encryptionKey,
                     String cloudApiUrl,
                     String ceVersion,
                     ObjectMapper objectMapper,
                     RestTemplate restTemplate,
                     Clock clock) {
        this.cloudLinkRepository = cloudLinkRepository;
        this.keycloakUrl = keycloakUrl;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.encryptionKey = deriveKey(encryptionKey);
        this.cloudApiUrl = cloudApiUrl;
        this.ceVersion = ceVersion;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Generate the Keycloak authorization URL with PKCE parameters.
     * Returns { authUrl, state } - the state is used to correlate the callback.
     */
    public Map<String, String> generateAuthUrl() {
        return generateAuthUrl(null);
    }

    public Map<String, String> generateAuthUrl(Long tenantId) {
        return generateAuthUrl(tenantId, null);
    }

    public Map<String, String> generateAuthUrl(Long tenantId, String frontendReturnPath) {
        String state = UUID.randomUUID().toString();
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String sanitizedReturnPath = sanitizeFrontendReturnPath(frontendReturnPath);

        synchronized (pendingAuthFlows) {
            Instant now = Instant.now(clock);
            pruneExpiredPendingAuthFlows(now);
            pendingAuthFlows.put(state, new PendingAuthFlow(codeVerifier, tenantId, null, now, sanitizedReturnPath));
        }

        String authUrl = keycloakUrl + "/protocol/openid-connect/auth"
                + "?client_id=" + clientId
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope=openid"
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256"
                + "&state=" + state;

        return Map.of("authUrl", authUrl, "state", state);
    }

    /**
     * Store the OAuth code delivered to the backend callback. The authenticated
     * frontend completes the link later by posting only the state, so the code is
     * never reflected into browser-visible frontend URLs.
     */
    public String receiveCallback(String authCode, String state) {
        if (authCode == null || authCode.isBlank() || state == null || state.isBlank()) {
            throw new IllegalArgumentException("authCode and state are required");
        }
        synchronized (pendingAuthFlows) {
            PendingAuthFlow pending = requirePendingAuthFlow(state);
            if (pending.authCode() != null && !pending.authCode().isBlank()) {
                throw new IllegalArgumentException("Authorization callback already completed");
            }
            pendingAuthFlows.put(state, pending.withAuthCode(authCode));
            return pending.frontendReturnPath();
        }
    }

    /**
     * Exchange the authorization code for tokens and store the link.
     * Called after the OAuth callback with the auth code and state.
     */
    public void linkAccount(Long tenantId, String state) {
        String exchangeAuthCode;
        String codeVerifier;
        synchronized (pendingAuthFlows) {
            PendingAuthFlow pending = requirePendingAuthFlow(state);
            if (pending.tenantId() != null && !pending.tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("State parameter does not belong to this tenant");
            }
            exchangeAuthCode = pending.authCode();
            if (exchangeAuthCode == null || exchangeAuthCode.isBlank()) {
                throw new IllegalArgumentException("Authorization callback has not completed");
            }
            codeVerifier = pending.codeVerifier();
            pendingAuthFlows.remove(state);
        }

        // Exchange code for tokens at Keycloak
        Map<String, Object> tokenResponse = exchangeCodeForTokens(exchangeAuthCode, codeVerifier);

        String accessToken = (String) tokenResponse.get("access_token");
        String refreshToken = (String) tokenResponse.get("refresh_token");
        int expiresIn = tokenResponse.get("expires_in") instanceof Number
                ? ((Number) tokenResponse.get("expires_in")).intValue() : 300;

        if (accessToken == null || refreshToken == null) {
            throw new RuntimeException("Keycloak did not return access_token or refresh_token");
        }

        // Decode JWT to get user info (sub, preferred_username)
        Map<String, String> userInfo = extractUserInfoFromJwt(accessToken);

        // Delete any existing link for this tenant (re-link scenario)
        cloudLinkRepository.findByTenantId(tenantId).ifPresent(existing ->
                cloudLinkRepository.delete(existing));

        // Store the new link
        CeCloudLinkEntity link = new CeCloudLinkEntity();
        link.setTenantId(tenantId);
        link.setCloudUserId(userInfo.get("sub"));
        link.setCloudUsername(userInfo.getOrDefault("username", "Cloud User"));
        link.setEncryptedRefreshToken(encrypt(refreshToken));
        link.setCachedAccessToken(accessToken);
        link.setTokenExpiresAt(clock.instant().plusSeconds(expiresIn - 30)); // 30s buffer
        link.setLinkedAt(clock.instant());
        link.setLlmSource(CloudLlmSource.BYOK.name());
        link.setCatalogSource(CloudLlmSource.BYOK.name());

        cloudLinkRepository.save(link);
        logger.info("CE cloud account linked: tenant {} -> cloud user {}", tenantId, userInfo.get("sub"));

        // Best-effort register on the cloud's ce-link registry. We don't bubble failures
        // here so the user-visible OAuth completion stays clean - the heartbeat scheduler
        // will retry on the next tick if this misses.
        try {
            registerWithCloud(link);
            promoteCloudSourceWhenRegistered(tenantId);
        } catch (RuntimeException registerFailure) {
            logger.warn("CE cloud link register POST failed for tenant {} ({}). Heartbeat scheduler will retry.",
                    tenantId, registerFailure.getMessage());
        }
    }

    private boolean promoteCloudSourceWhenRegistered(Long tenantId) {
        Optional<CeCloudLinkEntity> latest = cloudLinkRepository.findByTenantId(tenantId);
        if (latest.isEmpty() || latest.get().getRegisteredAt() == null) {
            return false;
        }
        CeCloudLinkEntity registered = latest.get();
        if (CloudLlmSource.CLOUD.name().equals(registered.getLlmSource())) {
            return true;
        }
        registered.setLlmSource(CloudLlmSource.CLOUD.name());
        cloudLinkRepository.save(registered);
        return true;
    }

    private PendingAuthFlow requirePendingAuthFlow(String state) {
        Instant now = Instant.now(clock);
        pruneExpiredPendingAuthFlows(now);
        PendingAuthFlow pending = pendingAuthFlows.get(state);
        if (pending == null || pending.isExpired(now)) {
            pendingAuthFlows.remove(state);
            throw new IllegalArgumentException("Invalid or expired state parameter");
        }
        return pending;
    }

    private void pruneExpiredPendingAuthFlows(Instant now) {
        pendingAuthFlows.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private String sanitizeFrontendReturnPath(String returnPath) {
        if (returnPath == null || returnPath.isBlank()) {
            return DEFAULT_FRONTEND_CALLBACK_PATH;
        }
        URI uri;
        try {
            uri = URI.create(returnPath);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cloud-link return path");
        }
        if (uri.isAbsolute()
                || uri.getHost() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !returnPath.startsWith("/")
                || returnPath.startsWith("//")) {
            throw new IllegalArgumentException("Invalid cloud-link return path");
        }

        String path = uri.getPath();
        if (DEFAULT_FRONTEND_CALLBACK_PATH.equals(path)
                || "/ce-setup".equals(path)
                || "/app/marketplace".equals(path)
                || path.matches("^/[a-z]{2}/ce-setup$")
                || path.matches("^/[a-z]{2}/app/marketplace$")) {
            return path;
        }
        throw new IllegalArgumentException("Unsupported cloud-link return path");
    }

    /**
     * POSTs the freshly-linked install to the cloud's {@code /api/ce-link/register}
     * endpoint. Stamps {@code registeredAt = now()} on the local row on 2xx so the
     * heartbeat scheduler picks it up. On 409 ALREADY_BOUND the row is also marked
     * registered (the install_id is already in the cloud registry - no further work).
     */
    public void registerWithCloud(CeCloudLinkEntity link) {
        String accessToken = getCloudAccessToken(link.getTenantId());
        String url = cloudApiUrl + "/ce-link/register";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("installId", link.getInstallId().toString());
        body.put("ceVersion", ceVersion);
        if (link.getLabel() != null && !link.getLabel().isBlank()) {
            body.put("label", link.getLabel());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, request, JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                // Defensive: don't stamp registeredAt unless the cloud explicitly succeeded.
                logger.warn("CE cloud link register POST non-2xx for tenant={} installId={}: {}",
                        link.getTenantId(), link.getInstallId(), response.getStatusCode().value());
                return;
            }
            link.setRegisteredAt(clock.instant());
            cloudLinkRepository.save(link);
            logger.info("CE cloud link registered (tenant={} installId={}): cloud responded {}",
                    link.getTenantId(), link.getInstallId(), response.getStatusCode().value());
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict alreadyBound) {
            // 409 ALREADY_BOUND - the install_id is already in the cloud registry.
            // Mark local registered so we stop re-POSTing on every scheduler tick.
            link.setRegisteredAt(clock.instant());
            cloudLinkRepository.save(link);
            logger.info("CE cloud link 409 ALREADY_BOUND (tenant={} installId={}) - marked locally registered",
                    link.getTenantId(), link.getInstallId());
        }
    }

    /**
     * Last successfully-fetched cloud plan code per {@code installId}. Served as the fallback when a
     * later fetch fails on a transient cloud outage, so the CE workspace cap never momentarily
     * collapses to FREE and the CE reconcile sweep does not flap workspaces paused⇄active on a blip.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CloudEntitlement> lastGoodCloudEntitlement =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The cloud account's governing plan code + credit-tier index + billing cadence, relayed to a
     * linked CE so its pricing page can mirror not just the plan but the exact credit slider / cadence.
     */
    public record CloudEntitlement(String planCode, int creditTierIndex, String cadence) {}

    /**
     * Fetches the plan code of the cloud account this install is bound to, from
     * the cloud's {@code GET /api/ce-link/{installId}/entitlements} (CE&#8596;Cloud
     * pricing delegation, tranche 3). The cloud subscription is the single source
     * of truth; the CE inherits it.
     *
     * <p>Returns {@code null} when the install isn't registered on the cloud yet, so the CE falls
     * back to its own local plan instead of failing closed. On a <b>transient cloud outage</b> it
     * returns the last successfully-fetched plan (if any) rather than {@code null}, so a blip cannot
     * collapse the workspace cap to FREE and flap workspaces. A successful response that carries no
     * usable plan code is authoritative ("no cloud plan"): it returns {@code null} and clears any
     * stale cached value.
     */
    public String fetchCloudPlanCode(CeCloudLinkEntity link) {
        CloudEntitlement e = fetchCloudEntitlement(link);
        return e != null ? e.planCode() : null;
    }

    /**
     * Fetches the bound cloud account's full governing entitlement (plan code + credit-tier index +
     * billing cadence) from the cloud's {@code GET /api/ce-link/{installId}/entitlements} (CE&#8596;Cloud
     * pricing delegation). The cloud subscription is the single source of truth; the CE inherits it.
     *
     * <p>Returns {@code null} when the install isn't registered yet, so the CE falls back to its own
     * local plan instead of failing closed. On a <b>transient cloud outage</b> it serves the last
     * successfully-fetched entitlement (if any) rather than {@code null}, so a blip cannot collapse
     * the workspace cap to FREE / flap workspaces. A successful response that carries no usable plan
     * code is authoritative ("no cloud plan"): returns {@code null} and clears any stale cached value.
     */
    public CloudEntitlement fetchCloudEntitlement(CeCloudLinkEntity link) {
        if (link == null || link.getRegisteredAt() == null) {
            return null;
        }
        String installId = String.valueOf(link.getInstallId());
        try {
            String accessToken = getCloudAccessToken(link.getTenantId());
            String url = cloudApiUrl + "/ce-link/" + installId + "/entitlements";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<JsonNode> response =
                    restTemplate.exchange(url, HttpMethod.GET, request, JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode body = response.getBody();
                JsonNode planCode = body.get("planCode");
                if (planCode != null && !planCode.isNull() && !planCode.asText().isBlank()) {
                    int creditTierIndex = body.path("creditTierIndex").asInt(0);
                    String cadence = body.hasNonNull("cadence") ? body.get("cadence").asText() : null;
                    CloudEntitlement e = new CloudEntitlement(planCode.asText(), creditTierIndex, cadence);
                    lastGoodCloudEntitlement.put(installId, e);
                    return e;
                }
            }
            // Cloud responded but without a usable plan code → authoritative "no cloud plan"; drop
            // any stale cached value so we never keep serving an entitlement the cloud dropped.
            lastGoodCloudEntitlement.remove(installId);
            return null;
        } catch (RuntimeException e) {
            logger.warn("CE cloud entitlements fetch failed for tenant={} installId={}: {}",
                    link.getTenantId(), installId, e.getMessage());
            // Transient outage: serve the last known-good entitlement instead of collapsing to FREE.
            // Null only if the cloud has never returned a usable plan for this install.
            return lastGoodCloudEntitlement.get(installId);
        }
    }

    /**
     * The cloud account's plan code that GOVERNS this CE install for entitlement
     * purposes (feature gating), present only when the install is CLOUD-sourced (through
     * EITHER toggle, see {@link #governingCloudEntitlementForLink}) and the cloud returns
     * a usable plan; empty otherwise (all-BYOK, unlinked, unregistered, or the cloud
     * unreachable). The consultation half of {@code EffectivePlanResolver}: an all-BYOK
     * install keeps its own local plan, matching the established resolver contract.
     * {@code PlanLimitService} falls back to the local plan when this is empty, so a
     * transient cloud outage never strips entitlements.
     */
    public java.util.Optional<String> governingCloudPlanCode(Long tenantId) {
        return governingCloudEntitlement(tenantId).map(CloudEntitlement::planCode);
    }

    /**
     * Like {@link #governingCloudPlanCode} but carries the full entitlement (plan code + credit-tier
     * index + billing cadence). CLOUD-sourced installs only (either toggle); empty for
     * all-BYOK/unlinked/unregistered or when the cloud is unreachable and no last-known-good
     * value exists.
     */
    public java.util.Optional<CloudEntitlement> governingCloudEntitlement(Long tenantId) {
        return governingCloudEntitlementForLink(
                cloudLinkRepository.findByTenantId(tenantId).orElse(null));
    }

    /**
     * Same as {@link #governingCloudEntitlement(Long)} but resolves from a link entity directly,
     * so a caller that already holds the GOVERNING link - e.g. the install-global link a CE member
     * inherits - can read its entitlement without re-keying on the caller's own tenant.
     *
     * <p>The entitlement is fetched when the install draws on the cloud account through EITHER
     * relay toggle: {@code llmSource=CLOUD} (LLM relay) or {@code catalogSource=CLOUD} (catalog
     * credential relay). Both relays are gated on the cloud plan, so a catalog-only CLOUD install
     * needs its cloudPlanCode surfaced just like an LLM-relaying one (the frontend upsell keys on
     * it). Only when BOTH toggles are BYOK does the local plan govern and the fetch is skipped.
     */
    private java.util.Optional<CloudEntitlement> governingCloudEntitlementForLink(CeCloudLinkEntity link) {
        if (link == null
                || (CloudLlmSource.from(link.getLlmSource()) != CloudLlmSource.CLOUD
                        && CloudLlmSource.from(link.getCatalogSource()) != CloudLlmSource.CLOUD)) {
            return java.util.Optional.empty();
        }
        CloudEntitlement e = fetchCloudEntitlement(link);
        return (e != null && e.planCode() != null && !e.planCode().isBlank())
                ? java.util.Optional.of(e)
                : java.util.Optional.empty();
    }

    /**
     * Fetches the bound cloud account's credit usage summary from the cloud's
     * {@code GET /api/credits/summary}, so a CLOUD-linked CE can mirror the cloud
     * billing view (balance, 30-day consumption, per-source breakdown) - the relay
     * meters spend against the cloud account, so this is where that spend is visible.
     * The cloud is the single source of truth; the CE just renders it (in $).
     *
     * <p>Returns {@code null} when the install isn't registered yet or the cloud is
     * unreachable, so the CE quota view falls back to its (empty) local ledger instead
     * of erroring.
     *
     * <p>The result is scoped to this CE's relay slice only (see {@link #CE_RELAY_SOURCE_TYPE}
     * and {@link #scopeSummaryToRelay}) - the CE never sees the cloud account's unrelated spend.
     */
    public Map<String, Object> fetchCloudUsageSummary(Long tenantId) {
        CeCloudLinkEntity link = cloudLinkRepository.findByTenantId(tenantId).orElse(null);
        if (link == null || link.getRegisteredAt() == null) {
            return null;
        }
        try {
            String accessToken = getCloudAccessToken(tenantId);
            String url = cloudApiUrl + "/credits/summary";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();
                return scopeSummaryToRelay(body);
            }
            return null;
        } catch (RuntimeException e) {
            logger.warn("CE cloud usage summary fetch failed for tenant={} installId={}: {}",
                    link.getTenantId(), link.getInstallId(), e.getMessage());
            return null;
        }
    }

    /**
     * Mirrors the bound cloud account's referral code + invite stats from the cloud's
     * {@code GET /api/ce-link/{installId}/reward-stats}, so a CLOUD-linked CE can show
     * its owner's "invite friends" progress. The reward state lives on the cloud account;
     * the CE just renders it. Returns {@code null} when unregistered or unreachable so the
     * CE shows the connect-first state.
     */
    public Map<String, Object> fetchCloudRewardStats(Long tenantId) {
        CeCloudLinkEntity link = cloudLinkRepository.findByTenantId(tenantId).orElse(null);
        if (link == null || link.getRegisteredAt() == null) {
            return null;
        }
        try {
            String accessToken = getCloudAccessToken(tenantId);
            String url = cloudApiUrl + "/ce-link/" + link.getInstallId() + "/reward-stats";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();
                return body;
            }
            return null;
        } catch (RuntimeException e) {
            logger.warn("CE cloud reward stats fetch failed for tenant={} installId={}: {}",
                    link.getTenantId(), link.getInstallId(), e.getMessage());
            return null;
        }
    }

    /** Status + JSON body of a CE to Cloud reward redeem (relays the cloud's typed result). */
    public record CloudRedeemResult(int status, Map<String, Object> body) {}

    /**
     * Redeems a reward code on the cloud as the bound cloud user (the CE referee acts as
     * their own cloud account, no install scope). Relays the cloud's status and typed body
     * so the CE surfaces the same error codes. Returns 409 CLOUD_LINK_REQUIRED when the
     * install is not linked, 502 CLOUD_UNREACHABLE on a transport failure.
     */
    public CloudRedeemResult redeemRewardOnCloud(Long tenantId, String code) {
        CeCloudLinkEntity link = cloudLinkRepository.findByTenantId(tenantId).orElse(null);
        if (link == null || link.getRegisteredAt() == null) {
            return new CloudRedeemResult(409, Map.of(
                    "success", false, "code", "CLOUD_LINK_REQUIRED",
                    "message", "Connect your cloud account to redeem a code."));
        }
        try {
            String accessToken = getCloudAccessToken(tenantId);
            String url = cloudApiUrl + "/billing/redeem";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request =
                    new HttpEntity<>(Map.of("code", code == null ? "" : code), headers);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return new CloudRedeemResult(response.getStatusCode().value(), body != null ? body : Map.of());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return new CloudRedeemResult(e.getStatusCode().value(), parseRedeemErrorBody(e));
        } catch (RuntimeException e) {
            logger.warn("CE cloud reward redeem failed for tenant={}: {}", tenantId, e.getMessage());
            return new CloudRedeemResult(502, Map.of(
                    "success", false, "code", "CLOUD_UNREACHABLE",
                    "message", "Could not reach the cloud to redeem this code."));
        }
    }

    private Map<String, Object> parseRedeemErrorBody(org.springframework.web.client.HttpStatusCodeException e) {
        try {
            String raw = e.getResponseBodyAsString();
            if (raw != null && !raw.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
                return parsed;
            }
        } catch (Exception ignored) {
            // fall through to a generic body
        }
        return Map.of("success", false, "code", "REDEEM_FAILED", "message", "Could not redeem this code.");
    }

    /**
     * Reduces the cloud account's full usage summary to ONLY this CE's relay slice, so the CE
     * never surfaces the cloud account's unrelated spend (web app, purchases, plan grants, other
     * installs). Keeps {@code balance} (the cloud account's wallet balance - the pool the relay
     * draws from; the CE quota UI does not display it, it shows an infinite local balance) and
     * {@code delinquent};
     * replaces {@code totalConsumedLast30Days} with the {@link #CE_RELAY_SOURCE_TYPE} consumption
     * and trims {@code breakdownByType} to the relay entry only (empty when this install has never
     * relayed). Amounts stay in CREDITS - the CE frontend converts to dollars at display time.
     */
    private Map<String, Object> scopeSummaryToRelay(Map<String, Object> cloudSummary) {
        Map<String, Object> relayEntry = null;
        Object breakdownRaw = cloudSummary.get("breakdownByType");
        if (breakdownRaw instanceof Map<?, ?> breakdown) {
            Object entry = breakdown.get(CE_RELAY_SOURCE_TYPE);
            if (entry instanceof Map<?, ?> entryMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) entryMap;
                relayEntry = casted;
            }
        }
        Object relayConsumed = relayEntry != null ? relayEntry.get("credits") : null;

        Map<String, Object> scoped = new HashMap<>();
        scoped.put("balance", cloudSummary.get("balance"));
        scoped.put("totalConsumedLast30Days", relayConsumed != null ? relayConsumed : 0);
        scoped.put("breakdownByType",
                relayEntry != null ? Map.of(CE_RELAY_SOURCE_TYPE, relayEntry) : Map.of());
        scoped.put("delinquent", cloudSummary.getOrDefault("delinquent", false));
        return scoped;
    }

    /**
     * Mirror of this CE's relay rows in the bound cloud account's paginated credit usage
     * history (cloud {@code GET /api/credits/history}). The query is ALWAYS scoped to
     * {@link #CE_RELAY_SOURCE_TYPE} server-side, so a CLOUD-linked CE sees only its own
     * relayed LLM calls - never the cloud account's other ledger rows - and the cloud filters
     * at the DB rather than returning everything to be trimmed. Returns the cloud's Page JSON
     * as a map, or {@code null} when unregistered/unreachable so the CE falls back to its
     * local history.
     */
    public Map<String, Object> fetchCloudUsageHistory(Long tenantId, int page, int size) {
        CeCloudLinkEntity link = cloudLinkRepository.findByTenantId(tenantId).orElse(null);
        if (link == null || link.getRegisteredAt() == null) {
            return null;
        }
        try {
            String accessToken = getCloudAccessToken(tenantId);
            StringBuilder url = new StringBuilder(cloudApiUrl)
                    .append("/credits/history?page=").append(page).append("&size=").append(size)
                    .append("&sourceType=")
                    .append(java.net.URLEncoder.encode(CE_RELAY_SOURCE_TYPE, java.nio.charset.StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response =
                    restTemplate.exchange(url.toString(), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();
                return body;
            }
            return null;
        } catch (RuntimeException e) {
            logger.warn("CE cloud usage history fetch failed for tenant={} installId={}: {}",
                    link.getTenantId(), link.getInstallId(), e.getMessage());
            return null;
        }
    }

    /**
     * POSTs to {@code /api/ce-link/{installId}/heartbeat}. Called periodically by
     * {@code CeCloudLinkHeartbeatScheduler}. On 410 GONE the cloud side has revoked
     * this install - we clear {@code registeredAt} locally so the next click on
     * "Reconnect" forces a fresh register, and zero out the cached access token so
     * subsequent paid marketplace calls re-prompt OAuth.
     */
    public HeartbeatOutcome sendHeartbeat(CeCloudLinkEntity incomingLink) {
        // Re-fetch by tenantId so we operate on a SINGLE entity instance throughout -
        // `getCloudAccessToken` below also re-fetches + saves token-refresh fields, and
        // if we mutated the caller's stale instance in the catch blocks the save would
        // overwrite the just-refreshed cachedAccessToken / tokenExpiresAt with stale
        // values (audit M1). The scheduler typically calls with a findAll() result so
        // the incoming instance is already stale by the time we reach the catch path.
        Optional<CeCloudLinkEntity> reloaded = cloudLinkRepository.findByTenantId(incomingLink.getTenantId());
        if (reloaded.isEmpty()) {
            logger.debug("Heartbeat skipped - tenant {} no longer linked", incomingLink.getTenantId());
            return HeartbeatOutcome.PENDING_REGISTER;
        }
        CeCloudLinkEntity link = reloaded.get();

        if (link.getRegisteredAt() == null) {
            // Not yet registered - try register first; heartbeat will catch up next tick.
            try {
                registerWithCloud(link);
                return promoteCloudSourceWhenRegistered(link.getTenantId())
                        ? HeartbeatOutcome.REGISTERED
                        : HeartbeatOutcome.PENDING_REGISTER;
            } catch (RuntimeException e) {
                logger.debug("Heartbeat skipped - pending register failed for tenant {}: {}",
                        link.getTenantId(), e.getMessage());
                return HeartbeatOutcome.PENDING_REGISTER;
            }
        }
        String accessToken;
        try {
            accessToken = getCloudAccessToken(link.getTenantId());
        } catch (RuntimeException tokenFailure) {
            logger.warn("Heartbeat skipped - no access token for tenant {}: {}",
                    link.getTenantId(), tokenFailure.getMessage());
            return HeartbeatOutcome.TOKEN_UNAVAILABLE;
        }
        String url = cloudApiUrl + "/ce-link/" + link.getInstallId() + "/heartbeat";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
                Map.of("ceVersion", ceVersion), headers);
        try {
            restTemplate.postForEntity(url, request, Void.class);
            // Re-fetch one more time so the lastUsedAt save doesn't clobber any
            // token-refresh state that getCloudAccessToken may have just persisted.
            CeCloudLinkEntity fresh = cloudLinkRepository.findByTenantId(link.getTenantId()).orElse(link);
            fresh.setLastUsedAt(clock.instant());
            cloudLinkRepository.save(fresh);
            return HeartbeatOutcome.OK;
        } catch (org.springframework.web.client.HttpClientErrorException.Gone revoked) {
            // 410 GONE - cloud revoked the link. Clear local registered + cached token
            // so the UI surfaces "Reconnect cloud account" + paid acquires re-prompt OAuth.
            CeCloudLinkEntity fresh = cloudLinkRepository.findByTenantId(link.getTenantId()).orElse(link);
            fresh.setRegisteredAt(null);
            fresh.setCachedAccessToken(null);
            fresh.setTokenExpiresAt(null);
            fresh.setLlmSource(CloudLlmSource.BYOK.name());
            fresh.setCatalogSource(CloudLlmSource.BYOK.name());
            cloudLinkRepository.save(fresh);
            // Mirror onto the caller's instance so any test/caller observing it sees the
            // post-revoke state without needing to re-fetch.
            incomingLink.setRegisteredAt(null);
            incomingLink.setCachedAccessToken(null);
            incomingLink.setTokenExpiresAt(null);
            incomingLink.setLlmSource(CloudLlmSource.BYOK.name());
            incomingLink.setCatalogSource(CloudLlmSource.BYOK.name());
            logger.warn("CE cloud link 410 GONE for tenant {} installId={} - local link marked unregistered",
                    link.getTenantId(), link.getInstallId());
            return HeartbeatOutcome.REVOKED;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound notFound) {
            // 404 - install_id unknown on cloud (DB reset?). Clear registeredAt only;
            // keep cachedAccessToken so user can re-link without re-OAuth.
            CeCloudLinkEntity fresh = cloudLinkRepository.findByTenantId(link.getTenantId()).orElse(link);
            fresh.setRegisteredAt(null);
            fresh.setLlmSource(CloudLlmSource.BYOK.name());
            fresh.setCatalogSource(CloudLlmSource.BYOK.name());
            cloudLinkRepository.save(fresh);
            incomingLink.setRegisteredAt(null);
            incomingLink.setLlmSource(CloudLlmSource.BYOK.name());
            incomingLink.setCatalogSource(CloudLlmSource.BYOK.name());
            logger.warn("CE cloud link 404 for tenant {} installId={} - cleared registered marker",
                    link.getTenantId(), link.getInstallId());
            return HeartbeatOutcome.NOT_FOUND;
        } catch (RuntimeException transientFailure) {
            // Network blip (HttpServerErrorException, ResourceAccessException, …) -
            // next tick retries. No local mutation.
            logger.warn("Heartbeat transient failure for tenant {} installId={}: {}",
                    link.getTenantId(), link.getInstallId(), transientFailure.getMessage());
            return HeartbeatOutcome.TRANSIENT_FAILURE;
        }
    }

    /** Result of one heartbeat call, observable by tests + scheduler. */
    public enum HeartbeatOutcome {
        OK, REGISTERED, PENDING_REGISTER, TOKEN_UNAVAILABLE, REVOKED, NOT_FOUND, TRANSIENT_FAILURE
    }

    /**
     * Get a valid cloud access token for the linked account.
     * Returns cached token if still valid, otherwise refreshes from Keycloak.
     */
    public String getCloudAccessToken(Long tenantId) {
        CeCloudLinkEntity link = cloudLinkRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new CloudAccountNotLinkedException("No cloud account linked"));

        // Check if cached access token is still valid
        if (link.getCachedAccessToken() != null && link.getTokenExpiresAt() != null
                && clock.instant().isBefore(link.getTokenExpiresAt())) {
            link.setLastUsedAt(clock.instant());
            cloudLinkRepository.save(link);
            return link.getCachedAccessToken();
        }

        // Refresh the access token
        String refreshToken = decrypt(link.getEncryptedRefreshToken());
        Map<String, Object> tokenResponse = refreshAccessToken(refreshToken);

        String newAccessToken = (String) tokenResponse.get("access_token");
        String newRefreshToken = (String) tokenResponse.get("refresh_token");
        int expiresIn = tokenResponse.get("expires_in") instanceof Number
                ? ((Number) tokenResponse.get("expires_in")).intValue() : 300;

        if (newAccessToken == null) {
            throw new RuntimeException("Failed to refresh cloud access token");
        }

        // Update stored tokens
        link.setCachedAccessToken(newAccessToken);
        link.setTokenExpiresAt(clock.instant().plusSeconds(expiresIn - 30));
        if (newRefreshToken != null) {
            link.setEncryptedRefreshToken(encrypt(newRefreshToken));
        }
        link.setLastUsedAt(clock.instant());
        cloudLinkRepository.save(link);

        return newAccessToken;
    }

    /**
     * Get the link status for a tenant.
     */
    public Map<String, Object> getLinkStatus(Long tenantId) {
        Map<String, Object> status = new HashMap<>();

        // Per-user link: drives MANAGEMENT (link / unlink / relink stay the caller's OWN link), so
        // a CE member never gets management controls over the admin's install-level cloud link.
        Optional<CeCloudLinkEntity> own = cloudLinkRepository.findByTenantId(tenantId);
        if (own.isPresent()) {
            CeCloudLinkEntity entity = own.get();
            status.put("linked", true);
            status.put("registered", entity.getRegisteredAt() != null);
            status.put("cloudUsername", entity.getCloudUsername());
            status.put("linkedAt", entity.getLinkedAt().toString());
            status.put("llmSource", CloudLlmSource.from(entity.getLlmSource()).name());
            status.put("catalogSource", CloudLlmSource.from(entity.getCatalogSource()).name());
            if (entity.getInstallId() != null) {
                status.put("installId", entity.getInstallId().toString());
            }
            governingCloudEntitlementForLink(entity).ifPresent(e -> {
                status.put("cloudPlanCode", e.planCode());
                status.put("cloudCreditTierIndex", e.creditTierIndex());
                if (e.cadence() != null) {
                    status.put("cloudCadence", e.cadence());
                }
            });
        } else {
            status.put("linked", false);
            status.put("registered", false);
        }

        // Install-global activation: drives VISIBILITY (marketplace, highlights, plan badge). One
        // cloud account governs the whole self-hosted install, so a member WITHOUT their own link
        // still inherits the admin's cloud activation - exactly like the LLM relay already does
        // install-wide. STATUS only (no tokens), and it does NOT grant team capability (that stays
        // the org owner's plan via governingCloudPlanCode), so members see the cloud yet remain
        // owner-gated for invite / create-workspace.
        CeCloudLinkEntity installLink = cloudLinkRepository
                .findFirstByRegisteredAtNotNullOrderByLinkedAtDesc().orElse(null);
        status.put("installLinked", installLink != null);
        // Carry the install plan code for the inheriting (member) case only; the owner already has
        // cloudPlanCode above, so this avoids a redundant second cloud round-trip for the owner.
        if (installLink != null && own.isEmpty()) {
            governingCloudEntitlementForLink(installLink).ifPresent(e ->
                    status.put("installCloudPlanCode", e.planCode()));
        }
        return status;
    }

    public CloudLlmSource getLlmSource(Long tenantId) {
        return cloudLinkRepository.findByTenantId(tenantId)
                .map(link -> CloudLlmSource.from(link.getLlmSource()))
                .orElse(CloudLlmSource.BYOK);
    }

    public CloudLlmSource getCatalogSource(Long tenantId) {
        return cloudLinkRepository.findByTenantId(tenantId)
                .map(link -> CloudLlmSource.from(link.getCatalogSource()))
                .orElse(CloudLlmSource.BYOK);
    }

    public CloudLlmSource setCatalogSource(Long tenantId, CloudLlmSource source) {
        CloudLlmSource normalized = source == null ? CloudLlmSource.BYOK : source;
        Optional<CeCloudLinkEntity> existing = cloudLinkRepository.findByTenantId(tenantId);
        if (existing.isEmpty()) {
            if (normalized == CloudLlmSource.BYOK) {
                return CloudLlmSource.BYOK;
            }
            throw new CloudAccountNotLinkedException("No cloud account linked");
        }
        CeCloudLinkEntity link = existing.get();
        if (normalized == CloudLlmSource.CLOUD && link.getRegisteredAt() == null) {
            registerWithCloud(link);
            link = cloudLinkRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new CloudAccountNotLinkedException("No cloud account linked"));
            if (link.getRegisteredAt() == null) {
                throw new IllegalStateException("Cloud link is not registered");
            }
        }
        link.setCatalogSource(normalized.name());
        cloudLinkRepository.save(link);
        return normalized;
    }

    public CloudLlmSource setLlmSource(Long tenantId, CloudLlmSource source) {
        CloudLlmSource normalized = source == null ? CloudLlmSource.BYOK : source;
        Optional<CeCloudLinkEntity> existing = cloudLinkRepository.findByTenantId(tenantId);
        if (existing.isEmpty()) {
            if (normalized == CloudLlmSource.BYOK) {
                return CloudLlmSource.BYOK;
            }
            throw new CloudAccountNotLinkedException("No cloud account linked");
        }
        CeCloudLinkEntity link = existing.get();
        if (normalized == CloudLlmSource.CLOUD && link.getRegisteredAt() == null) {
            registerWithCloud(link);
            link = cloudLinkRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new CloudAccountNotLinkedException("No cloud account linked"));
            if (link.getRegisteredAt() == null) {
                throw new IllegalStateException("Cloud link is not registered");
            }
        }
        link.setLlmSource(normalized.name());
        cloudLinkRepository.save(link);
        return normalized;
    }

    public CloudRuntimeStatus getCloudRuntimeStatus(Long tenantId) {
        CeCloudLinkEntity link = cloudLinkRepository.findByTenantId(tenantId).orElse(null);
        if (link == null) {
            return CloudRuntimeStatus.byok();
        }
        CloudLlmSource source = CloudLlmSource.from(link.getLlmSource());
        if (source != CloudLlmSource.CLOUD) {
            return CloudRuntimeStatus.byok();
        }
        return resolveCloudSelectedRuntime(tenantId, link);
    }

    /**
     * Mirror of {@link #getCloudRuntimeStatus} for the catalog credential relay, keyed on
     * the link's {@code catalogSource} instead of {@code llmSource}. The two toggles are
     * independent: a tenant may relay LLM calls to the cloud while executing catalog tools
     * locally with its own credentials, and vice versa.
     */
    public CloudRuntimeStatus getCatalogRuntimeStatus(Long tenantId) {
        CeCloudLinkEntity link = cloudLinkRepository.findByTenantId(tenantId).orElse(null);
        if (link == null) {
            return CloudRuntimeStatus.byok();
        }
        CloudLlmSource source = CloudLlmSource.from(link.getCatalogSource());
        if (source != CloudLlmSource.CLOUD) {
            return CloudRuntimeStatus.byok();
        }
        return resolveCloudSelectedRuntime(tenantId, link);
    }

    /**
     * Shared tail of {@link #getCloudRuntimeStatus} and {@link #getCatalogRuntimeStatus}:
     * the caller already established that the relevant source toggle is CLOUD, so this
     * ensures the link is registered (attempting a register when it is not), then
     * resolves the access token. Registration and token resolution do not depend on
     * which toggle selected the cloud.
     */
    private CloudRuntimeStatus resolveCloudSelectedRuntime(Long tenantId, CeCloudLinkEntity link) {
        if (link.getRegisteredAt() == null) {
            registerWithCloud(link);
        }
        CeCloudLinkEntity fresh = cloudLinkRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new CloudAccountNotLinkedException("No cloud account linked"));
        if (fresh.getRegisteredAt() == null) {
            return CloudRuntimeStatus.notReady(CloudLlmSource.CLOUD);
        }
        String accessToken = getCloudAccessToken(tenantId);
        return new CloudRuntimeStatus(
                CloudLlmSource.CLOUD,
                true,
                accessToken,
                fresh.getInstallId().toString(),
                cloudApiUrl
        );
    }

    public boolean hasAnyCloudLlmSource() {
        return cloudLinkRepository.existsByLlmSource(CloudLlmSource.CLOUD.name());
    }

    /**
     * Install-global runtime for the model-catalog bundle sync. The sync runs once
     * per CE install (not per tenant), so it resolves THE active cloud link here -
     * the most recently linked registered link - regardless of its {@code llmSource}.
     * Being cloud-linked is what entitles an install to catalog updates; choosing
     * CLOUD inference (vs BYOK) is a separate per-tenant decision that must not
     * decide whether the catalog stays fresh. Returns {@code byok()} (not ready)
     * when this install has no registered link, or {@code notReady} when the link
     * exists but its access token can't be obtained.
     */
    public CloudRuntimeStatus getActiveInstallRuntime() {
        CeCloudLinkEntity link = cloudLinkRepository
                .findFirstByRegisteredAtNotNullOrderByLinkedAtDesc()
                .orElse(null);
        if (link == null) {
            logger.info("Active-install runtime: no registered cloud link found install-globally → not linked");
            return CloudRuntimeStatus.byok();
        }
        CloudLlmSource source = CloudLlmSource.from(link.getLlmSource());
        try {
            String accessToken = getCloudAccessToken(link.getTenantId());
            logger.info("Active-install runtime: resolved active link tenant={} source={} → ready",
                    link.getTenantId(), source);
            return new CloudRuntimeStatus(
                    source, true, accessToken, link.getInstallId().toString(), cloudApiUrl);
        } catch (RuntimeException tokenFailure) {
            logger.warn("Active-install runtime: access token unavailable for tenant {}: {}",
                    link.getTenantId(), tokenFailure.toString());
            return CloudRuntimeStatus.notReady(source);
        }
    }

    /**
     * Install-global runtime for the catalog credential relay, mirror of
     * {@link #getActiveInstallRuntime()} but keyed on the link's {@code catalogSource}
     * instead of its {@code llmSource}. Used by the auth-side public-info delegation,
     * which runs per install (not per tenant), so it resolves THE active cloud link -
     * the most recently linked registered link. Unlike the bundle-sync runtime (where
     * being linked alone entitles the install to catalog updates), the credential relay
     * is an OPT-IN: it only applies when the admin set {@code catalogSource=CLOUD},
     * mirroring the tenant-scoped {@link #getCatalogRuntimeStatus} filter. Returns
     * {@code byok()} (not ready) when this install has no registered link or its active
     * link's catalog source is not CLOUD, or {@code notReady} when the link exists but
     * its access token can't be obtained.
     */
    public CloudRuntimeStatus getActiveInstallCatalogRuntime() {
        CeCloudLinkEntity link = cloudLinkRepository
                .findFirstByRegisteredAtNotNullOrderByLinkedAtDesc()
                .orElse(null);
        if (link == null) {
            logger.info("Active-install catalog runtime: no registered cloud link found install-globally → not linked");
            return CloudRuntimeStatus.byok();
        }
        CloudLlmSource source = CloudLlmSource.from(link.getCatalogSource());
        if (source != CloudLlmSource.CLOUD) {
            logger.info("Active-install catalog runtime: active link tenant={} has catalogSource={} → relay not opted in",
                    link.getTenantId(), source);
            return CloudRuntimeStatus.byok();
        }
        try {
            String accessToken = getCloudAccessToken(link.getTenantId());
            logger.info("Active-install catalog runtime: resolved active link tenant={} source={} → ready",
                    link.getTenantId(), source);
            return new CloudRuntimeStatus(
                    source, true, accessToken, link.getInstallId().toString(), cloudApiUrl);
        } catch (RuntimeException tokenFailure) {
            logger.warn("Active-install catalog runtime: access token unavailable for tenant {}: {}",
                    link.getTenantId(), tokenFailure.toString());
            return CloudRuntimeStatus.notReady(source);
        }
    }

    public record CloudRuntimeStatus(
            CloudLlmSource source,
            boolean cloudReady,
            String accessToken,
            String installId,
            String cloudApiUrl
    ) {
        static CloudRuntimeStatus byok() {
            return new CloudRuntimeStatus(CloudLlmSource.BYOK, false, null, null, null);
        }

        static CloudRuntimeStatus notReady(CloudLlmSource source) {
            return new CloudRuntimeStatus(source, false, null, null, null);
        }
    }

    /**
     * Unlink the cloud account.
     */
    public void unlinkAccount(Long tenantId) {
        cloudLinkRepository.findByTenantId(tenantId).ifPresent(link -> {
            cloudLinkRepository.delete(link);
            logger.info("CE cloud account unlinked for tenant {}", tenantId);
        });
    }

    /**
     * Check if a tenant has a linked cloud account.
     */
    public boolean isLinked(Long tenantId) {
        return cloudLinkRepository.findByTenantId(tenantId).isPresent();
    }

    // ========== Token Exchange ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForTokens(String authCode, String codeVerifier) {
        String tokenUrl = keycloakUrl + "/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("code", authCode);
        body.add("redirect_uri", redirectUri);
        body.add("code_verifier", codeVerifier);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Keycloak token exchange failed: " + response.getStatusCode());
            }
            return response.getBody();
        } catch (Exception e) {
            logger.error("Failed to exchange auth code for tokens: {}", e.getMessage());
            throw new RuntimeException("Failed to exchange authorization code: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> refreshAccessToken(String refreshToken) {
        String tokenUrl = keycloakUrl + "/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("refresh_token", refreshToken);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Keycloak token refresh failed: " + response.getStatusCode());
            }
            return response.getBody();
        } catch (Exception e) {
            logger.error("Failed to refresh access token: {}", e.getMessage());
            throw new RuntimeException("Failed to refresh cloud access token: " + e.getMessage(), e);
        }
    }

    // ========== JWT Parsing ==========

    private Map<String, String> extractUserInfoFromJwt(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return Map.of("sub", "unknown", "username", "Cloud User");
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode claims = objectMapper.readTree(payload);

            String sub = claims.has("sub") ? claims.get("sub").asText() : "unknown";
            String username = claims.has("preferred_username")
                    ? claims.get("preferred_username").asText()
                    : claims.has("email") ? claims.get("email").asText() : "Cloud User";

            return Map.of("sub", sub, "username", username);
        } catch (Exception e) {
            logger.warn("Failed to parse JWT claims: {}", e.getMessage());
            return Map.of("sub", "unknown", "username", "Cloud User");
        }
    }

    // ========== Encryption (AES-GCM) ==========

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt token", e);
        }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt token", e);
        }
    }

    private static byte[] deriveKey(String password) {
        if (password == null || password.isBlank() || LEGACY_DEFAULT_ENCRYPTION_KEY.equals(password)) {
            throw new IllegalStateException("cloud-link.encryption-key must be configured");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Arrays.copyOf(digest.digest(password.getBytes(StandardCharsets.UTF_8)), 16); // AES-128
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    // ========== PKCE Helpers ==========

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate code challenge", e);
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    // ========== Exception ==========

    public static class CloudAccountNotLinkedException extends RuntimeException {
        public CloudAccountNotLinkedException(String message) {
            super(message);
        }
    }
}
