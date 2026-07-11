package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ApiKey;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.ApiKeyEntryResponse;
import com.apimarketplace.auth.dto.CreateApiKeyResponse;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.ApiKeyRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Named multi API keys (V398): creation with optional tool scopes, listing,
 * soft revocation, and resolution that attaches the key's scopes to the
 * UserResolutionResponse. The legacy single key on auth.users stays a
 * full-access key (scopes null) and is checked FIRST during resolution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService named multi keys (V398)")
class ApiKeyServiceMultiKeyTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private CredentialEncryptionService encryptionService;

    @Mock
    private UserResolutionService userResolutionService;

    @Mock
    private GatewayCacheClient gatewayCacheClient;

    private ApiKeyService apiKeyService;

    private static final Long USER_ID = 42L;
    private static final String PROVIDER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final String HMAC_HASH = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    private static final UUID KEY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(
                userRepository,
                apiKeyRepository,
                encryptionService,
                userResolutionService,
                gatewayCacheClient
        );
    }

    @Nested
    @DisplayName("createKey")
    class CreateKeyTests {

        @Test
        @DisplayName("creates a full-access key (null scopes) with lc_live_ plaintext, hash and hint")
        void createKey_fullAccess_happyPath() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(0L);
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateApiKeyResponse response = apiKeyService.createKey(USER_ID, "CI bot", null);

            assertThat(response.getApiKey()).startsWith("lc_live_").hasSize(72);
            assertThat(response.getName()).isEqualTo("CI bot");
            assertThat(response.getMaskedApiKey())
                    .isEqualTo("lc_live_..." + response.getApiKey().substring(68));
            assertThat(response.getScopes()).isNull();
            assertThat(response.getCreatedAt()).isNotNull();

            ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
            verify(apiKeyRepository).save(captor.capture());
            ApiKey saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getKeyHash()).isEqualTo(HMAC_HASH);
            assertThat(saved.getScopes()).isNull();
            assertThat(saved.getRevokedAt()).isNull();
        }

        @Test
        @DisplayName("stores scoped key with normalized comma-joined scopes and returns them as a list")
        void createKey_scoped_storesNormalizedScopes() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(0L);
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateApiKeyResponse response =
                    apiKeyService.createKey(USER_ID, "Scoped", List.of("workflow", "table"));

            assertThat(response.getScopes()).containsExactly("workflow", "table");
            ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
            verify(apiKeyRepository).save(captor.capture());
            assertThat(captor.getValue().getScopes()).isEqualTo("workflow,table");
        }

        @Test
        @DisplayName("normalizes scopes: trim, lowercase, dedupe, blanks dropped")
        void createKey_normalizesScopes() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(0L);
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateApiKeyResponse response = apiKeyService.createKey(USER_ID, "Scoped",
                    Arrays.asList(" Workflow ", "TABLE", "workflow", "", "   ", null, "table"));

            assertThat(response.getScopes()).containsExactly("workflow", "table");
        }

        @Test
        @DisplayName("rejects a missing or blank name with a validation error")
        void createKey_rejectsBlankName() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

            assertThatThrownBy(() -> apiKeyService.createKey(USER_ID, "   ", null))
                    .isInstanceOf(ApiKeyValidationException.class)
                    .hasMessageContaining("name");
            assertThatThrownBy(() -> apiKeyService.createKey(USER_ID, null, null))
                    .isInstanceOf(ApiKeyValidationException.class);
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a name longer than 100 characters")
        void createKey_rejectsTooLongName() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

            assertThatThrownBy(() -> apiKeyService.createKey(USER_ID, "x".repeat(101), null))
                    .isInstanceOf(ApiKeyValidationException.class)
                    .hasMessageContaining("100");
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("trims the name before validating and storing")
        void createKey_trimsName() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(0L);
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateApiKeyResponse response = apiKeyService.createKey(USER_ID, "  My key  ", null);

            assertThat(response.getName()).isEqualTo("My key");
        }

        @Test
        @DisplayName("rejects an EMPTY scope list: it is neither no-access nor full access")
        void createKey_rejectsEmptyScopeList() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

            assertThatThrownBy(() -> apiKeyService.createKey(USER_ID, "Scoped", List.of()))
                    .isInstanceOf(ApiKeyValidationException.class)
                    .hasMessageContaining("at least one");
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a scope list that normalizes to empty (blanks only)")
        void createKey_rejectsBlankOnlyScopeList() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

            assertThatThrownBy(() -> apiKeyService.createKey(USER_ID, "Scoped", List.of("  ", "")))
                    .isInstanceOf(ApiKeyValidationException.class);
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("caps active keys at 20 with a validation error")
        void createKey_rejectsBeyondCap() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID))
                    .thenReturn((long) ApiKeyService.MAX_ACTIVE_KEYS);

            assertThatThrownBy(() -> apiKeyService.createKey(USER_ID, "One too many", null))
                    .isInstanceOf(ApiKeyValidationException.class)
                    .hasMessageContaining("20");
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws IllegalArgumentException (404 mapping) for an unknown user")
        void createKey_unknownUser() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> apiKeyService.createKey(999L, "Key", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("busts the gateway cache for the owner after creation (regenerateKey parity)")
        void createKey_bustsGatewayCache() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(0L);
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

            apiKeyService.createKey(USER_ID, "CI bot", null);

            verify(gatewayCacheClient).invalidateUserCache(PROVIDER_ID);
        }
    }

    @Nested
    @DisplayName("listKeys")
    class ListKeysTests {

        @Test
        @DisplayName("maps active keys to entries with parsed scopes and never exposes hash or plaintext")
        void listKeys_mapsEntries() {
            ApiKey scoped = buildKey("Scoped", "workflow,table");
            ApiKey full = buildKey("Full", null);
            when(apiKeyRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(USER_ID))
                    .thenReturn(List.of(scoped, full));

            List<ApiKeyEntryResponse> entries = apiKeyService.listKeys(USER_ID);

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).getName()).isEqualTo("Scoped");
            assertThat(entries.get(0).getScopes()).containsExactly("workflow", "table");
            assertThat(entries.get(0).getMaskedApiKey()).isEqualTo("lc_live_...ab12");
            assertThat(entries.get(1).getScopes()).isNull();
        }

        @Test
        @DisplayName("returns an empty list for a user without named keys")
        void listKeys_empty() {
            when(apiKeyRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(USER_ID))
                    .thenReturn(List.of());

            assertThat(apiKeyService.listKeys(USER_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("revokeKey")
    class RevokeKeyTests {

        @Test
        @DisplayName("soft-revokes the owner's key and busts the gateway cache")
        void revokeKey_happyPath() {
            ApiKey key = buildKey("CI bot", null);
            when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(key));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

            apiKeyService.revokeKey(USER_ID, KEY_ID);

            ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
            verify(apiKeyRepository).save(captor.capture());
            assertThat(captor.getValue().getRevokedAt()).isNotNull();
            verify(gatewayCacheClient).invalidateUserCache(PROVIDER_ID);
        }

        @Test
        @DisplayName("rejects revoking a key that belongs to another user")
        void revokeKey_rejectsForeignKey() {
            ApiKey key = buildKey("Someone else's", null);
            key.setUserId(777L);
            when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(key));

            assertThatThrownBy(() -> apiKeyService.revokeKey(USER_ID, KEY_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(apiKeyRepository, never()).save(any());
            verifyNoInteractions(gatewayCacheClient);
        }

        @Test
        @DisplayName("rejects revoking an unknown key id")
        void revokeKey_rejectsUnknownKey() {
            when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> apiKeyService.revokeKey(USER_ID, KEY_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("revoking an already-revoked key is a no-op (no second save, no cache bust)")
        void revokeKey_alreadyRevokedIsNoOp() {
            ApiKey key = buildKey("Old", null);
            key.setRevokedAt(LocalDateTime.now().minusDays(1));
            when(apiKeyRepository.findById(KEY_ID)).thenReturn(Optional.of(key));

            apiKeyService.revokeKey(USER_ID, KEY_ID);

            verify(apiKeyRepository, never()).save(any());
            verifyNoInteractions(gatewayCacheClient);
        }
    }

    @Nested
    @DisplayName("resolveByPlaintextKey with multi keys")
    class ResolveTests {

        private static final String PLAINTEXT = "lc_live_" + "c".repeat(64);

        @Test
        @DisplayName("legacy users-table key still resolves FIRST with null scopes (full access)")
        void legacyKeyResolvesWithNullScopes() {
            User user = buildUser();
            UserResolutionResponse resolution = new UserResolutionResponse();
            resolution.setUserId(USER_ID);
            when(encryptionService.hmacHash(PLAINTEXT)).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.of(user));
            when(userResolutionService.resolveUser(PROVIDER_ID, null)).thenReturn(resolution);

            UserResolutionResponse result = apiKeyService.resolveByPlaintextKey(PLAINTEXT);

            assertThat(result).isNotNull();
            assertThat(result.getApiKeyScopes()).isNull();
            // The multi-key table is never consulted when the legacy key matches.
            verifyNoInteractions(apiKeyRepository);
        }

        @Test
        @DisplayName("scoped multi key resolves the owner and attaches its scope list")
        void scopedKeyResolvesWithScopes() {
            ApiKey key = buildKey("Scoped", "workflow,table");
            UserResolutionResponse resolution = new UserResolutionResponse();
            resolution.setUserId(USER_ID);
            when(encryptionService.hmacHash(PLAINTEXT)).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.empty());
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(HMAC_HASH)).thenReturn(Optional.of(key));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(userResolutionService.resolveUser(PROVIDER_ID, null)).thenReturn(resolution);

            UserResolutionResponse result = apiKeyService.resolveByPlaintextKey(PLAINTEXT);

            assertThat(result).isNotNull();
            assertThat(result.getApiKeyScopes()).containsExactly("workflow", "table");
        }

        @Test
        @DisplayName("full-access multi key (null scopes) resolves with null apiKeyScopes")
        void fullAccessMultiKeyResolvesWithNullScopes() {
            ApiKey key = buildKey("Full", null);
            UserResolutionResponse resolution = new UserResolutionResponse();
            resolution.setUserId(USER_ID);
            when(encryptionService.hmacHash(PLAINTEXT)).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.empty());
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(HMAC_HASH)).thenReturn(Optional.of(key));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(userResolutionService.resolveUser(PROVIDER_ID, null)).thenReturn(resolution);

            UserResolutionResponse result = apiKeyService.resolveByPlaintextKey(PLAINTEXT);

            assertThat(result).isNotNull();
            assertThat(result.getApiKeyScopes()).isNull();
        }

        @Test
        @DisplayName("revoked key no longer resolves (repository filters revoked_at IS NULL)")
        void revokedKeyDoesNotResolve() {
            when(encryptionService.hmacHash(PLAINTEXT)).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.empty());
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(HMAC_HASH)).thenReturn(Optional.empty());

            assertThat(apiKeyService.resolveByPlaintextKey(PLAINTEXT)).isNull();
            verifyNoInteractions(userResolutionService);
        }

        @Test
        @DisplayName("multi key of a disabled owner is rejected like the legacy path")
        void disabledOwnerRejected() {
            ApiKey key = buildKey("Scoped", "workflow");
            User disabled = buildUser();
            disabled.setEnabled(false);
            when(encryptionService.hmacHash(PLAINTEXT)).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.empty());
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(HMAC_HASH)).thenReturn(Optional.of(key));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(disabled));

            assertThat(apiKeyService.resolveByPlaintextKey(PLAINTEXT)).isNull();
            verifyNoInteractions(userResolutionService);
        }

        @Test
        @DisplayName("stamps last_used_at when never stamped before")
        void touchesLastUsedWhenNull() {
            ApiKey key = buildKey("Scoped", "workflow");
            key.setLastUsedAt(null);
            stubSuccessfulMultiKeyResolution(key);

            apiKeyService.resolveByPlaintextKey(PLAINTEXT);

            verify(apiKeyRepository).touchLastUsedAt(eq(KEY_ID), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("does NOT re-stamp last_used_at within the 15-minute throttle window")
        void skipsTouchWithinThrottleWindow() {
            ApiKey key = buildKey("Scoped", "workflow");
            key.setLastUsedAt(LocalDateTime.now().minusMinutes(5));
            stubSuccessfulMultiKeyResolution(key);

            apiKeyService.resolveByPlaintextKey(PLAINTEXT);

            verify(apiKeyRepository, never()).touchLastUsedAt(any(), any());
        }

        @Test
        @DisplayName("re-stamps last_used_at once the 15-minute window has passed")
        void touchesAgainAfterThrottleWindow() {
            ApiKey key = buildKey("Scoped", "workflow");
            key.setLastUsedAt(LocalDateTime.now().minusMinutes(16));
            stubSuccessfulMultiKeyResolution(key);

            apiKeyService.resolveByPlaintextKey(PLAINTEXT);

            verify(apiKeyRepository).touchLastUsedAt(eq(KEY_ID), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("a failing last_used_at write never fails the resolution (best-effort)")
        void touchFailureIsSwallowed() {
            ApiKey key = buildKey("Scoped", "workflow");
            key.setLastUsedAt(null);
            stubSuccessfulMultiKeyResolution(key);
            when(apiKeyRepository.touchLastUsedAt(any(), any()))
                    .thenThrow(new RuntimeException("db down"));

            UserResolutionResponse result = apiKeyService.resolveByPlaintextKey(PLAINTEXT);

            assertThat(result).isNotNull();
            assertThat(result.getApiKeyScopes()).containsExactly("workflow");
        }

        private void stubSuccessfulMultiKeyResolution(ApiKey key) {
            UserResolutionResponse resolution = new UserResolutionResponse();
            resolution.setUserId(USER_ID);
            when(encryptionService.hmacHash(PLAINTEXT)).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.empty());
            when(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(HMAC_HASH)).thenReturn(Optional.of(key));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));
            when(userResolutionService.resolveUser(PROVIDER_ID, null)).thenReturn(resolution);
        }
    }

    // ========== Helpers ==========

    private User buildUser() {
        User user = new User("testuser", "test@example.com", AuthProvider.KEYCLOAK, PROVIDER_ID);
        user.setId(USER_ID);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        return user;
    }

    private ApiKey buildKey(String name, String scopes) {
        ApiKey key = new ApiKey(USER_ID, name, HMAC_HASH, "lc_live_...ab12", scopes);
        key.setId(KEY_ID);
        return key;
    }
}
