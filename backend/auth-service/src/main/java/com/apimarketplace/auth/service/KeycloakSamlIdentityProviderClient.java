package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keycloak Admin REST adapter for organization SAML identity providers.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class KeycloakSamlIdentityProviderClient {

    private static final String SAML_USER_ATTRIBUTE_IDP_MAPPER = "saml-user-attribute-idp-mapper";

    /** Both flows are created by the realm configuration script. */
    static final String FIRST_BROKER_LOGIN_FLOW = "lc-first-broker-login";
    static final String POST_BROKER_LOGIN_FLOW = "lc-post-broker-2fa";

    /**
     * One Keycloak mapper reads ONE attribute name, and IdPs disagree on the name: ADFS and
     * Entra ID send the xmlsoap claim URIs, Okta, Google Workspace, JumpCloud and most SaaS
     * IdPs send plain names, and Shibboleth-style IdPs send the LDAP OIDs. Keycloak skips a
     * mapper whose attribute is absent from the assertion, so declaring every common spelling
     * is harmless and is what fills the profile whichever IdP the workspace connects. If an
     * IdP sends two spellings of one field with DIFFERENT values, which one wins is Keycloak's
     * mapper order, not this list's.
     * Mappers are matched by NAME on every upsert, so the three original names are kept
     * unchanged: a connection provisioned before this list grew gets them updated in place
     * and the new ones added when it is next saved. Names are imported at the first login
     * (syncMode IMPORT), so a user who already logged in keeps the profile they got then.
     */
    private static final List<SamlAttributeMapperSpec> DEFAULT_ATTRIBUTE_MAPPERS = List.of(
            new SamlAttributeMapperSpec(
                    "livecontext-email",
                    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
                    "email"),
            new SamlAttributeMapperSpec(
                    "livecontext-first-name",
                    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname",
                    "firstName"),
            new SamlAttributeMapperSpec(
                    "livecontext-last-name",
                    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname",
                    "lastName"),
            new SamlAttributeMapperSpec("livecontext-email-plain", "email", "email"),
            new SamlAttributeMapperSpec("livecontext-email-mail", "mail", "email"),
            new SamlAttributeMapperSpec("livecontext-email-oid", "urn:oid:0.9.2342.19200300.100.1.3", "email"),
            new SamlAttributeMapperSpec("livecontext-first-name-plain", "firstName", "firstName"),
            new SamlAttributeMapperSpec("livecontext-first-name-given", "givenName", "firstName"),
            new SamlAttributeMapperSpec("livecontext-first-name-oid", "urn:oid:2.5.4.42", "firstName"),
            new SamlAttributeMapperSpec("livecontext-last-name-plain", "lastName", "lastName"),
            new SamlAttributeMapperSpec("livecontext-last-name-sn", "sn", "lastName"),
            new SamlAttributeMapperSpec("livecontext-last-name-surname", "surname", "lastName"),
            new SamlAttributeMapperSpec("livecontext-last-name-oid", "urn:oid:2.5.4.4", "lastName")
    );

    private final RestTemplate restTemplate;

    @Value("${keycloak.admin.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.admin.realm}")
    private String keycloakRealm;

    @Value("${keycloak.admin.client-id:livecontext-admin-api}")
    private String adminClientId;

    @Value("${keycloak.admin.client-secret:}")
    private String adminClientSecret;

    @Value("${keycloak.issuer-uri:http://localhost:8180/realms/livecontext}")
    private String keycloakIssuerUri;

    public KeycloakSamlIdentityProviderClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Creates or updates the workspace IdP.
     *
     * @param enabled whether Keycloak may broker logins through it. The caller decides (a
     *                workspace with no verified domain, or no longer on a Team plan, gets a
     *                disabled IdP): an enabled IdP is a live login door, and until a domain is
     *                proven the admission check would refuse everyone who walks through it
     *                anyway, AFTER Keycloak already created a user holding the asserted email.
     */
    public void upsert(OrganizationSamlConnection connection, boolean enabled) {
        String token = fetchServiceAccountToken();
        String alias = connection.getIdpAlias();
        String instanceUrl = adminIdentityProviderUrl(alias);
        Map<String, Object> payload = buildIdentityProviderPayload(connection, enabled);
        HttpEntity<Map<String, Object>> entity = jsonEntity(payload, token);

        if (exists(instanceUrl, token)) {
            restTemplate.exchange(instanceUrl, HttpMethod.PUT, entity, Void.class);
        } else {
            restTemplate.exchange(adminIdentityProvidersUrl(), HttpMethod.POST, entity, Void.class);
        }
        upsertDefaultAttributeMappers(alias, token);
    }

    public void delete(String alias) {
        String token = fetchServiceAccountToken();
        String instanceUrl = adminIdentityProviderUrl(alias);
        try {
            restTemplate.exchange(instanceUrl, HttpMethod.DELETE, bearerEntity(token), Void.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
            // Idempotent delete: the DB row may exist while the KC provider was
            // manually removed. Deleting the DB row remains the right outcome.
        }
    }

    /**
     * Turns an existing IdP on or off without touching the rest of its configuration.
     * A missing IdP is left missing: there is nothing to enable, and nothing to disable.
     *
     * @return false when the IdP does not exist in Keycloak
     */
    @SuppressWarnings("unchecked")
    public boolean setEnabled(String alias, boolean enabled) {
        String token = fetchServiceAccountToken();
        String instanceUrl = adminIdentityProviderUrl(alias);
        Map<String, Object> current;
        try {
            ResponseEntity<Map> response = restTemplate.exchange(instanceUrl, HttpMethod.GET, bearerEntity(token), Map.class);
            current = response.getBody() == null ? null : new LinkedHashMap<>((Map<String, Object>) response.getBody());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw e;
        }
        if (current == null) {
            return false;
        }
        current.put("enabled", enabled);
        restTemplate.exchange(instanceUrl, HttpMethod.PUT, jsonEntity(current, token), Void.class);
        return true;
    }

    /** What {@link #releaseRefusedBrokeredUser} did to the Keycloak user. */
    public enum ReleaseOutcome {
        /** The user existed only through this IdP and held no credential: deleted. */
        DELETED,
        /** The user has another sign-in method: only the link to this IdP was removed. */
        UNLINKED,
        /** The user is gone or was never linked to this IdP: nothing to do. */
        NOTHING
    }

    /**
     * Undoes what Keycloak did for a SAML login the app refused.
     *
     * <p>Keycloak's first broker login runs BEFORE the app sees the token: by the time the
     * admission check refuses a login, a Keycloak user holding the email the IdP asserted may
     * already exist (and would block the real owner of that address from registering it), or
     * the IdP may have been linked to someone else's account. So: when {@code allowDelete}, a
     * user that exists only through this IdP (its single federated identity) and holds no
     * credential of its own is deleted; any other user only loses its link to this IdP, never
     * its account, and its sessions are ended (the refused login already holds one).
     *
     * @param allowDelete false when an app account is known to sit on this Keycloak user: the
     *                    user is then only unlinked and logged out, whatever it holds
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ReleaseOutcome releaseRefusedBrokeredUser(String keycloakUserId, String alias, boolean allowDelete) {
        if (keycloakUserId == null || keycloakUserId.isBlank() || alias == null || alias.isBlank()) {
            return ReleaseOutcome.NOTHING;
        }
        String token = fetchServiceAccountToken();
        String userUrl = adminUserUrl(keycloakUserId);
        List<Map<String, Object>> links;
        try {
            links = federatedIdentities(userUrl, token);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return ReleaseOutcome.NOTHING;
            }
            throw e;
        }
        boolean linkedToAlias = links.stream().anyMatch(l -> alias.equals(l.get("identityProvider")));
        if (!linkedToAlias) {
            return ReleaseOutcome.NOTHING;
        }
        if (allowDelete && links.size() == 1 && !hasCredential(userUrl, token)) {
            deleteIgnoringMissing(userUrl, token);
            return ReleaseOutcome.DELETED;
        }
        deleteIgnoringMissing(userUrl + "/federated-identity/" + alias, token);
        logout(userUrl, token);
        return ReleaseOutcome.UNLINKED;
    }

    /** A Keycloak user brokered by one workspace IdP, as the orphan sweep sees it. */
    public record BrokeredUser(String id, Long createdTimestamp) {
    }

    /** Aliases of every workspace SAML IdP ({@code org-<uuid>-saml}) present in the realm. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<String> listOrganizationSamlAliases() {
        String token = fetchServiceAccountToken();
        ResponseEntity<Map[]> response = restTemplate.exchange(
                adminIdentityProvidersUrl(), HttpMethod.GET, bearerEntity(token), Map[].class);
        if (response.getBody() == null) {
            return List.of();
        }
        return Arrays.stream(response.getBody())
                .map(m -> m.get("alias"))
                .filter(a -> a instanceof String s && OrganizationSamlService.isOrganizationSamlAlias(s))
                .map(String.class::cast)
                .toList();
    }

    /** One page of the users linked to {@code alias} (Keycloak's {@code idpAlias} user search). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<BrokeredUser> listUsersLinkedTo(String alias, int first, int max) {
        String token = fetchServiceAccountToken();
        String url = keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/users?idpAlias="
                + java.net.URLEncoder.encode(alias, java.nio.charset.StandardCharsets.UTF_8)
                + "&first=" + first + "&max=" + max + "&briefRepresentation=false";
        ResponseEntity<Map[]> response = restTemplate.exchange(url, HttpMethod.GET, bearerEntity(token), Map[].class);
        if (response.getBody() == null) {
            return List.of();
        }
        return Arrays.stream(response.getBody())
                .filter(m -> m.get("id") instanceof String)
                .map(m -> new BrokeredUser((String) m.get("id"),
                        m.get("createdTimestamp") instanceof Number n ? n.longValue() : null))
                .toList();
    }

    /**
     * Deletes a Keycloak user ONLY when it still exists solely through {@code alias} (one
     * federated identity, that one) and holds no credential. Re-checked here, immediately
     * before the delete, so the sweep never acts on a stale listing.
     *
     * @return true when the user was deleted
     */
    public boolean deleteIfOnlyBrokeredBy(String keycloakUserId, String alias) {
        String token = fetchServiceAccountToken();
        String userUrl = adminUserUrl(keycloakUserId);
        List<Map<String, Object>> links;
        try {
            links = federatedIdentities(userUrl, token);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw e;
        }
        if (links.size() != 1 || !alias.equals(links.get(0).get("identityProvider"))) {
            return false;
        }
        if (hasCredential(userUrl, token)) {
            return false;
        }
        deleteIgnoringMissing(userUrl, token);
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> federatedIdentities(String userUrl, String token) {
        ResponseEntity<Map[]> response = restTemplate.exchange(
                userUrl + "/federated-identity", HttpMethod.GET, bearerEntity(token), Map[].class);
        return response.getBody() == null ? List.of()
                : Arrays.stream(response.getBody()).map(m -> (Map<String, Object>) m).toList();
    }

    @SuppressWarnings("rawtypes")
    private boolean hasCredential(String userUrl, String token) {
        ResponseEntity<Map[]> credentials = restTemplate.exchange(
                userUrl + "/credentials", HttpMethod.GET, bearerEntity(token), Map[].class);
        return credentials.getBody() != null && credentials.getBody().length > 0;
    }

    private void logout(String userUrl, String token) {
        try {
            restTemplate.exchange(userUrl + "/logout", HttpMethod.POST, bearerEntity(token), Void.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
        }
    }

    private void deleteIgnoringMissing(String url, String token) {
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, bearerEntity(token), Void.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
        }
    }

    private boolean exists(String instanceUrl, String token) {
        try {
            restTemplate.exchange(instanceUrl, HttpMethod.GET, bearerEntity(token), Map.class);
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    private Map<String, Object> buildIdentityProviderPayload(OrganizationSamlConnection connection, boolean enabled) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("entityId", trimTrailingSlash(keycloakIssuerUri));
        config.put("idpEntityId", connection.getIdpEntityId());
        config.put("singleSignOnServiceUrl", connection.getSsoUrl());
        config.put("signingCertificate", connection.getX509Certificate());
        config.put("validateSignature", "true");
        config.put("principalType", "SUBJECT");
        config.put("nameIDPolicyFormat", "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress");
        config.put("postBindingAuthnRequest", "true");
        config.put("postBindingResponse", "true");
        config.put("wantAuthnRequestsSigned", "false");
        config.put("syncMode", "IMPORT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alias", connection.getIdpAlias());
        payload.put("displayName", connection.getDisplayName());
        payload.put("providerId", "saml");
        payload.put("enabled", enabled && connection.getStatus() != OrganizationSamlConnection.Status.DISABLED);
        // Never trusted: the IdP is configured by a workspace admin and asserts whatever email
        // it likes. Trusting it would mark that address verified in Keycloak and let the first
        // broker login treat it as proof of ownership. Decision (2026-09-27): it stays false.
        // A SAML user therefore arrives unverified and confirms the address through the app
        // onboarding's email code step, like a password sign-up (the realm keeps verifyEmail=false:
        // verification is the onboarding's job, see EmailVerificationService); the app must never
        // mark a SAML user verified on the IdP's word alone.
        payload.put("trustEmail", false);
        payload.put("storeToken", false);
        payload.put("addReadTokenRoleOnCreate", false);
        // Always hidden, whatever the workspace asked for: a visible button would put a
        // workspace-chosen label (any display name, any IdP URL) on the platform's own login
        // page, for every visitor. Workspace users reach it through the SSO start URL or the
        // email discovery instead.
        payload.put("hideOnLogin", true);
        payload.put("firstBrokerLoginFlowAlias", FIRST_BROKER_LOGIN_FLOW);
        // Same post broker flow as the social IdPs: an account holding a second factor is
        // still asked for it after a SAML login, instead of the IdP alone letting it in.
        payload.put("postBrokerLoginFlowAlias", POST_BROKER_LOGIN_FLOW);
        payload.put("config", config);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private void upsertDefaultAttributeMappers(String alias, String token) {
        ResponseEntity<Map[]> response = restTemplate.exchange(
                adminIdentityProviderMappersUrl(alias),
                HttpMethod.GET,
                bearerEntity(token),
                Map[].class);
        List<Map<String, Object>> existingMappers = response.getBody() == null
                ? List.of()
                : Arrays.stream(response.getBody())
                        .map(map -> (Map<String, Object>) map)
                        .toList();

        for (SamlAttributeMapperSpec spec : DEFAULT_ATTRIBUTE_MAPPERS) {
            Map<String, Object> payload = buildAttributeMapperPayload(alias, spec);
            Optional<Map<String, Object>> existing = existingMappers.stream()
                    .filter(mapper -> spec.name().equals(mapper.get("name")))
                    .findFirst();
            if (existing.isPresent() && existing.get().get("id") instanceof String id && !id.isBlank()) {
                restTemplate.exchange(adminIdentityProviderMapperUrl(alias, id), HttpMethod.PUT,
                        jsonEntity(payload, token), Void.class);
            } else {
                restTemplate.exchange(adminIdentityProviderMappersUrl(alias), HttpMethod.POST,
                        jsonEntity(payload, token), Void.class);
            }
        }
    }

    private Map<String, Object> buildAttributeMapperPayload(String alias, SamlAttributeMapperSpec spec) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("attribute.name", spec.attributeName());
        config.put("user.attribute", spec.userAttribute());
        config.put("syncMode", "INHERIT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", spec.name());
        payload.put("identityProviderAlias", alias);
        payload.put("identityProviderMapper", SAML_USER_ATTRIBUTE_IDP_MAPPER);
        payload.put("config", config);
        return payload;
    }

    private String fetchServiceAccountToken() {
        if (adminClientSecret == null || adminClientSecret.isBlank()) {
            throw new IllegalStateException("keycloak.admin.client-secret is required for SAML SSO provisioning");
        }

        String tokenUrl = keycloakServerUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", adminClientId);
        params.add("client_secret", adminClientSecret);

        ResponseEntity<Map> response = restTemplate.exchange(
                tokenUrl,
                HttpMethod.POST,
                new HttpEntity<>(params, headers),
                Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("KC admin token endpoint returned " + response.getStatusCode());
        }
        Object token = response.getBody().get("access_token");
        if (!(token instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("KC admin token response missing access_token");
        }
        return s;
    }

    private HttpEntity<Void> bearerEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String adminIdentityProvidersUrl() {
        return keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/identity-provider/instances";
    }

    private String adminIdentityProviderUrl(String alias) {
        return adminIdentityProvidersUrl() + "/" + alias;
    }

    private String adminUserUrl(String keycloakUserId) {
        return keycloakServerUrl + "/admin/realms/" + keycloakRealm + "/users/" + keycloakUserId;
    }

    private String adminIdentityProviderMappersUrl(String alias) {
        return adminIdentityProviderUrl(alias) + "/mappers";
    }

    private String adminIdentityProviderMapperUrl(String alias, String mapperId) {
        return adminIdentityProviderMappersUrl(alias) + "/" + mapperId;
    }

    private record SamlAttributeMapperSpec(String name, String attributeName, String userAttribute) {
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
