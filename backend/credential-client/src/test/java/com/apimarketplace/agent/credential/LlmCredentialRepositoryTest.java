package com.apimarketplace.agent.credential;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmCredentialRepository - unit tests")
class LlmCredentialRepositoryTest {

    @Mock
    private CredentialClient credentialClient;

    private LlmCredentialRepository repository;

    @BeforeEach
    void setUp() {
        repository = new LlmCredentialRepository(credentialClient);
    }

    @Test
    @DisplayName("should return API key when found via credential client")
    void shouldReturnApiKeyWhenFound() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.of("sk-ant-real-key"));

        Optional<String> result = repository.findApiKeyByProviderName("anthropic");

        assertThat(result).isPresent().contains("sk-ant-real-key");
    }

    @Test
    @DisplayName("should return empty when no key found")
    void shouldReturnEmptyWhenNotFound() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_openai"))
                .thenReturn(Optional.empty());

        Optional<String> result = repository.findApiKeyByProviderName("openai");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return empty for unknown provider name")
    void shouldReturnEmptyForUnknownProvider() {
        Optional<String> result = repository.findApiKeyByProviderName("unknown-provider");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return empty when credential client throws exception")
    void shouldReturnEmptyOnException() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_google"))
                .thenThrow(new RuntimeException("Connection refused"));

        Optional<String> result = repository.findApiKeyByProviderName("google");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("toIntegrationName applies llm_ prefix to any non-blank provider name")
    void shouldMapProviderToIntegrationNames() {
        assertThat(LlmCredentialRepository.toIntegrationName("anthropic")).isEqualTo("llm_anthropic");
        assertThat(LlmCredentialRepository.toIntegrationName("openai")).isEqualTo("llm_openai");
        assertThat(LlmCredentialRepository.toIntegrationName("google")).isEqualTo("llm_google");
        assertThat(LlmCredentialRepository.toIntegrationName("mistral")).isEqualTo("llm_mistral");
        // Any new provider declared in YAML works without code changes - the convention is
        // "llm_" + name. A lookup for a missing credential is handled downstream, not here.
        assertThat(LlmCredentialRepository.toIntegrationName("brand-new-provider"))
                .isEqualTo("llm_brand-new-provider");
    }

    @Test
    @DisplayName("toIntegrationName rejects null or blank input")
    void shouldRejectNullOrBlankProviderNames() {
        assertThat(LlmCredentialRepository.toIntegrationName(null)).isNull();
        assertThat(LlmCredentialRepository.toIntegrationName("")).isNull();
        assertThat(LlmCredentialRepository.toIntegrationName("   ")).isNull();
    }

    @Test
    @DisplayName("toProviderName strips llm_ prefix, rejects non-prefixed input")
    void shouldReverseMapIntegrationToProviderNames() {
        assertThat(LlmCredentialRepository.toProviderName("llm_anthropic")).isEqualTo("anthropic");
        assertThat(LlmCredentialRepository.toProviderName("llm_openai")).isEqualTo("openai");
        assertThat(LlmCredentialRepository.toProviderName("llm_brand-new-provider"))
                .isEqualTo("brand-new-provider");
        // Anything without the llm_ prefix is not an LLM credential - return null.
        assertThat(LlmCredentialRepository.toProviderName("unknown")).isNull();
        assertThat(LlmCredentialRepository.toProviderName("gmail")).isNull();
        assertThat(LlmCredentialRepository.toProviderName("llm_")).isNull();
        assertThat(LlmCredentialRepository.toProviderName(null)).isNull();
    }

    @Test
    @DisplayName("hasDbKey should return true when key exists")
    void shouldReturnTrueWhenKeyExists() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.of("some-key"));

        assertThat(repository.hasDbKey("anthropic")).isTrue();
    }

    @Test
    @DisplayName("hasDbKey should return false when no key exists")
    void shouldReturnFalseWhenNoKeyExists() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_openai"))
                .thenReturn(Optional.empty());

        assertThat(repository.hasDbKey("openai")).isFalse();
    }

    @Test
    @DisplayName("hasDbKey caches result - second call within TTL does not hit client")
    void hasDbKeyCachesResult() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.of("key"));

        assertThat(repository.hasDbKey("anthropic")).isTrue();
        assertThat(repository.hasDbKey("anthropic")).isTrue();

        // Client called only once despite two hasDbKey calls
        verify(credentialClient, times(1))
                .getPlatformCredentialForIntegration("llm_anthropic");
    }

    @Test
    @DisplayName("clearHasDbKeyCache forces re-query on next hasDbKey call")
    void clearHasDbKeyCacheForcesReQuery() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.of("key"));

        // First call - caches true
        assertThat(repository.hasDbKey("anthropic")).isTrue();

        // Clear cache
        repository.clearHasDbKeyCache("anthropic");

        // Change the answer - key was deleted
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.empty());

        // Second call after eviction - must re-query and return false
        assertThat(repository.hasDbKey("anthropic")).isFalse();
        verify(credentialClient, times(2))
                .getPlatformCredentialForIntegration("llm_anthropic");
    }

    @Test
    @DisplayName("hasDbKey cache does not cross providers")
    void hasDbKeyCachePerProvider() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.of("key1"));
        when(credentialClient.getPlatformCredentialForIntegration("llm_openai"))
                .thenReturn(Optional.empty());

        assertThat(repository.hasDbKey("anthropic")).isTrue();
        assertThat(repository.hasDbKey("openai")).isFalse();

        verify(credentialClient).getPlatformCredentialForIntegration("llm_anthropic");
        verify(credentialClient).getPlatformCredentialForIntegration("llm_openai");
    }

    // ── User-first resolution (extended chain - 2026-05-28) ─────────────────────────────────

    @Test
    @DisplayName("user-default credential wins over platform - platform lookup is short-circuited (matches SendEmailNode pattern, applied to llm_<provider>)")
    void userCredentialWinsOverPlatform() {
        CredentialSummaryDto userCred = new CredentialSummaryDto();
        Map<String, Object> data = new HashMap<>();
        data.put("api_key", "sk-user-personal-key");
        userCred.setCredentialData(data);
        when(credentialClient.getDefaultCredential("user-42", "llm_openai"))
                .thenReturn(Optional.of(userCred));

        Optional<String> result = repository.findApiKeyByProviderName("user-42", "openai");

        assertThat(result).isPresent().contains("sk-user-personal-key");
        verify(credentialClient).getDefaultCredential("user-42", "llm_openai");
        // No platform lookup must occur - the user key short-circuits the chain.
        // Mockito strict-stubbing would have caught a redundant platform stub here.
        verify(credentialClient, never()).getPlatformCredentialForIntegration(any());
    }

    @Test
    @DisplayName("falls back to platform when user has no default credential for the provider")
    void platformFallbackWhenUserHasNoCredential() {
        when(credentialClient.getDefaultCredential("user-7", "llm_anthropic"))
                .thenReturn(Optional.empty());
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.of("sk-platform-fallback"));

        Optional<String> result = repository.findApiKeyByProviderName("user-7", "anthropic");

        assertThat(result).isPresent().contains("sk-platform-fallback");
        verify(credentialClient).getDefaultCredential("user-7", "llm_anthropic");
        verify(credentialClient).getPlatformCredentialForIntegration("llm_anthropic");
    }

    @Test
    @DisplayName("skips user step when userId is null (async/scheduler thread); platform key still resolves")
    void nullUserIdSkipsUserLookupAndUsesPlatform() {
        when(credentialClient.getPlatformCredentialForIntegration("llm_google"))
                .thenReturn(Optional.of("AIza-platform-key"));

        Optional<String> result = repository.findApiKeyByProviderName((String) null, "google");

        assertThat(result).isPresent().contains("AIza-platform-key");
        verify(credentialClient, never()).getDefaultCredential(any(), any());
        verify(credentialClient).getPlatformCredentialForIntegration("llm_google");
    }

    @Test
    @DisplayName("user credential with blank api_key field is treated as miss; falls through to platform")
    void blankUserApiKeyFallsThroughToPlatform() {
        CredentialSummaryDto userCred = new CredentialSummaryDto();
        Map<String, Object> data = new HashMap<>();
        data.put("api_key", "   "); // whitespace only - not a usable key
        userCred.setCredentialData(data);
        when(credentialClient.getDefaultCredential("user-9", "llm_mistral"))
                .thenReturn(Optional.of(userCred));
        when(credentialClient.getPlatformCredentialForIntegration("llm_mistral"))
                .thenReturn(Optional.of("sk-platform-mistral"));

        Optional<String> result = repository.findApiKeyByProviderName("user-9", "mistral");

        assertThat(result).isPresent().contains("sk-platform-mistral");
    }

    @Test
    @DisplayName("credentialClient.getDefaultCredential exception is swallowed; falls through to platform (best-effort)")
    void userLookupExceptionFallsThroughToPlatform() {
        when(credentialClient.getDefaultCredential("user-99", "llm_deepseek"))
                .thenThrow(new RuntimeException("auth-service down"));
        when(credentialClient.getPlatformCredentialForIntegration("llm_deepseek"))
                .thenReturn(Optional.of("sk-platform-deepseek"));

        Optional<String> result = repository.findApiKeyByProviderName("user-99", "deepseek");

        assertThat(result).isPresent().contains("sk-platform-deepseek");
        verify(credentialClient).getPlatformCredentialForIntegration("llm_deepseek");
    }

    // ── User credential mode: proxy | no_proxy (V275 2026-05-28) ──────────────────────

    @Test
    @DisplayName("mode=no_proxy (or absent) uses the user's api_key directly - the default 'I'll provide my own key' path")
    void noProxyModeUsesUserApiKey() {
        CredentialSummaryDto cred = new CredentialSummaryDto();
        Map<String, Object> data = new HashMap<>();
        data.put("api_key", "sk-user-no-proxy");
        data.put("mode", "no_proxy");
        cred.setCredentialData(data);
        when(credentialClient.getDefaultCredential("user-1", "llm_openai"))
                .thenReturn(Optional.of(cred));

        Optional<String> result = repository.findApiKeyByProviderName("user-1", "openai");

        assertThat(result).isPresent().contains("sk-user-no-proxy");
        verify(credentialClient, never()).getPlatformCredentialForIntegration(any());
    }

    @Test
    @DisplayName("mode=proxy opts the user into platform-managed routing - user's api_key is SKIPPED, platform key is used")
    void proxyModeFallsThroughToPlatformEvenIfUserApiKeyPresent() {
        CredentialSummaryDto cred = new CredentialSummaryDto();
        Map<String, Object> data = new HashMap<>();
        // Even with a valid api_key, mode=proxy means "don't use mine - route through platform".
        data.put("api_key", "sk-user-WOULD-BE-USED-IF-NO_PROXY");
        data.put("mode", "proxy");
        cred.setCredentialData(data);
        when(credentialClient.getDefaultCredential("user-2", "llm_anthropic"))
                .thenReturn(Optional.of(cred));
        when(credentialClient.getPlatformCredentialForIntegration("llm_anthropic"))
                .thenReturn(Optional.of("sk-platform-anthropic"));

        Optional<String> result = repository.findApiKeyByProviderName("user-2", "anthropic");

        assertThat(result).isPresent().contains("sk-platform-anthropic");
        verify(credentialClient).getDefaultCredential("user-2", "llm_anthropic");
        verify(credentialClient).getPlatformCredentialForIntegration("llm_anthropic");
    }

    @Test
    @DisplayName("mode field is case-insensitive - 'PROXY' (uppercase) and '  proxy  ' (padded) both route through platform")
    void proxyModeIsCaseAndWhitespaceTolerant() {
        CredentialSummaryDto cred = new CredentialSummaryDto();
        Map<String, Object> data = new HashMap<>();
        data.put("api_key", "sk-user");
        data.put("mode", "  PROXY  ");
        cred.setCredentialData(data);
        when(credentialClient.getDefaultCredential("user-3", "llm_google"))
                .thenReturn(Optional.of(cred));
        when(credentialClient.getPlatformCredentialForIntegration("llm_google"))
                .thenReturn(Optional.of("AIza-platform"));

        Optional<String> result = repository.findApiKeyByProviderName("user-3", "google");

        assertThat(result).isPresent().contains("AIza-platform");
    }

    @Test
    @DisplayName("mode field absent on legacy credential - treated as no_proxy (use api_key directly), preserving forward-compat")
    void absentModeFieldDefaultsToNoProxy() {
        CredentialSummaryDto cred = new CredentialSummaryDto();
        Map<String, Object> data = new HashMap<>();
        // No "mode" field - legacy credentials saved before V275 land here.
        data.put("api_key", "sk-legacy-user");
        cred.setCredentialData(data);
        when(credentialClient.getDefaultCredential("user-4", "llm_mistral"))
                .thenReturn(Optional.of(cred));

        Optional<String> result = repository.findApiKeyByProviderName("user-4", "mistral");

        assertThat(result).isPresent().contains("sk-legacy-user");
        verify(credentialClient, never()).getPlatformCredentialForIntegration(any());
    }
}
