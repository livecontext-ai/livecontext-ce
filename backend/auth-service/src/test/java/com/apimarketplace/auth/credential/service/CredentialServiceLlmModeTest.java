package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Switching whose key serves the caller's executions on a provider ({@code credential_data.mode}
 * of an {@code llm_<provider>} credential). The key stays saved; only the route moves.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialService.setLlmKeyModeForScope")
class CredentialServiceLlmModeTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private StringRedisTemplate redisTemplate;

    private CredentialService service;

    @BeforeEach
    void setUp() {
        service = new CredentialService(credentialRepository, redisTemplate);
    }

    private static Credential credential(String org, String integration, String mode) {
        return new Credential(
                7L, "tenant-1", org, "Anthropic", integration, CredentialType.API_Key,
                CredentialEnvironment.Production, CredentialStatus.active, null,
                Map.of("api_key", "enc:sk-ant", "mode", mode),
                List.of(), List.of(), "tenant-1", null, true, null,
                Instant.parse("2026-09-17T10:00:00Z"), Instant.parse("2026-09-17T10:00:00Z"));
    }

    @Test
    @DisplayName("switches an LLM key of the workspace and hands back the row as re-read after the write")
    void switchesTheRouteOfAnLlmKey() {
        when(credentialRepository.findById(7L))
                .thenReturn(Optional.of(credential("org-1", "llm_anthropic", "no_proxy")),
                            Optional.of(credential("org-1", "llm_anthropic", "proxy")));
        when(credentialRepository.updateLlmMode(7L, "org-1", "proxy")).thenReturn(1);

        Optional<Credential> result = service.setLlmKeyModeForScope(7L, "tenant-1", "org-1", "proxy");

        assertThat(result).isPresent();
        assertThat(result.get().credentialData()).containsEntry("mode", "proxy");
        verify(credentialRepository).updateLlmMode(7L, "org-1", "proxy");
    }

    @Test
    @DisplayName("a credential in another workspace is invisible: empty, nothing written")
    void outOfScopeIsEmpty() {
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(credential("org-OTHER", "llm_anthropic", "no_proxy")));

        assertThat(service.setLlmKeyModeForScope(7L, "tenant-1", "org-1", "proxy")).isEmpty();

        verify(credentialRepository, never()).updateLlmMode(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("only an llm_<provider> credential has a route: anything else is refused, never given a mode")
    void refusesANonLlmCredential() {
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(credential("org-1", "gmail", "no_proxy")));

        assertThatThrownBy(() -> service.setLlmKeyModeForScope(7L, "tenant-1", "org-1", "proxy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM provider key");
        verify(credentialRepository, never()).updateLlmMode(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("a mode outside no_proxy / proxy is refused before any read")
    void refusesAnUnknownMode() {
        assertThatThrownBy(() -> service.setLlmKeyModeForScope(7L, "tenant-1", "org-1", "maybe"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(credentialRepository, never()).findById(any());
    }

    @Test
    @DisplayName("a row deleted between the read and the write is empty, not an error")
    void deletedBetweenReadAndWrite() {
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(credential("org-1", "llm_openai", "proxy")));
        when(credentialRepository.updateLlmMode(7L, "org-1", "no_proxy")).thenReturn(0);

        assertThat(service.setLlmKeyModeForScope(7L, "tenant-1", "org-1", "no_proxy")).isEmpty();
    }
}
