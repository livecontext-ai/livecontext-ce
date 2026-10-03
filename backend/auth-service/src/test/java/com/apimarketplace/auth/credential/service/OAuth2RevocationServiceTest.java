package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("OAuth2RevocationService (LC-065)")
class OAuth2RevocationServiceTest {

    /** A literal public address: validated without any DNS lookup, so the test is hermetic. */
    private static final String REVOKE_URL = "https://8.8.8.8/revoke";

    private CredentialEncryptionService encryption;
    private CredentialAuditRecorder audit;
    private SimpleMeterRegistry meters;
    private OAuth2RevocationService service;
    private MockRestServiceServer provider;

    @BeforeEach
    void setUp() {
        encryption = mock(CredentialEncryptionService.class);
        when(encryption.decrypt(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).replace("ENC:", ""));
        audit = mock(CredentialAuditRecorder.class);
        meters = new SimpleMeterRegistry();
        // Unreachable catalog: a credential without a stored endpoint must degrade to NO_ENDPOINT.
        service = new OAuth2RevocationService(encryption, new ObjectMapper(), "http://127.0.0.1:1",
                org.springframework.web.reactive.function.client.WebClient.builder());
        service.setCollaborators(audit, meters);
        RestTemplate client = new RestTemplate();
        provider = MockRestServiceServer.bindTo(client).build();
        service.setRestTemplate(client);
    }

    private static Credential oauth(Map<String, Object> data) {
        return new Credential(7L, "user-1", "org-1", "Gmail", "gmail", CredentialType.OAuth2,
                CredentialEnvironment.Production, CredentialStatus.active, null, data,
                List.of(), List.of(), "user-1", null, true, null, Instant.now(), Instant.now());
    }

    private static Map<String, Object> tokens(String revokeUrl) {
        Map<String, Object> data = new HashMap<>();
        data.put("refresh_token", "ENC:rt-live");
        data.put("access_token", "ENC:at-live");
        data.put("oauth_client_id", "cid");
        data.put("oauth_client_secret", "ENC:csec");
        if (revokeUrl != null) {
            data.put(OAuth2RevocationService.REVOKE_URL_FIELD, revokeUrl);
            data.put(OAuth2RevocationService.TOKEN_HOST_FIELD, "8.8.8.8");
        }
        return data;
    }

    /** {@link #tokens(String)} plus a known provider-account subject (LC-065 sibling key). */
    private static Map<String, Object> tokensWithSubject(String revokeUrl, String subject) {
        Map<String, Object> data = tokens(revokeUrl);
        data.put(OAuth2RevocationService.SUBJECT_FIELD, subject);
        return data;
    }

    private double counter(String outcome) {
        var c = meters.find(OAuth2RevocationService.METRIC).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("POSTs the REFRESH token (RFC 7009) with client auth, and reports REVOKED")
    void revokesRefreshToken() {
        provider.expect(once(), requestTo(REVOKE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "token", "rt-live", "token_type_hint", "refresh_token",
                        "client_id", "cid", "client_secret", "csec")))
                .andRespond(withSuccess());

        assertThat(service.revoke(oauth(tokens(REVOKE_URL)))).isEqualTo(OAuth2RevocationService.Outcome.REVOKED);

        provider.verify();
        assertThat(counter("revoked")).isEqualTo(1);
        verify(audit).recordProviderRevocation("user-1", 7L, "gmail", "revoked", true);
    }

    @Test
    @DisplayName("falls back to the access token when no refresh token is stored")
    void fallsBackToAccessToken() {
        Map<String, Object> data = tokens(REVOKE_URL);
        data.remove("refresh_token");
        provider.expect(once(), requestTo(REVOKE_URL))
                .andExpect(content().formDataContains(Map.of("token", "at-live", "token_type_hint", "access_token")))
                .andRespond(withSuccess());

        assertThat(service.revoke(oauth(data))).isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
        provider.verify();
    }

    @Test
    @DisplayName("a 400 is final (no retry), reported FAILED and audited as a warning, never thrown")
    void clientErrorIsFinal() {
        provider.expect(once(), requestTo(REVOKE_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(service.revoke(oauth(tokens(REVOKE_URL)))).isEqualTo(OAuth2RevocationService.Outcome.FAILED);

        provider.verify();
        assertThat(counter("failed")).isEqualTo(1);
        verify(audit).recordProviderRevocation("user-1", 7L, "gmail", "failed", false);
    }

    @Test
    @DisplayName("a 503 is retried once; the second answer decides")
    void serverErrorRetried() {
        provider.expect(once(), requestTo(REVOKE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        provider.expect(once(), requestTo(REVOKE_URL)).andRespond(withSuccess());

        assertThat(service.revoke(oauth(tokens(REVOKE_URL)))).isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
        provider.verify();
    }

    @Test
    @DisplayName("a provider that keeps failing yields FAILED after two attempts, never an exception")
    void persistentFailure() {
        provider.expect(twice(), requestTo(REVOKE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(service.revoke(oauth(tokens(REVOKE_URL)))).isEqualTo(OAuth2RevocationService.Outcome.FAILED);
        provider.verify();
    }

    @Test
    @DisplayName("an internal or plain-http endpoint is never called with the token")
    void unsafeEndpointRejected() {
        assertThat(service.revoke(oauth(tokens("https://169.254.169.254/revoke"))))
                .isEqualTo(OAuth2RevocationService.Outcome.ENDPOINT_REJECTED);
        assertThat(service.revoke(oauth(tokens("http://8.8.8.8/revoke"))))
                .isEqualTo(OAuth2RevocationService.Outcome.ENDPOINT_REJECTED);
        provider.verify(); // no request was expected, none was made
        assertThat(counter("endpoint_rejected")).isEqualTo(2);
    }

    @Test
    @DisplayName("no stored endpoint and an unreachable catalog: NO_ENDPOINT, recorded, delete proceeds")
    void noEndpoint() {
        Map<String, Object> data = tokens(null);
        data.put("credential_template_id", "tmpl-x");

        assertThat(service.revoke(oauth(data))).isEqualTo(OAuth2RevocationService.Outcome.NO_ENDPOINT);
        verify(audit).recordProviderRevocation("user-1", 7L, "gmail", "no_endpoint", false);
    }

    @Test
    @DisplayName("LC-065 (item 6): the CURRENT catalog template wins over a stale STORED revoke spec")
    void currentTemplateOverridesStoredSpec() throws Exception {
        // A re-imported catalog fixed or moved the revoke endpoint after this credential connected;
        // the OLD endpoint (recorded at connect time) must never be used again once a fresh read
        // succeeds. Only when the catalog cannot be reached does the stored fallback apply.
        com.sun.net.httpserver.HttpServer catalog = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        catalog.createContext("/api/catalog/credentials/tmpl-1", exchange -> {
            String body = """
                    {"metadata":{"oauth2Config":{
                      "tokenUrl":"https://oauth2.googleapis.com/token",
                      "revokeUrl":"https://8.8.8.8/new-revoke"
                    }}}
                    """;
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        catalog.start();
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "catalogClient",
                    org.springframework.web.reactive.function.client.WebClient.builder()
                            .baseUrl("http://127.0.0.1:" + catalog.getAddress().getPort())
                            .build());

            Map<String, Object> data = tokens("https://8.8.8.8/OLD-stale-revoke");
            data.put("credential_template_id", "tmpl-1");

            provider.expect(once(), requestTo("https://8.8.8.8/new-revoke"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess());

            assertThat(service.revoke(oauth(data))).isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
            provider.verify(); // the stale stored URL was never requested
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    @DisplayName("an API-key credential, or an OAuth2 row with no token left, is NOT_APPLICABLE and not audited")
    void notApplicable() {
        Credential apiKey = new Credential(8L, "user-1", "org-1", "Key", "stripe", CredentialType.API_Key,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of("api_key", "ENC:k"),
                List.of(), List.of(), "user-1", null, true, null, Instant.now(), Instant.now());

        assertThat(service.revoke(apiKey)).isEqualTo(OAuth2RevocationService.Outcome.NOT_APPLICABLE);
        assertThat(service.revoke(oauth(Map.of(OAuth2RevocationService.REVOKE_URL_FIELD, REVOKE_URL))))
                .isEqualTo(OAuth2RevocationService.Outcome.NOT_APPLICABLE);
        assertThat(service.revoke(null)).isEqualTo(OAuth2RevocationService.Outcome.NOT_APPLICABLE);
        verify(audit, never()).recordProviderRevocation(anyString(), eq(8L), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("revokeUrl is read from both catalog template shapes (direct object, legacy wrapped string)")
    void revokeUrlFromTemplateShapes() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(OAuth2RevocationService.revokeUrlOf(om.readTree(
                "{\"metadata\":{\"oauth2Config\":{\"revokeUrl\":\"https://oauth2.googleapis.com/revoke\"}}}"), om))
                .isEqualTo("https://oauth2.googleapis.com/revoke");
        assertThat(OAuth2RevocationService.revokeUrlOf(om.readTree(
                "{\"metadata\":{\"value\":\"{\\\"oauth2Config\\\":{\\\"revokeUrl\\\":\\\"https://x.example/r\\\"}}\"}}"), om))
                .isEqualTo("https://x.example/r");
        assertThat(OAuth2RevocationService.revokeUrlOf(om.readTree("{\"metadata\":{}}"), om)).isNull();
        assertThat(OAuth2RevocationService.revokeUrlOf(null, om)).isNull();
    }

    @Test
    @DisplayName("contentType of the revocation request is form-urlencoded")
    void formEncoded() {
        provider.expect(once(), requestTo(REVOKE_URL))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andRespond(withSuccess());

        service.revoke(oauth(tokens(REVOKE_URL)));
        provider.verify();
    }

    // ─────────────── Audit follow-up: shared grant, host match, request shapes ───────────────

    private static Credential sibling(long id, String tenant, String clientId, String tokenHost, CredentialStatus status) {
        return sibling(id, tenant, clientId, tokenHost, status, null);
    }

    private static Credential sibling(long id, String tenant, String clientId, String tokenHost,
                                      CredentialStatus status, String subject) {
        Map<String, Object> data = new HashMap<>();
        data.put("refresh_token", "ENC:rt-other");
        data.put("oauth_client_id", clientId);
        if (tokenHost != null) data.put(OAuth2RevocationService.TOKEN_HOST_FIELD, tokenHost);
        if (subject != null) data.put(OAuth2RevocationService.SUBJECT_FIELD, subject);
        return new Credential(id, tenant, "org-1", "Other " + id, "google_drive", CredentialType.OAuth2,
                CredentialEnvironment.Production, status, null, data,
                List.of(), List.of(), tenant, null, false, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("shared grant: another live credential of the SAME provider account (known subject) stays -> nothing sent")
    void sharedGrantNotRevoked() {
        var survivor = sibling(8L, "user-1", "cid", "8.8.8.8", CredentialStatus.active, "acct-1");

        assertThat(service.revoke(oauth(tokensWithSubject(REVOKE_URL, "acct-1")), List.of(survivor)))
                .isEqualTo(OAuth2RevocationService.Outcome.SHARED_GRANT);

        provider.verify(); // no request expected, none made
        verify(audit).recordProviderRevocation("user-1", 7L, "gmail", "shared_grant", true);
    }

    @Test
    @DisplayName("LC-065 (item 1): cross-tenant, SAME provider account -> treated as shared, nothing sent")
    void crossTenantSameAccountNotRevoked() {
        // Two different tenants connected the SAME Google account through the platform client:
        // a provider revokes per (account x client), so revoking tenant user-1's copy would also
        // kill tenant someone-else's. auth-service owns the whole credentials table, so this MUST
        // be caught even though the surviving row belongs to another tenant.
        var survivor = sibling(8L, "someone-else", "cid", "8.8.8.8", CredentialStatus.active, "acct-shared");

        assertThat(service.revoke(oauth(tokensWithSubject(REVOKE_URL, "acct-shared")), List.of(survivor)))
                .isEqualTo(OAuth2RevocationService.Outcome.SHARED_GRANT);

        provider.verify(); // no request expected, none made
    }

    @Test
    @DisplayName("LC-065 (item 1): unknown provider subject on either side -> treated as possibly shared, nothing sent")
    void sharedGrantUnknownSubjectNotRevoked() {
        // Neither side recorded a subject (older credential, or the provider had no id_token /
        // subjectUrl): we cannot tell whether they are the same account, so we must not guess.
        var survivor = sibling(8L, "user-1", "cid", "8.8.8.8", CredentialStatus.active, null);

        assertThat(service.revoke(oauth(tokens(REVOKE_URL)), List.of(survivor)))
                .isEqualTo(OAuth2RevocationService.Outcome.SHARED_GRANT_UNKNOWN_SUBJECT);

        provider.verify(); // no request expected, none made
        verify(audit).recordProviderRevocation("user-1", 7L, "gmail", "shared_grant_unknown_subject", true);
    }

    @Test
    @DisplayName("LC-065 (item 1): same tenant, two DIFFERENT known provider accounts -> not shared, revokes")
    void sameTenantDifferentAccountsRevokes() {
        provider.expect(once(), requestTo(REVOKE_URL)).andRespond(withSuccess());
        // Same tenant, same OAuth client, same host, but a DIFFERENT Google account: not a shared
        // grant, so the sibling never blocks the revoke even though it looks identical apart from
        // the account.
        var otherAccountSameTenant = sibling(8L, "user-1", "cid", "8.8.8.8", CredentialStatus.active, "acct-2");

        assertThat(service.revoke(oauth(tokensWithSubject(REVOKE_URL, "acct-1")), List.of(otherAccountSameTenant)))
                .isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
        provider.verify();
    }

    @Test
    @DisplayName("the LAST credential of the grant revokes: siblings of another client, host, terminal status, or a different known account don't count")
    void lastOfGrantRevokes() {
        provider.expect(once(), requestTo(REVOKE_URL)).andRespond(withSuccess());
        List<Credential> notSharing = List.of(
                sibling(8L, "user-1", "another-client", "8.8.8.8", CredentialStatus.active, "acct-1"),
                sibling(9L, "user-1", "cid", "9.9.9.9", CredentialStatus.active, "acct-1"),
                sibling(10L, "user-1", "cid", "8.8.8.8", CredentialStatus.needs_reauth, "acct-1"),
                sibling(11L, "someone-else", "cid", "8.8.8.8", CredentialStatus.active, "acct-2"));

        assertThat(service.revoke(oauth(tokensWithSubject(REVOKE_URL, "acct-1")), notSharing))
                .isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
        provider.verify();
    }

    @Test
    @DisplayName("host mismatch: a self-hosted token host never sees its token sent to another domain's revoke URL")
    void hostMismatch() {
        Map<String, Object> data = tokens(REVOKE_URL);
        data.put(OAuth2RevocationService.TOKEN_HOST_FIELD, "1.1.1.1"); // e.g. gitlab.acme.com vs gitlab.com

        assertThat(service.revoke(oauth(data))).isEqualTo(OAuth2RevocationService.Outcome.HOST_MISMATCH);
        provider.verify();
    }

    @Test
    @DisplayName("registrable domain: api.github.com ~ github.com, gitlab.acme.com !~ gitlab.com, co.uk handled")
    void registrableDomains() {
        assertThat(OAuth2RevocationService.sameRegistrableDomain("api.github.com", "github.com")).isTrue();
        assertThat(OAuth2RevocationService.sameRegistrableDomain("oauth2.googleapis.com", "oauth2.googleapis.com")).isTrue();
        assertThat(OAuth2RevocationService.sameRegistrableDomain("gitlab.com", "gitlab.acme.com")).isFalse();
        assertThat(OAuth2RevocationService.sameRegistrableDomain("a.example.co.uk", "b.example.co.uk")).isTrue();
        assertThat(OAuth2RevocationService.sameRegistrableDomain("a.example.co.uk", "evil.co.uk")).isFalse();
        assertThat(OAuth2RevocationService.sameRegistrableDomain("gitlab.com", null)).isFalse();
    }

    @Test
    @DisplayName("basic_delete (GitHub): {client_id} filled in, DELETE, HTTP Basic client auth, JSON access_token")
    void basicDeleteWithClientIdPlaceholder() {
        Map<String, Object> data = tokens("https://8.8.8.8/applications/{client_id}/token");
        data.put(OAuth2RevocationService.REVOKE_METHOD_FIELD, "basic_delete");
        String basic = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("cid:csec".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        provider.expect(once(), requestTo("https://8.8.8.8/applications/cid/token"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization", basic))
                .andExpect(content().json("{\"access_token\":\"at-live\"}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(service.revoke(oauth(data))).isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
        provider.verify();
    }

    @Test
    @DisplayName("bearer (Dropbox): POST with the ACCESS token as bearer and no body")
    void bearerMethod() {
        Map<String, Object> data = tokens(REVOKE_URL);
        data.put(OAuth2RevocationService.REVOKE_METHOD_FIELD, "bearer");
        provider.expect(once(), requestTo(REVOKE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization", "Bearer at-live"))
                .andRespond(withSuccess());

        assertThat(service.revoke(oauth(data))).isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
        provider.verify();
    }

    @Test
    @DisplayName("rfc7009 with client_secret_basic: Basic header and NO client secret in the body")
    void rfc7009BasicClientAuth() {
        Map<String, Object> data = tokens(REVOKE_URL);
        data.put(OAuth2RevocationService.CLIENT_AUTH_FIELD, "basic");
        provider.expect(once(), requestTo(REVOKE_URL))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization",
                        org.hamcrest.Matchers.startsWith("Basic ")))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("token=rt-live").doesNotContain("client_secret");
                })
                .andRespond(withSuccess());

        assertThat(service.revoke(oauth(data))).isEqualTo(OAuth2RevocationService.Outcome.REVOKED);
        provider.verify();
    }

    @Test
    @DisplayName("placeholder other than {client_id}, or a host that does not resolve at use: rejected, no request")
    void unsupportedPlaceholderAndUnresolvableRejected() {
        assertThat(service.revoke(oauth(tokens("https://8.8.8.8/{tenant}/revoke"))))
                .isEqualTo(OAuth2RevocationService.Outcome.ENDPOINT_REJECTED);
        Map<String, Object> unresolvable = tokens("https://revoke.no-such-host.invalid/revoke");
        unresolvable.put(OAuth2RevocationService.TOKEN_HOST_FIELD, "revoke.no-such-host.invalid");
        assertThat(service.revoke(oauth(unresolvable)))
                .isEqualTo(OAuth2RevocationService.Outcome.ENDPOINT_REJECTED);
        provider.verify();
    }

    @Test
    @DisplayName("template spec: revokeMethod read, token host from the RESOLVED token URL, unknown method -> no spec")
    void specFromTemplate() throws Exception {
        ObjectMapper om = new ObjectMapper();
        var spec = OAuth2RevocationService.specOf(om.readTree("""
                {"metadata":{"oauth2Config":{"tokenUrl":"https://{host}/oauth/token",
                 "revokeUrl":"https://gitlab.com/oauth/revoke","authMethod":"client_secret_basic"}}}"""),
                "https://gitlab.acme.com/oauth/token", om);
        assertThat(spec.tokenHost()).isEqualTo("gitlab.acme.com");
        assertThat(spec.method()).isEqualTo("rfc7009");
        assertThat(spec.basicClientAuth()).isTrue();
        assertThat(OAuth2RevocationService.specOf(om.readTree(
                "{\"metadata\":{\"oauth2Config\":{\"revokeUrl\":\"https://x.example/r\",\"revokeMethod\":\"soap\"}}}"),
                null, om)).isNull();
    }
}
