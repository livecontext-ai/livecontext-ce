package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ApiKey;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.CreateApiKeyResponse;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.ApiKeyRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-054: named lc_live_ keys get an expiry (default and maximum 365 days) and an expired key
 * no longer resolves. Keys created before V530 have no expiry and keep working.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService key expiry (LC-054)")
class ApiKeyServiceExpiryTest {

    @Mock private UserRepository userRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private UserResolutionService userResolutionService;
    @Mock private GatewayCacheClient gatewayCacheClient;

    private ApiKeyService service;

    private static final Long USER_ID = 42L;
    private static final String PROVIDER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final String HASH = "a".repeat(64);
    private static final String PLAINTEXT = "lc_live_" + "c".repeat(64);

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(userRepository, apiKeyRepository, encryptionService,
                userResolutionService, gatewayCacheClient);
    }

    private User user() {
        User user = new User("u", "u@example.com", AuthProvider.KEYCLOAK, PROVIDER_ID);
        user.setId(USER_ID);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        return user;
    }

    private void stubCreate() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(apiKeyRepository.countUsableByUserId(eq(USER_ID), any())).thenReturn(0L);
        when(encryptionService.hmacHash(anyString())).thenReturn(HASH);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a new key expires 365 days after creation by default, and the response says so")
    void defaultLifetimeIs365Days() {
        stubCreate();
        CreateApiKeyResponse response = service.createKey(USER_ID, "CI", null);

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(saved.capture());
        assertThat(Duration.between(saved.getValue().getCreatedAt(), saved.getValue().getExpiresAt()).toDays())
                .isEqualTo(365);
        assertThat(response.getExpiresAt()).isEqualTo(saved.getValue().getExpiresAt());
    }

    @Test
    @DisplayName("an explicit shorter lifetime is honoured")
    void explicitLifetime() {
        stubCreate();
        service.createKey(USER_ID, "CI", null, 30);
        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(saved.capture());
        assertThat(Duration.between(saved.getValue().getCreatedAt(), saved.getValue().getExpiresAt()).toDays())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("a lifetime above 365 days or below 1 day is refused before anything is written")
    void lifetimeBounds() {
        assertThatThrownBy(() -> service.createKey(USER_ID, "CI", null, 366))
                .isInstanceOf(ApiKeyValidationException.class);
        assertThatThrownBy(() -> service.createKey(USER_ID, "CI", null, 0))
                .isInstanceOf(ApiKeyValidationException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("an expired key does not resolve (the owner is never looked up)")
    void expiredKeyRefused() {
        ApiKey key = new ApiKey(USER_ID, "old", HASH, "lc_live_...cccc", null);
        key.setId(UUID.randomUUID());
        key.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(encryptionService.hmacHashCandidates(PLAINTEXT)).thenReturn(java.util.List.of(HASH));
        when(userRepository.findByApiKeyHashIn(java.util.List.of(HASH))).thenReturn(java.util.List.of());
        when(apiKeyRepository.findByKeyHashInAndRevokedAtIsNull(java.util.List.of(HASH)))
                .thenReturn(java.util.List.of(key));

        assertThat(service.resolveByPlaintextKey(PLAINTEXT)).isNull();
        verify(userResolutionService, never()).resolveUser(anyString(), any());
    }

    @Test
    @DisplayName("a key created before V530 (no expiry) and an unexpired key still resolve")
    void legacyAndLiveKeysResolve() {
        UserResolutionResponse resolution = new UserResolutionResponse();
        resolution.setUserId(USER_ID);
        when(encryptionService.hmacHashCandidates(PLAINTEXT)).thenReturn(java.util.List.of(HASH));
        when(userRepository.findByApiKeyHashIn(java.util.List.of(HASH))).thenReturn(java.util.List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(userResolutionService.resolveUser(PROVIDER_ID, null)).thenReturn(resolution);

        ApiKey legacy = new ApiKey(USER_ID, "legacy", HASH, "lc_live_...cccc", null);
        legacy.setId(UUID.randomUUID());
        when(apiKeyRepository.findByKeyHashInAndRevokedAtIsNull(java.util.List.of(HASH)))
                .thenReturn(java.util.List.of(legacy));
        assertThat(service.resolveByPlaintextKey(PLAINTEXT)).isNotNull();

        ApiKey live = new ApiKey(USER_ID, "live", HASH, "lc_live_...cccc", null);
        live.setId(UUID.randomUUID());
        live.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(apiKeyRepository.findByKeyHashInAndRevokedAtIsNull(java.util.List.of(HASH)))
                .thenReturn(java.util.List.of(live));
        assertThat(service.resolveByPlaintextKey(PLAINTEXT)).isNotNull();
    }

    @Test
    @DisplayName("regenerate gives the legacy single key a 365-day expiry, shown by /current")
    void legacyRegenerateSetsExpiry() {
        User u = user();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(encryptionService.hmacHash(anyString())).thenReturn(HASH);
        var response = service.regenerateKey(USER_ID);
        assertThat(u.getApiKeyExpiresAt()).isNotNull();
        assertThat(Duration.between(u.getApiKeyCreatedAt(), u.getApiKeyExpiresAt()).toDays()).isEqualTo(365);
        assertThat(response.getExpiresAt()).isEqualTo(u.getApiKeyExpiresAt());
    }

    @Test
    @DisplayName("an expired legacy single key does not resolve, and is reported inactive")
    void expiredLegacyKeyRefused() {
        User u = user();
        u.setApiKeyHash(HASH);
        u.setApiKeyHint("lc_live_...cccc");
        u.setApiKeyExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(encryptionService.hmacHashCandidates(PLAINTEXT)).thenReturn(java.util.List.of(HASH));
        when(userRepository.findByApiKeyHashIn(java.util.List.of(HASH))).thenReturn(java.util.List.of(u));

        assertThat(service.resolveByPlaintextKey(PLAINTEXT)).isNull();
        verify(userResolutionService, never()).resolveUser(anyString(), any());

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        assertThat(service.getCurrentKeyInfo(USER_ID).isActive()).isFalse();
    }

    @Test
    @DisplayName("the per-user key cap counts only usable keys: 20 expired keys do not block a new one")
    void capIgnoresExpiredKeys() {
        stubCreate();
        // The usable count (not revoked AND not expired) is what decides; the raw non-revoked
        // count, which would include the expired ones, is never consulted.
        org.mockito.Mockito.lenient().when(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(20L);
        service.createKey(USER_ID, "CI", null);
        verify(apiKeyRepository).save(any(ApiKey.class));
        verify(apiKeyRepository, never()).countByUserIdAndRevokedAtIsNull(USER_ID);
    }
}
