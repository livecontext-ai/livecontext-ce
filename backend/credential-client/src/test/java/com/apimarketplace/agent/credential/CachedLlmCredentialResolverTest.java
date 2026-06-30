package com.apimarketplace.agent.credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CachedLlmCredentialResolver - unit tests")
class CachedLlmCredentialResolverTest {

    @Mock
    private LlmCredentialRepository repository;

    private CachedLlmCredentialResolver resolver;

    /**
     * Test-mutable userId source. Tests subclass {@link CachedLlmCredentialResolver}
     * and override {@code currentUserId()} via this field rather than binding a
     * servlet request - shared-agent-lib's test scope does not pull jakarta.servlet.
     */
    private String stubbedUserId;

    @BeforeEach
    void setUp() {
        stubbedUserId = null;
        resolver = new CachedLlmCredentialResolver(repository) {
            @Override
            protected String currentUserId() {
                return stubbedUserId;
            }
        };
    }

    @Test
    @DisplayName("should return DB value when present (no request bound - platform-only slot)")
    void shouldReturnDbValue() {
        when(repository.findApiKeyByProviderName((String) null, "anthropic"))
                .thenReturn(Optional.of("sk-ant-key-from-db"));

        Optional<String> result = resolver.resolveApiKey("anthropic");

        assertThat(result).isPresent().contains("sk-ant-key-from-db");
        verify(repository, times(1)).findApiKeyByProviderName((String) null, "anthropic");
    }

    @Test
    @DisplayName("should return empty when not in DB")
    void shouldReturnEmptyWhenNotInDb() {
        when(repository.findApiKeyByProviderName((String) null, "openai"))
                .thenReturn(Optional.empty());

        Optional<String> result = resolver.resolveApiKey("openai");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should cache result and not query DB again")
    void shouldCacheResult() {
        when(repository.findApiKeyByProviderName((String) null, "anthropic"))
                .thenReturn(Optional.of("sk-ant-cached"));

        // First call - hits DB
        resolver.resolveApiKey("anthropic");
        // Second call - should use cache
        Optional<String> result = resolver.resolveApiKey("anthropic");

        assertThat(result).isPresent().contains("sk-ant-cached");
        verify(repository, times(1)).findApiKeyByProviderName((String) null, "anthropic");
    }

    @Test
    @DisplayName("does NOT cache a miss - a key added right after a miss is seen on the very next resolve (no TTL wait)")
    void doesNotCacheNegativeResultSoLaterAddIsSeenImmediately() {
        // Regression for the negative-cache fix: a miss must not pin "no key" for
        // the 5-minute TTL. First resolve misses; the user then adds the key; the
        // next resolve must re-query the DB and pick it up immediately rather than
        // serving a cached empty Optional.
        when(repository.findApiKeyByProviderName((String) null, "mistral"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("sk-mistral-just-added"));

        Optional<String> first = resolver.resolveApiKey("mistral");
        Optional<String> second = resolver.resolveApiKey("mistral");

        assertThat(first).isEmpty();
        assertThat(second).contains("sk-mistral-just-added");
        verify(repository, times(2)).findApiKeyByProviderName((String) null, "mistral");
    }

    @Test
    @DisplayName("should re-query after invalidation")
    void shouldReQueryAfterInvalidation() {
        when(repository.findApiKeyByProviderName((String) null, "anthropic"))
                .thenReturn(Optional.of("old-key"))
                .thenReturn(Optional.of("new-key"));

        resolver.resolveApiKey("anthropic");
        resolver.invalidate("anthropic");
        Optional<String> result = resolver.resolveApiKey("anthropic");

        assertThat(result).isPresent().contains("new-key");
        verify(repository, times(2)).findApiKeyByProviderName((String) null, "anthropic");
    }

    @Test
    @DisplayName("should re-query all after invalidateAll")
    void shouldReQueryAfterInvalidateAll() {
        when(repository.findApiKeyByProviderName((String) null, "anthropic"))
                .thenReturn(Optional.of("key-1"))
                .thenReturn(Optional.of("key-2"));
        when(repository.findApiKeyByProviderName((String) null, "openai"))
                .thenReturn(Optional.of("oai-1"))
                .thenReturn(Optional.of("oai-2"));

        resolver.resolveApiKey("anthropic");
        resolver.resolveApiKey("openai");

        resolver.invalidateAll();

        Optional<String> anthropic = resolver.resolveApiKey("anthropic");
        Optional<String> openai = resolver.resolveApiKey("openai");

        assertThat(anthropic).contains("key-2");
        assertThat(openai).contains("oai-2");
        verify(repository, times(2)).findApiKeyByProviderName((String) null, "anthropic");
        verify(repository, times(2)).findApiKeyByProviderName((String) null, "openai");
    }

    // ── User-scoped cache (2026-05-28) ───────────────────────────────────────

    @Test
    @DisplayName("user-A and user-B get independent cache slots - A's saved key never leaks into B's resolution")
    void cacheKeyIsScopedPerUser() {
        when(repository.findApiKeyByProviderName(eq("user-A"), eq("openai")))
                .thenReturn(Optional.of("sk-A-personal-key"));
        when(repository.findApiKeyByProviderName(eq("user-B"), eq("openai")))
                .thenReturn(Optional.of("sk-B-personal-key"));

        stubbedUserId = "user-A";
        Optional<String> a = resolver.resolveApiKey("openai");

        stubbedUserId = "user-B";
        Optional<String> b = resolver.resolveApiKey("openai");

        assertThat(a).contains("sk-A-personal-key");
        assertThat(b).contains("sk-B-personal-key");
        verify(repository).findApiKeyByProviderName("user-A", "openai");
        verify(repository).findApiKeyByProviderName("user-B", "openai");
    }

    @Test
    @DisplayName("invalidate(provider) drops every per-user slot for that provider so a platform-credential edit re-resolves for all users")
    void invalidateDropsAllUserSlotsForProvider() {
        when(repository.findApiKeyByProviderName(eq("user-A"), eq("openai")))
                .thenReturn(Optional.of("v1"))
                .thenReturn(Optional.of("v2"));
        when(repository.findApiKeyByProviderName(eq("user-B"), eq("openai")))
                .thenReturn(Optional.of("v1"))
                .thenReturn(Optional.of("v2"));

        stubbedUserId = "user-A";
        resolver.resolveApiKey("openai");
        stubbedUserId = "user-B";
        resolver.resolveApiKey("openai");

        resolver.invalidate("openai");

        stubbedUserId = "user-A";
        Optional<String> aAfter = resolver.resolveApiKey("openai");
        stubbedUserId = "user-B";
        Optional<String> bAfter = resolver.resolveApiKey("openai");

        assertThat(aAfter).contains("v2");
        assertThat(bAfter).contains("v2");
        verify(repository, times(2)).findApiKeyByProviderName("user-A", "openai");
        verify(repository, times(2)).findApiKeyByProviderName("user-B", "openai");
    }
}
