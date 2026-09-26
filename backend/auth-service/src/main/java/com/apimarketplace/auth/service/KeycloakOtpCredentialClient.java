package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and arms a user's TOTP second factor in Keycloak, through the admin REST API.
 *
 * <p>Keycloak owns the whole TOTP lifecycle in the cloud: the secret, the QR code, the
 * verification at login and the removal all happen on Keycloak pages
 * ({@code kc_action=CONFIGURE_TOTP} and {@code kc_action=delete_credential:<id>}). This
 * client only needs a few primitives: list the second factors a user holds (authenticator
 * apps and recovery codes), delete one, read / write the user's pending required actions so
 * a platform admin can be sent through enrollment, and end the admin's sessions so that
 * happens now.
 *
 * <p>Unlike the best-effort Keycloak clients next to it, every method here THROWS on
 * failure. A status that silently read "no second factor" because Keycloak was
 * unreachable would tell a protected user they are unprotected, and would make the
 * admin enforcement re-arm a factor that is already there.
 *
 * <p>Cloud only ({@code auth.mode=keycloak}); CE has no Keycloak.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class KeycloakOtpCredentialClient {

    static final String OTP_CREDENTIAL_TYPE = "otp";
    static final String RECOVERY_CODES_CREDENTIAL_TYPE = "recovery-authn-codes";
    public static final String CONFIGURE_TOTP = "CONFIGURE_TOTP";
    public static final String CONFIGURE_RECOVERY_CODES = "CONFIGURE_RECOVERY_AUTHN_CODES";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${keycloak.admin.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.admin.realm}")
    private String keycloakRealm;

    @Value("${keycloak.admin.client-id:livecontext-admin-api}")
    private String adminClientId;

    @Value("${keycloak.admin.client-secret:}")
    private String adminClientSecret;

    // Local dev only: the local stack has no service-account secret, so the master
    // admin login stays as a fallback when the secret is blank (same contract as
    // KeycloakAdminEmailVerifier). Deployed environments always carry the secret.
    @Value("${keycloak.admin.username:admin}")
    private String keycloakAdminUsername;

    @Value("${keycloak.admin.password:admin}")
    private String keycloakAdminPassword;

    public KeycloakOtpCredentialClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** One TOTP device registered on the Keycloak account. */
    public record OtpCredential(String id, String label, Instant createdAt) {}

    /**
     * The account's set of single-use recovery codes. {@code remaining} / {@code total} are
     * null when Keycloak's credential data cannot be read (never guessed).
     */
    public record RecoveryCodes(String id, Integer remaining, Integer total) {}

    /** What the account holds as a second factor: its authenticator apps and recovery codes. */
    public record SecondFactors(List<OtpCredential> otp, RecoveryCodes recoveryCodes) {}

    /**
     * The user's authenticator apps ({@code otp}, oldest first as Keycloak returns them) and
     * recovery codes ({@code recovery-authn-codes}, null when none).
     *
     * @throws IllegalStateException when Keycloak cannot be read
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public SecondFactors listSecondFactors(String providerId) {
        ResponseEntity<List> response = restTemplate.exchange(
                userUrl(providerId) + "/credentials", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), List.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Keycloak credentials read returned " + response.getStatusCode());
        }
        List<OtpCredential> otp = new ArrayList<>();
        RecoveryCodes recovery = null;
        for (Object entry : response.getBody()) {
            if (!(entry instanceof Map credential)) continue;
            Object type = credential.get("type");
            if (OTP_CREDENTIAL_TYPE.equals(type)) {
                Object created = credential.get("createdDate");
                otp.add(new OtpCredential(
                        String.valueOf(credential.get("id")),
                        credential.get("userLabel") instanceof String label && !label.isBlank() ? label : null,
                        created instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : null));
            } else if (RECOVERY_CODES_CREDENTIAL_TYPE.equals(type) && recovery == null) {
                Map<String, Object> data = parseCredentialData(credential.get("credentialData"));
                recovery = new RecoveryCodes(
                        String.valueOf(credential.get("id")),
                        data.get("remainingCodes") instanceof Number n ? n.intValue() : null,
                        data.get("totalCodes") instanceof Number n ? n.intValue() : null);
            }
        }
        return new SecondFactors(List.copyOf(otp), recovery);
    }

    /**
     * Removes one credential from the user.
     *
     * @throws IllegalStateException when Keycloak refuses
     */
    public void deleteCredential(String providerId, String credentialId) {
        ResponseEntity<Void> response = restTemplate.exchange(
                userUrl(providerId) + "/credentials/" + credentialId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Keycloak credential delete returned " + response.getStatusCode());
        }
    }

    /** Keycloak returns credentialData as a JSON STRING; an unreadable one reads as empty. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseCredentialData(Object raw) {
        if (!(raw instanceof String json) || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * The required actions Keycloak will run at the user's next login.
     *
     * @throws IllegalStateException when Keycloak cannot be read
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<String> getRequiredActions(String providerId) {
        ResponseEntity<Map> response = restTemplate.exchange(
                userUrl(providerId), HttpMethod.GET, new HttpEntity<>(authHeaders()), Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Keycloak user read returned " + response.getStatusCode());
        }
        Object actions = response.getBody().get("requiredActions");
        List<String> result = new ArrayList<>();
        if (actions instanceof List list) {
            for (Object action : list) {
                if (action != null) result.add(action.toString());
            }
        }
        return result;
    }

    /**
     * Replaces the user's pending required actions. Keycloak's user PUT is a partial
     * update, so only {@code requiredActions} changes. Callers pass the FULL list they
     * want (read it first), never a single action, or they would drop the others.
     *
     * @throws IllegalStateException when Keycloak refuses the write
     */
    public void setRequiredActions(String providerId, List<String> requiredActions) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> response = restTemplate.exchange(
                userUrl(providerId), HttpMethod.PUT,
                new HttpEntity<>(Map.of("requiredActions", requiredActions), headers), Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Keycloak user update returned " + response.getStatusCode());
        }
    }

    /**
     * Ends every Keycloak session of the user, so their next request that needs Keycloak
     * (a token renewal, a new tab) goes through a full login and its required actions.
     *
     * @throws IllegalStateException when Keycloak refuses
     */
    public void logoutAllSessions(String providerId) {
        ResponseEntity<Void> response = restTemplate.exchange(
                userUrl(providerId) + "/logout", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Keycloak user logout returned " + response.getStatusCode());
        }
    }

    private String userUrl(String providerId) {
        return keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/users/" + providerId;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getAdminToken());
        return headers;
    }

    @SuppressWarnings("rawtypes")
    private String getAdminToken() {
        boolean serviceAccount = adminClientSecret != null && !adminClientSecret.isBlank();
        String tokenUrl = keycloakServerUrl + "/realms/" + (serviceAccount ? keycloakRealm : "master")
                + "/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (serviceAccount) {
            params.add("grant_type", "client_credentials");
            params.add("client_id", adminClientId);
            params.add("client_secret", adminClientSecret);
        } else {
            params.add("grant_type", "password");
            params.add("client_id", "admin-cli");
            params.add("username", keycloakAdminUsername);
            params.add("password", keycloakAdminPassword);
        }

        ResponseEntity<Map> response = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, new HttpEntity<>(params, headers), Map.class);
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
