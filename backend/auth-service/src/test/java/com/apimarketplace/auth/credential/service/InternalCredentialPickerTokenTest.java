package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.common.security.CredentialEncryptionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Picker asks for a token on every open: reuse a valid stored token, refresh only when stale. */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialService.getOrRefreshOAuth2AccessTokenById")
class InternalCredentialPickerTokenTest {

    @Mock private CredentialService credentialService;
    @Mock private OAuth2Service oAuth2Service;
    @Mock private CredentialEncryptionService encryption;
    @Mock private PlatformCredentialService platform;
    @Mock private StringRedisTemplate redis;

    private InternalCredentialService service;

    @BeforeEach
    void setUp() {
        service = new InternalCredentialService(credentialService, oAuth2Service, encryption, platform, redis);
        lenient().when(encryption.decrypt(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).replace("ENC:", ""));
    }

    private static Credential cred(CredentialType type, String expiresAt) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("access_token", "ENC:stored-token");
        data.put("refresh_token", "ENC:rt");
        if (expiresAt != null) data.put("expires_at", expiresAt);
        return new Credential(10L, "u1", null, "Drive", "google_drive", type, CredentialEnvironment.Production,
                CredentialStatus.active, null, data, List.of(), List.of(), "u1", null, true,
                Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("a token valid for more than a minute is returned as stored, no provider refresh")
    void validTokenNotRefreshed() {
        when(credentialService.getCredential(10L)).thenReturn(Optional.of(
                cred(CredentialType.OAuth2, Instant.now().plusSeconds(1800).toString())));

        assertThat(service.getOrRefreshOAuth2AccessTokenById("u1", 10L, null)).contains("stored-token");
        verify(oAuth2Service, never()).refreshToken(anyLong(), any());
    }

    @Test
    @DisplayName("an expiring token is refreshed at the provider")
    void staleTokenRefreshed() {
        Credential stale = cred(CredentialType.OAuth2, Instant.now().plusSeconds(20).toString());
        when(credentialService.getCredential(10L)).thenReturn(Optional.of(stale));
        Credential refreshed = new Credential(10L, "u1", null, "Drive", "google_drive", CredentialType.OAuth2,
                CredentialEnvironment.Production, CredentialStatus.active, null,
                Map.of("access_token", "ENC:fresh-token"), List.of(), List.of(), "u1", null, true,
                Instant.now(), Instant.now(), Instant.now());
        when(oAuth2Service.refreshToken(10L, "u1")).thenReturn(refreshed);

        assertThat(service.getOrRefreshOAuth2AccessTokenById("u1", 10L, null)).contains("fresh-token");
    }

    @Test
    @DisplayName("an API-key credential never yields a token here")
    void apiKeyNever() {
        when(credentialService.getCredential(10L)).thenReturn(Optional.of(cred(CredentialType.API_Key, null)));

        assertThat(service.getOrRefreshOAuth2AccessTokenById("u1", 10L, null)).isEmpty();
        verify(oAuth2Service, never()).refreshToken(anyLong(), any());
    }
}
