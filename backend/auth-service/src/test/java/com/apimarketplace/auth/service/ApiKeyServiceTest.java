package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.ApiKeyResponse;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService Tests")
class ApiKeyServiceTest {

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
    @DisplayName("regenerateKey")
    class RegenerateKeyTests {

        @Test
        @DisplayName("should generate key with valid format starting with lc_live_")
        void regenerateKey_generatesValidFormat() {
            User user = buildUser();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(userRepository.save(any(User.class))).thenReturn(user);

            ApiKeyResponse response = apiKeyService.regenerateKey(USER_ID);

            assertThat(response.getApiKey()).startsWith("lc_live_");
            // lc_live_ (8 chars) + 64 hex chars = 72 chars total
            assertThat(response.getApiKey()).hasSize(72);
        }

        @Test
        @DisplayName("should store hash and hint on user entity")
        void regenerateKey_storesHashAndHint() {
            User user = buildUser();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(userRepository.save(any(User.class))).thenReturn(user);

            apiKeyService.regenerateKey(USER_ID);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            User saved = captor.getValue();

            assertThat(saved.getApiKeyHash()).isEqualTo(HMAC_HASH);
            assertThat(saved.getApiKeyHint()).startsWith("lc_live_...");
            assertThat(saved.getApiKeyHint()).hasSize(15); // "lc_live_..." (11) + 4 suffix chars
            assertThat(saved.getApiKeyCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should invalidate previous key by overwriting hash")
        void regenerateKey_invalidatesPreviousKey() {
            User user = buildUser();
            user.setApiKeyHash("old_hash");
            user.setApiKeyHint("lc_live_...old1");
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(userRepository.save(any(User.class))).thenReturn(user);

            apiKeyService.regenerateKey(USER_ID);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getApiKeyHash()).isEqualTo(HMAC_HASH);
            assertThat(captor.getValue().getApiKeyHash()).isNotEqualTo("old_hash");
        }

        @Test
        @DisplayName("should return response with plaintext, hint, and quotas")
        void regenerateKey_returnsCompleteResponse() {
            User user = buildUser();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(encryptionService.hmacHash(anyString())).thenReturn(HMAC_HASH);
            when(userRepository.save(any(User.class))).thenReturn(user);

            ApiKeyResponse response = apiKeyService.regenerateKey(USER_ID);

            assertThat(response.getApiKey()).isNotNull();
            assertThat(response.getMaskedApiKey()).isNotNull();
            assertThat(response.isActive()).isTrue();
            assertThat(response.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("getCurrentKeyInfo")
    class GetCurrentKeyInfoTests {

        @Test
        @DisplayName("should return hint and date when key exists")
        void getCurrentKeyInfo_returnsHintAndDate() {
            User user = buildUser();
            user.setApiKeyHint("lc_live_...ab12");
            user.setApiKeyCreatedAt(LocalDateTime.of(2026, 1, 15, 10, 0));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            ApiKeyResponse response = apiKeyService.getCurrentKeyInfo(USER_ID);

            assertThat(response.getApiKey()).isNull(); // never return plaintext on GET
            assertThat(response.getMaskedApiKey()).isEqualTo("lc_live_...ab12");
            assertThat(response.isActive()).isTrue();
            assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 0));
        }

        @Test
        @DisplayName("should return inactive when no key exists")
        void getCurrentKeyInfo_returnsNullWhenNoKey() {
            User user = buildUser();
            // No API key set
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            ApiKeyResponse response = apiKeyService.getCurrentKeyInfo(USER_ID);

            assertThat(response.getApiKey()).isNull();
            assertThat(response.getMaskedApiKey()).isNull();
            assertThat(response.isActive()).isFalse();
            assertThat(response.getCreatedAt()).isNull();
        }

        @Test
        @DisplayName("should throw when user not found")
        void getCurrentKeyInfo_throwsForUnknownUser() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> apiKeyService.getCurrentKeyInfo(999L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("resolveByPlaintextKey")
    class ResolveByPlaintextKeyTests {

        @Test
        @DisplayName("should return user resolution for valid key")
        void resolveByPlaintextKey_returnsUserForValidKey() {
            User user = buildUser();
            UserResolutionResponse expectedResponse = new UserResolutionResponse();
            expectedResponse.setUserId(USER_ID);

            when(encryptionService.hmacHash("lc_live_testkey123")).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.of(user));
            when(userResolutionService.resolveUser(PROVIDER_ID, null)).thenReturn(expectedResponse);

            UserResolutionResponse result = apiKeyService.resolveByPlaintextKey("lc_live_testkey123");

            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            verify(userResolutionService).resolveUser(PROVIDER_ID, null);
        }

        @Test
        @DisplayName("should return null for unknown key")
        void resolveByPlaintextKey_returnsNullForUnknownKey() {
            when(encryptionService.hmacHash("lc_live_unknown")).thenReturn("some_hash");
            when(userRepository.findByApiKeyHash("some_hash")).thenReturn(Optional.empty());

            UserResolutionResponse result = apiKeyService.resolveByPlaintextKey("lc_live_unknown");

            assertThat(result).isNull();
            verifyNoInteractions(userResolutionService);
        }

        @Test
        @DisplayName("should return null for disabled user")
        void resolveByPlaintextKey_returnsNullForDisabledUser() {
            User user = buildUser();
            user.setEnabled(false);

            when(encryptionService.hmacHash("lc_live_disabled")).thenReturn(HMAC_HASH);
            when(userRepository.findByApiKeyHash(HMAC_HASH)).thenReturn(Optional.of(user));

            UserResolutionResponse result = apiKeyService.resolveByPlaintextKey("lc_live_disabled");

            assertThat(result).isNull();
            verifyNoInteractions(userResolutionService);
        }

        @Test
        @DisplayName("should return null for null or blank key")
        void resolveByPlaintextKey_returnsNullForNullKey() {
            assertThat(apiKeyService.resolveByPlaintextKey(null)).isNull();
            assertThat(apiKeyService.resolveByPlaintextKey("")).isNull();
            assertThat(apiKeyService.resolveByPlaintextKey("   ")).isNull();

            verifyNoInteractions(encryptionService);
            verifyNoInteractions(userRepository);
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

}
