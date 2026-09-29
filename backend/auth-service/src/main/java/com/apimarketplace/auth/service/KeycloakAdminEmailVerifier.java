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
 *
 * <p>Authenticates with the {@code livecontext-admin-api} service account
 * ({@code client_credentials} on the application realm), like
 * {@link KcAdminLogoutService} and {@link AccountPurgeService}. It used to log in
 * as the master-realm {@code admin} with a password, the only runtime use of that
 * password in the app: when the master password was reset on 2026-09-19 and only
 * the GitHub copy was updated, every check failed and every new account, Google
 * included, was sent to the email-code step. The service-account secret is
 * provisioned by configure-keycloak.sh and carried to the cluster by
 * deploy-keycloak.yml, so it cannot drift the same way.
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

    @Value("${keycloak.admin.client-id:livecontext-admin-api}")
    private String adminClientId;

    @Value("${keycloak.admin.client-secret:}")
    private String adminClientSecret;

    // Local dev only: the local stack has no service-account secret, so the
    // master admin login stays as a fallback when the secret is blank. Deployed
    // environments always carry the secret and never reach this path.
    @Value("${keycloak.admin.username:admin}")
    private String keycloakAdminUsername;

    @Value("${keycloak.admin.password:admin}")
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

    /**
     * Writes the person's language onto their Keycloak user, so the pages and e-mails KEYCLOAK
     * itself produces are in it.
     *
     * <p>It is how a page reached WITHOUT the app follows the app's language. A password reset
     * starts on the Keycloak login page, where nothing of ours is running: no cookie, no session,
     * nothing but the user record. The sign-in redirect carries `ui_locales` for the cases that do
     * start in the app; this attribute is what covers the ones that do not.
     *
     * <p>Written only on an explicit pick, so an account that never opened the language menu has
     * no attribute and Keycloak falls through to `ui_locales`, then to the browser's
     * `Accept-Language`, then to the realm default.
     *
     * <p>Requires `locale` to be DECLARED in the realm's user profile (configure-keycloak.sh).
     * Since Keycloak 24 an undeclared attribute is "unmanaged" and the default policy drops it on
     * write without an error, which would make this whole method a silent no-op.
     *
     * <p>Keycloak's own locale codes, not the app's: its base theme ships {@code pt-BR} and
     * {@code zh-CN}, and an unknown code makes it fall back to the default silently.
     *
     * <p>Best-effort and never propagated, like {@link #markEmailVerified}: a language that lags
     * behind must not fail the request that changed it.
     *
     * <p>It lives in this class because this class already owns the admin
     * {@code PUT /users/{providerId}} call and the service-account token it needs; the class name
     * is narrower than what it now does.
     */
    public void setUserLocale(String providerId, String appLocale) {
        if (providerId == null || providerId.isBlank()) return;
        String kcLocale = toKeycloakLocale(appLocale);
        if (kcLocale == null) return;
        try {
            String adminToken = getAdminToken();
            String url = keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/users/" + providerId;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // READ the WHOLE representation, change one field, WRITE IT ALL BACK.
            //
            // Not a partial PUT, which is what makes this different from markEmailVerified above.
            // Sending an `attributes` map switches Keycloak's update path into "remove attributes
            // not present in the representation" (UserResource.updateUser passes
            // `rep.getAttributes() != null` as removeAttributes). Under the declarative user
            // profile, every DECLARED attribute absent from the body is then normalised to empty
            // and removed - and `username`, `email`, `firstName` and `lastName` are declared
            // (configure-keycloak.sh). A body carrying only `attributes` therefore either fails
            // validation, making this whole method a silent no-op, or succeeds and wipes the
            // person's name and address. Round-tripping the representation keeps all of them
            // present, so removeAttributes has nothing to remove.
            ResponseEntity<Map> current = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (current.getBody() == null) {
                logger.warn("Keycloak locale not set: no user representation for providerId={}", providerId);
                return;
            }
            Map<String, Object> representation = new java.util.LinkedHashMap<>(current.getBody());

            Map<String, Object> attributes = new java.util.LinkedHashMap<>();
            if (representation.get("attributes") instanceof Map<?, ?> existing) {
                existing.forEach((key, value) -> attributes.put(String.valueOf(key), value));
            }
            if (java.util.List.of(kcLocale).equals(attributes.get("locale"))) {
                return; // already what we would write
            }
            attributes.put("locale", java.util.List.of(kcLocale));
            representation.put("attributes", attributes);

            // Drop the root fields another writer owns. This is a read-modify-write, and
            // markEmailVerified above writes `emailVerified` on the very same user around signup:
            // a locale sync whose GET lands before that write and whose PUT lands after would put
            // the old value back and bounce the person to the verification step again. Keycloak
            // leaves a root field untouched when the representation omits it, so removing them
            // narrows this write to what it is actually about. The DECLARED profile attributes
            // (username, email, firstName, lastName) must stay: those are what `removeAttributes`
            // would clear.
            representation.remove("emailVerified");
            representation.remove("requiredActions");

            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(representation, headers), Void.class);
            logger.info("Keycloak locale set to {} for providerId={}", kcLocale, providerId);
        } catch (Exception e) {
            logger.warn("Failed to set Keycloak locale for providerId={}: {}", providerId, e.getMessage());
        }
    }

    /**
     * The app locale as Keycloak spells it, or null when it is not one of the six.
     *
     * <p>The REGION is dropped first, so {@code pt-BR} and {@code pt_BR} answer the same as {@code pt}
     * - which is what every other locale reader on this path does ({@code normalizeLocale} narrows the
     * same way). Without it a regional tag fell through to null, and {@code setUserLocale} returns
     * without writing when this answers null: a permanent silent no-op on the Keycloak attribute the
     * moment a regional value reaches {@code auth.users.locale}, which is eight characters wide and
     * could hold one. Unreachable today only because the single caller happens to be fed bare codes.
     *
     * <p>Narrowed HERE rather than by calling {@code normalizeLocale}, which answers English for
     * anything it does not know: that would make the {@code default -> null} below dead code and turn
     * "skip the write" into "write en", quietly, for a locale nobody chose.
     */
    public static String toKeycloakLocale(String appLocale) {
        if (appLocale == null) return null;
        String bare = appLocale.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        int dash = bare.indexOf('-');
        if (dash > 0) bare = bare.substring(0, dash);
        return switch (bare) {
            case "en" -> "en";
            case "fr" -> "fr";
            case "de" -> "de";
            case "es" -> "es";
            case "pt" -> "pt-BR";
            case "zh" -> "zh-CN";
            default -> null;
        };
    }

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

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);
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
