package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.http.CredentialHostBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The execution-time credential decision for user-created APIs (LC-002, CASA readiness), in both
 * directions of the narrow host-bound exception: a shipped integration's credential may reach that
 * integration's own concrete https hosts, and nothing else.
 */
@DisplayName("CustomApiCredentialGuard - foreign credential keys and the host-bound exception")
class CustomApiCredentialGuardTest {

    private ApiRepository apis;
    private ApiToolRepository tools;
    private CustomApiCredentialGuard guard;

    @BeforeEach
    void setUp() {
        apis = mock(ApiRepository.class);
        tools = mock(ApiToolRepository.class);
        guard = new CustomApiCredentialGuard(apis, tools, new ObjectMapper(), new PlatformOwnership("system,SYSTEM"));
    }

    private static ApiEntity api(String createdBy, String baseUrl, String key) {
        ApiEntity a = new ApiEntity();
        a.setId(UUID.randomUUID());
        a.setCreatedBy(createdBy);
        a.setBaseUrl(baseUrl);
        a.setPlatformCredentialName(key);
        a.setSource("custom");
        return a;
    }

    /** Registers a shipped (platform-owned) integration holding {@code key}. */
    private void shipped(String key, String baseUrl, String... toolSpecs) {
        ApiEntity s = api("system", baseUrl, key);
        s.setSource("import");
        when(apis.existsSharedIntegrationWithCredentialKey(key, "user-A")).thenReturn(true);
        when(apis.findIdsHoldingCredentialKeyNotCreatedBy(key, "user-A")).thenReturn(List.of(s.getId()));
        when(apis.findAllById(List.of(s.getId()))).thenReturn(List.of(s));
        List<ApiToolEntity> list = new java.util.ArrayList<>();
        for (String spec : toolSpecs) {
            ApiToolEntity t = new ApiToolEntity();
            t.setId(UUID.randomUUID());
            t.setExecutionSpec(spec);
            list.add(t);
        }
        when(tools.findByApiId(s.getId())).thenReturn(list);
    }

    @Test
    @DisplayName("allowed: https to the shipped integration's exact concrete host, bound for every request")
    void exactShippedHostIsHostBound() {
        shipped("gmail", "https://gmail.googleapis.com");

        CustomApiCredentialGuard.Decision d = guard.decide(
                api("user-A", "https://gmail.googleapis.com/gmail/v1", "gmail"), "gmail", "listMessages");

        assertThat(d.refusal()).isNull();
        assertThat(d.binding()).isNotNull();
        assertThat(d.binding().permits(URI.create("https://gmail.googleapis.com/x"))).isTrue();
        assertThat(d.binding().permits(URI.create("http://gmail.googleapis.com/x"))).as("https only").isFalse();
        assertThat(d.binding().permits(URI.create("https://evil.example.com/x"))).isFalse();
        assertThat(d.binding().permits(URI.create("https://gmail.googleapis.com.evil.com/x"))).isFalse();
        assertThat(d.binding().permits(URI.create("https://user@gmail.googleapis.com/x"))).as("no userinfo").isFalse();
    }

    @Test
    @DisplayName("refused: the same shipped key sent to the caller's own host")
    void foreignHostIsConflict() {
        shipped("gmail", "https://gmail.googleapis.com");

        CustomApiCredentialGuard.Decision d = guard.decide(
                api("user-A", "https://collect.attacker.example", "gmail"), "gmail", "listMessages");

        assertThat(d.refusal()).containsEntry("error", "credential_key_conflict");
    }

    @Test
    @DisplayName("refused: http to the right host")
    void plainHttpIsConflict() {
        shipped("gmail", "https://gmail.googleapis.com");
        assertThat(guard.decide(api("user-A", "http://gmail.googleapis.com", "gmail"), "gmail", "t").refusal())
                .containsEntry("error", "credential_key_conflict");
    }

    @Test
    @DisplayName("refused: a host under a TEMPLATED shipped base URL ({sub}.zendesk.com) is tenant-controlled")
    void templatedShippedHostIsConflict() {
        shipped("zendesk", "https://{subdomain}.zendesk.com/api/v2");
        assertThat(guard.decide(api("user-A", "https://evil.zendesk.com/api/v2", "zendesk"), "zendesk", "t").refusal())
                .containsEntry("error", "credential_key_conflict");
    }

    @Test
    @DisplayName("allowed: a declared allowedUrlHostSuffixes host; refused when that suffix covers a templated host")
    void declaredSuffixes() {
        shipped("tiktok", "https://open.tiktokapis.com",
                "{\"request\":{\"allowedUrlHostSuffixes\":[\"tiktokapis.com\"]}}");
        assertThat(guard.decide(api("user-A", "https://open-upload.tiktokapis.com", "tiktok"), "tiktok", "t").binding())
                .isNotNull();

        setUp();
        shipped("shopify", "https://{shop}.myshopify.com/admin/api",
                "{\"request\":{\"allowedUrlHostSuffixes\":[\"myshopify.com\"]}}");
        assertThat(guard.decide(api("user-A", "https://evil.myshopify.com/admin/api", "shopify"), "shopify", "t").refusal())
                .as("*.myshopify.com is any tenant's shop, so the suffix is excluded")
                .containsEntry("error", "credential_key_conflict");
    }

    @Test
    @DisplayName("refused: a native template (no shipped API row) is never host-bound")
    void nativeTemplateIsConflict() {
        when(apis.existsSharedIntegrationWithCredentialKey("imap", "user-A")).thenReturn(true);
        when(apis.findIdsHoldingCredentialKeyNotCreatedBy("imap", "user-A")).thenReturn(List.of());
        assertThat(guard.decide(api("user-A", "https://imap.example.com", "imap"), "imap", "t").refusal())
                .containsEntry("error", "credential_key_conflict");
    }

    @Test
    @DisplayName("refused: a key held by another TENANT's API is never shared, whatever the host")
    void anotherTenantsKeyIsConflict() {
        ApiEntity theirs = api("user-B", "https://api.acme.example", "acme");
        when(apis.existsSharedIntegrationWithCredentialKey("acme", "user-A")).thenReturn(true);
        when(apis.findIdsHoldingCredentialKeyNotCreatedBy("acme", "user-A")).thenReturn(List.of(theirs.getId()));
        when(apis.findAllById(List.of(theirs.getId()))).thenReturn(List.of(theirs));

        assertThat(guard.decide(api("user-A", "https://api.acme.example", "acme"), "acme", "t").refusal())
                .containsEntry("error", "credential_key_conflict");
    }

    @Test
    @DisplayName("a platform-owned API is not checked at all")
    void platformOwnedSkipsTheCheck() {
        CustomApiCredentialGuard.Decision d = guard.decide(api("system", "https://x.example", "gmail"), "gmail", "t");
        assertThat(d.refusal()).isNull();
        assertThat(d.binding()).isNull();
    }

    @Test
    @DisplayName("fail closed: an ownership lookup error refuses")
    void lookupErrorRefuses() {
        when(apis.existsSharedIntegrationWithCredentialKey(anyString(), any())).thenThrow(new RuntimeException("db down"));
        assertThat(guard.decide(api("user-A", "https://x.example", "k"), "k", "t").refusal())
                .containsEntry("error", "credential_key_unverified");
    }

    @Test
    @DisplayName("CredentialHostBinding.enforce refuses a final URL outside the binding, allows one inside")
    void enforceOnFinalUrl() {
        CredentialHostBinding.set(new CredentialHostBinding.Binding(
                "gmail", java.util.Set.of("gmail.googleapis.com"), java.util.Set.of()));
        try {
            CredentialHostBinding.enforce("https://gmail.googleapis.com/gmail/v1/users/me?q=x");
            assertThatThrownBy(() -> CredentialHostBinding.enforce("https://attacker.example/steal"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credential_key_conflict");
        } finally {
            CredentialHostBinding.clear();
        }
        CredentialHostBinding.enforce("https://attacker.example/after-clear"); // no binding, no refusal
    }
}
