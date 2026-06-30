package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Forces a logout of all active Keycloak sessions for a user whose CE link
 * was just revoked (doc §1 #4 - REVOKE should invalidate in-flight JWTs the
 * CE was still using).
 *
 * <p>Fires from a {@link CeLinkRevokedEvent} {@link TransactionalEventListener}
 * at {@link TransactionPhase#AFTER_COMMIT}: the KC admin-API call happens out
 * of the user-facing request path AND is skipped automatically if the surrounding
 * revoke transaction rolled back (the alternative would mean force-logging out
 * sessions for a revoke that never actually persisted).
 *
 * <p>Auth: {@code client_credentials} grant against the application realm with
 * the {@code livecontext-admin-api} service-account client (created by
 * {@code configure-keycloak.sh §4b}) holding {@code realm-management/manage-users}.
 *
 * <p>Gated cloud-only via {@code auth.mode=keycloak}. In CE
 * ({@code auth.mode=embedded}) this bean is absent - the listener simply never
 * subscribes, and the revoke fires no KC call (CE has no KC).
 *
 * <p><b>Failure mode</b>: best-effort. A 404 (user already gone) or a 5xx (KC
 * unreachable) is logged at WARN but never re-thrown. The audit row from
 * {@link CeLinkService#revoke} is the durable contract; KC logout is a defense-
 * in-depth side-effect. Future PR3c.3 retention scheduler will retry stale
 * pending logouts if we observe enough KC-down windows.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class KcAdminLogoutService {

    private static final Logger log = LoggerFactory.getLogger(KcAdminLogoutService.class);

    private final RestTemplate restTemplate;
    private final UserRepository userRepository;

    @Value("${keycloak.admin.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.admin.realm}")
    private String keycloakRealm;

    @Value("${keycloak.admin.client-id:livecontext-admin-api}")
    private String adminClientId;

    @Value("${keycloak.admin.client-secret}")
    private String adminClientSecret;

    public KcAdminLogoutService(RestTemplate restTemplate, UserRepository userRepository) {
        this.restTemplate = restTemplate;
        this.userRepository = userRepository;
    }

    /**
     * Listener entry point. Resolves the cloud user → KC providerId, fetches a
     * service-account token, calls KC's logout endpoint.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRevoke(CeLinkRevokedEvent event) {
        try {
            forceLogout(event.userId(), event.installId());
        } catch (RuntimeException unexpected) {
            // Last-line safety net - the listener thread must never propagate.
            log.warn("KcAdminLogoutService.onRevoke threw for userId={} installId={}: {}",
                    event.userId(), event.installId(), unexpected.getMessage());
        }
    }

    /**
     * Visible for direct calling by admin paths (PR3c.3 squat-recovery flow
     * will reuse this). Returns true if KC accepted the logout, false otherwise.
     */
    public boolean forceLogout(Long userId, java.util.UUID installId) {
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty() || user.get().getProviderId() == null) {
            log.debug("KcAdminLogoutService: skipping logout for userId={} (user missing or non-KC)", userId);
            return false;
        }
        String providerId = user.get().getProviderId();

        String adminToken;
        try {
            adminToken = fetchServiceAccountToken();
        } catch (RuntimeException tokenFailure) {
            log.warn("KcAdminLogoutService: failed to obtain admin token for userId={} installId={} ({}) - KC sessions left alive",
                    userId, installId, tokenFailure.getMessage());
            return false;
        }

        String url = keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/users/" + providerId + "/logout";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                log.info("KC sessions logged out for userId={} (providerId={}) after ce_link revoke (installId={})",
                        userId, providerId, installId);
                return true;
            }
            log.warn("KcAdminLogoutService: KC returned {} for /users/{}/logout (userId={}, installId={})",
                    status.value(), providerId, userId, installId);
            return false;
        } catch (RestClientException kcFailure) {
            log.warn("KcAdminLogoutService: KC logout call failed for userId={} (providerId={}) installId={} - {}",
                    userId, providerId, installId, kcFailure.getMessage());
            return false;
        }
    }

    /**
     * Acquire a short-lived bearer via {@code client_credentials} on the
     * application realm. We don't cache: KC tokens are cheap and caching adds
     * a "stale on rotation" failure mode for a path that fires at most once
     * per user-initiated revoke.
     */
    private String fetchServiceAccountToken() {
        String tokenUrl = keycloakServerUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", adminClientId);
        params.add("client_secret", adminClientSecret);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("KC admin token endpoint returned " + response.getStatusCode());
        }
        Object token = response.getBody().get("access_token");
        if (!(token instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("KC admin token response missing access_token");
        }
        return s;
    }
}
