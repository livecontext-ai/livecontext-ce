package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakSamlIdentityProviderClient")
class KeycloakSamlIdentityProviderClientTest {

    private static final String ALIAS = "org-aaaaaaaabbbbccccddddeeeeeeeeeeee-saml";

    @Mock private RestTemplate restTemplate;

    private KeycloakSamlIdentityProviderClient client;

    @BeforeEach
    void setUp() {
        client = new KeycloakSamlIdentityProviderClient(restTemplate);
        ReflectionTestUtils.setField(client, "keycloakServerUrl", "https://kc.test");
        ReflectionTestUtils.setField(client, "keycloakRealm", "livecontext");
        ReflectionTestUtils.setField(client, "adminClientId", "livecontext-admin-api");
        ReflectionTestUtils.setField(client, "adminClientSecret", "test-secret");
        ReflectionTestUtils.setField(client, "keycloakIssuerUri", "https://kc.test/realms/livecontext/");
    }

    @Test
    @DisplayName("createsHiddenSamlIdentityProviderWhenAliasDoesNotExist")
    void createsHiddenSamlIdentityProviderWhenAliasDoesNotExist() {
        stubTokenFetchOk();
        when(restTemplate.exchange(
                eq(instanceUrl()),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        stubMappersFetchEmpty();

        client.upsert(connection(true));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://kc.test/admin/realms/livecontext/identity-provider/instances"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(Void.class));

        Map<String, Object> payload = entityCaptor.getValue().getBody();
        assertThat(payload).isNotNull();
        assertThat(payload).containsEntry("alias", ALIAS);
        assertThat(payload).containsEntry("providerId", "saml");
        assertThat(payload).containsEntry("displayName", "Acme SSO");
        assertThat(payload).containsEntry("trustEmail", true);
        assertThat(payload).containsEntry("hideOnLogin", true);
        assertThat(payload).containsEntry("firstBrokerLoginFlowAlias", "lc-first-broker-login");

        @SuppressWarnings("unchecked")
        Map<String, String> config = (Map<String, String>) payload.get("config");
        assertThat(config).containsEntry("entityId", "https://kc.test/realms/livecontext");
        assertThat(config).containsEntry("idpEntityId", "https://idp.example.com/metadata");
        assertThat(config).containsEntry("singleSignOnServiceUrl", "https://idp.example.com/sso");
        assertThat(config).containsEntry("signingCertificate", "AQIDBA==");
        assertThat(config).doesNotContainKey("hideOnLoginPage");
        assertThat(config).containsEntry("validateSignature", "true");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> mapperCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(3)).exchange(
                eq(mappersUrl()),
                eq(HttpMethod.POST),
                mapperCaptor.capture(),
                eq(Void.class));
        assertThat(mapperCaptor.getAllValues())
                .extracting(entity -> entity.getBody().get("name"))
                .containsExactly("livecontext-email", "livecontext-first-name", "livecontext-last-name");

        @SuppressWarnings("unchecked")
        Map<String, String> emailMapperConfig = (Map<String, String>) mapperCaptor.getAllValues().get(0).getBody().get("config");
        assertThat(emailMapperConfig).containsEntry("user.attribute", "email");
        assertThat(emailMapperConfig)
                .containsEntry("attribute.name", "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");
    }

    @Test
    @DisplayName("updatesExistingSamlIdentityProviderWhenAliasExists")
    void updatesExistingSamlIdentityProviderWhenAliasExists() {
        stubTokenFetchOk();
        when(restTemplate.exchange(
                eq(instanceUrl()),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("alias", ALIAS)));
        stubMappersFetchEmpty();

        client.upsert(connection(false));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(instanceUrl()),
                eq(HttpMethod.PUT),
                entityCaptor.capture(),
                eq(Void.class));

        Map<String, Object> payload = entityCaptor.getValue().getBody();
        assertThat(payload).containsEntry("hideOnLogin", false);
    }

    @Test
    @DisplayName("deleteIgnoresMissingIdentityProvider")
    void deleteIgnoresMissingIdentityProvider() {
        stubTokenFetchOk();
        when(restTemplate.exchange(
                eq(instanceUrl()),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(Void.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        client.delete(ALIAS);

        verify(restTemplate).exchange(eq(instanceUrl()), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTokenFetchOk() {
        when(restTemplate.exchange(
                contains("/protocol/openid-connect/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));
    }

    private void stubMappersFetchEmpty() {
        lenient().when(restTemplate.exchange(
                eq(mappersUrl()),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map[].class)))
                .thenReturn(ResponseEntity.ok(new Map[0]));
    }

    private OrganizationSamlConnection connection(boolean hideOnLoginPage) {
        User owner = new User();
        owner.setId(42L);
        Organization organization = new Organization("Acme", "acme", false, owner);
        organization.setId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

        OrganizationSamlConnection connection = new OrganizationSamlConnection(organization, ALIAS);
        connection.setDisplayName("Acme SSO");
        connection.setIdpEntityId("https://idp.example.com/metadata");
        connection.setSsoUrl("https://idp.example.com/sso");
        connection.setX509Certificate("AQIDBA==");
        connection.setHideOnLoginPage(hideOnLoginPage);
        return connection;
    }

    private String instanceUrl() {
        return "https://kc.test/admin/realms/livecontext/identity-provider/instances/" + ALIAS;
    }

    private String mappersUrl() {
        return instanceUrl() + "/mappers";
    }
}
