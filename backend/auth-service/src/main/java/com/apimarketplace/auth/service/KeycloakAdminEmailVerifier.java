package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Keycloak Admin REST client for email-verification operations.
 *
 * <p>Extracted from {@link EmailVerificationService} to keep KC admin coupling
 * isolated in a single class. Gated cloud-only via {@code auth.mode=keycloak}.
 * In CE ({@code auth.mode=embedded}) this bean is absent and callers MUST guard
 * their injection with {@code @Autowired(required=false)} + null-check behind
 * {@code isEmbeddedAuth()}.
 *
 * <p>See {@code CLAUDE.md} section "Règle architecturale CE / Cloud".
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class KeycloakAdminEmailVerifier {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakAdminEmailVerifier.class);

    private final RestTemplate restTemplate;

    @Value("${keycloak.admin.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.admin.realm}")
    private String keycloakRealm;

    @Value("${keycloak.admin.username}")
    private String keycloakAdminUsername;

    @Value("${keycloak.admin.password}")
    private String keycloakAdminPassword;

    public KeycloakAdminEmailVerifier(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Returns true if the Keycloak user has {@code emailVerified=true} in the
     * admin user representation. Returns false on any HTTP/parsing failure.
     */
    public boolean isEmailVerified(String providerId) {
        try {
            String adminToken = getAdminToken();
            String url = keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/users/" + providerId;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object emailVerified = response.getBody().get("emailVerified");
                return Boolean.TRUE.equals(emailVerified);
            }
            return false;
        } catch (Exception e) {
            logger.error("Failed to check email verification status for providerId={}", providerId, e);
            return false;
        }
    }

    /**
     * Marks the Keycloak user's email as verified via PUT on the admin user
     * representation. Errors are logged but not propagated - callers treat
     * this as best-effort.
     */
    public void markEmailVerified(String providerId) {
        try {
            String adminToken = getAdminToken();
            String url = keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/users/" + providerId;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of("emailVerified", true);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
            logger.info("Email marked as verified in Keycloak for providerId={}", providerId);
        } catch (Exception e) {
            logger.error("Failed to mark email as verified in Keycloak for providerId={}", providerId, e);
        }
    }

    private String getAdminToken() {
        String tokenUrl = keycloakServerUrl + "/realms/master/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "password");
        params.add("client_id", "admin-cli");
        params.add("username", keycloakAdminUsername);
        params.add("password", keycloakAdminPassword);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return (String) response.getBody().get("access_token");
        }
        throw new RuntimeException("Failed to obtain Keycloak admin token");
    }
}
