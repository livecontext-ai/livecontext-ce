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

    public void upsert(OrganizationSamlConnection connection) {
        String token = fetchServiceAccountToken();
        String alias = connection.getIdpAlias();
        String instanceUrl = adminIdentityProviderUrl(alias);
        Map<String, Object> payload = buildIdentityProviderPayload(connection);
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

    private Map<String, Object> buildIdentityProviderPayload(OrganizationSamlConnection connection) {
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
        payload.put("enabled", connection.getStatus() != OrganizationSamlConnection.Status.DISABLED);
        payload.put("trustEmail", true);
        payload.put("storeToken", false);
        payload.put("addReadTokenRoleOnCreate", false);
        payload.put("hideOnLogin", connection.isHideOnLoginPage());
        payload.put("firstBrokerLoginFlowAlias", "lc-first-broker-login");
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
