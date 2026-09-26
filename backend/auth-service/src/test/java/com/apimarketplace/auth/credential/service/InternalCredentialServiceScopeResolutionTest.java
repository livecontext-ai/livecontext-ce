package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The scope preflight reads its credential through here. It used to resolve by NAME
 * alone, and a credential's name is free text the user typed: in production the two
 * Gmail credentials are called "Jaden" and "Gmail Credential" while the requirement
 * is keyed on the integration `gmail`. Nothing matched, the endpoint answered 404,
 * the caller failed open, and the guard fired 0 times while the provider refused 91
 * calls for precisely the scope gap it screens for.
 *
 * These tests pin the property that closes that hole: resolution falls back to the
 * INTEGRATION, exactly like the token path, so the preflight inspects the credential
 * the call is actually about to use.
 */
class InternalCredentialServiceScopeResolutionTest {

    private CredentialService credentialService;
    private InternalCredentialService service;

    private static Credential credential(String name, String integration, CredentialType type, List<String> scopes) {
        return new Credential(
                1L, "tenant-1", "org-1", name, integration, type, CredentialEnvironment.Production,
                CredentialStatus.active, "test", java.util.Map.of(), scopes, List.of(), "tenant-1",
                "icon", true, null, Instant.now(), Instant.now());
    }

    @BeforeEach
    void setUp() {
        credentialService = mock(CredentialService.class);
        service = new InternalCredentialService(
                credentialService,
                mock(OAuth2Service.class),
                mock(com.apimarketplace.common.security.CredentialEncryptionService.class),
                mock(PlatformCredentialService.class),
                mock(StringRedisTemplate.class));
    }

    @Test
    @DisplayName("a credential whose NAME differs from the integration is still resolved")
    void resolvesByIntegrationWhenTheNameDiffers() {
        // The production shape, verbatim: named by the user, keyed by the integration.
        Credential stored = credential("Gmail Credential", "gmail", CredentialType.OAuth2,
                List.of("https://www.googleapis.com/auth/gmail.send"));
        when(credentialService.findByNameIdentifyingIntegration(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(credentialService.getCredentialsByIntegration("tenant-1", "gmail"))
                .thenReturn(List.of(stored));

        var result = service.getCredentialScopes("tenant-1", "gmail", null);

        assertThat(result).isPresent();
        assertThat(result.get().scopes()).containsExactly("https://www.googleapis.com/auth/gmail.send");
        assertThat(result.get().integration()).isEqualTo("gmail");
        // The name is reported so a caller can tell WHICH credential answered without
        // pulling the record, whose fields come back decrypted.
        assertThat(result.get().name()).isEqualTo("Gmail Credential");
    }

    @Test
    @DisplayName("a non-OAuth2 credential reports null scopes, never an empty list")
    void nonOauth2ReportsNullScopes() {
        // An empty list would read as "zero scopes granted" and refuse every gated call;
        // null is the agreed "scopes do not apply here".
        Credential stored = credential("my key", "stripe", CredentialType.API_Key, List.of());
        when(credentialService.findByNameIdentifyingIntegration(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(credentialService.getCredentialsByIntegration("tenant-1", "stripe"))
                .thenReturn(List.of(stored));

        var result = service.getCredentialScopes("tenant-1", "stripe", null);

        assertThat(result).isPresent();
        assertThat(result.get().scopes()).isNull();
        assertThat(result.get().type()).isEqualTo("API_Key");
    }

    @Test
    @DisplayName("no credential at all stays empty, so the caller keeps failing open")
    void unresolvedStaysEmpty() {
        when(credentialService.findByNameIdentifyingIntegration(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(credentialService.getCredentialsByIntegration(anyString(), anyString()))
                .thenReturn(List.of());

        assertThat(service.getCredentialScopes("tenant-1", "gmail", null)).isEmpty();
    }

    @Test
    @DisplayName("a null user or credential name never reaches the repository")
    void nullInputsAreRefusedUpFront() {
        assertThat(service.getCredentialScopes(null, "gmail", null)).isEmpty();
        assertThat(service.getCredentialScopes("tenant-1", null, null)).isEmpty();
    }
}
