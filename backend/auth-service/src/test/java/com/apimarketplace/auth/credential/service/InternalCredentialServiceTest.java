package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTransientException;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialService Tests")
class InternalCredentialServiceTest {

    @Mock
    private CredentialService credentialService;

    @Mock
    private OAuth2Service oAuth2Service;

    @Mock
    private CredentialEncryptionService encryptionService;

    @Mock
    private PlatformCredentialService platformCredentialService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private InternalCredentialService service;

    private static final String USER_ID = "user-123";

    @BeforeEach
    void setUp() {
        service = new InternalCredentialService(
                credentialService, oAuth2Service, encryptionService,
                platformCredentialService, redisTemplate
        );
        // Default: no recent refresh in cache. opsForValue returns the mock value ops so that
        // markClientCredsRefreshed() doesn't NPE on success paths. Both are loose stubs - tests
        // that don't touch the client_credentials branch won't trigger them and MockitoExtension
        // won't flag them as strict-stubbing violations because we use lenient() where needed.
    }

    // ========== getAccessToken ==========

    @Nested
    @DisplayName("getAccessToken")
    class GetAccessTokenTests {

        @Test
        @DisplayName("should return empty for null userId")
        void getAccessToken_nullUserId() {
            Optional<String> result = service.getAccessToken(null, "cred-name");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for null credentialName")
        void getAccessToken_nullCredentialName() {
            Optional<String> result = service.getAccessToken(USER_ID, null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return decrypted access_token when present")
        void getAccessToken_returnsAccessToken() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:encrypted_token"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:encrypted_token")).thenReturn("decrypted_token");

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("decrypted_token");
        }

        @Test
        @DisplayName("getAccessTokenInfoById uses the selected credential id instead of name/default lookup")
        void getAccessTokenInfoById_returnsSelectedCredentialToken() {
            Credential cred = buildCredential(42L, USER_ID, "secondary",
                    Map.of("access_token", "ENC:selected_token"));
            when(credentialService.getCredential(42L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:selected_token")).thenReturn("selected_token");

            Optional<InternalCredentialService.AccessTokenInfo> result =
                    service.getAccessTokenInfoById(USER_ID, 42L, null);

            assertThat(result).isPresent();
            assertThat(result.get().accessToken()).isEqualTo("selected_token");
            verify(credentialService, never()).getCredentialByTenantAndName(anyString(), anyString());
            verify(credentialService, never()).getCredentialsByIntegration(anyString(), anyString());
        }

        @Test
        @DisplayName("should fall back to api_key when access_token is absent")
        void getAccessToken_fallsBackToApiKey() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("api_key", "ENC:enc_api_key"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_api_key")).thenReturn("my-api-key");

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("my-api-key");
        }

        @Test
        @DisplayName("should fall back to api_token when api_key is also absent")
        void getAccessToken_fallsBackToApiToken() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("api_token", "ENC:enc_api_token"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_api_token")).thenReturn("my-api-token");

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("my-api-token");
        }

        @Test
        @DisplayName("should fall back to bearer_token when other key fields are absent")
        void getAccessToken_fallsBackToBearerToken() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("bearer_token", "ENC:enc_bearer"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_bearer")).thenReturn("my-bearer");

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("my-bearer");
        }

        @Test
        @DisplayName("should try OAuth2 refresh when clientId and clientSecret are present but no direct token")
        void getAccessToken_triesOAuth2Refresh() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("client_id", "cid", "client_secret", "ENC:csec"));
            Credential refreshed = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:new_token", "client_id", "cid"));

            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:csec")).thenReturn("csec");
            when(oAuth2Service.refreshToken(1L, USER_ID)).thenReturn(refreshed);
            when(encryptionService.decrypt("ENC:new_token")).thenReturn("new_decrypted_token");

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("new_decrypted_token");
        }

        @Test
        @DisplayName("should return empty when credential not found")
        void getAccessToken_credentialNotFound() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "missing"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "missing"))
                    .thenReturn(List.of());

            Optional<String> result = service.getAccessToken(USER_ID, "missing");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when credential data is null")
        void getAccessToken_nullCredentialData() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred", null);
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should check platform credentials first for PLATFORM tenant")
        void getAccessToken_platformTenant() {
            when(platformCredentialService.getRawCredential("gmail", null))
                    .thenReturn(Optional.of(buildPlatformCredential("gmail", "ENC:platform_key")));
            when(encryptionService.decrypt("ENC:platform_key")).thenReturn("decrypted_platform_key");

            Optional<String> result = service.getAccessToken("PLATFORM", "gmail");

            assertThat(result).isPresent().contains("decrypted_platform_key");
            // Should not check user credentials when platform token found
            verifyNoInteractions(credentialService);
        }

        @Test
        @DisplayName("should fall back to user credentials for PLATFORM tenant when platform has no key")
        void getAccessToken_platformTenantFallback() {
            when(platformCredentialService.getRawCredential("unknown", null))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialByTenantAndName("PLATFORM", "unknown"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration("PLATFORM", "unknown"))
                    .thenReturn(List.of());

            Optional<String> result = service.getAccessToken("PLATFORM", "unknown");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should find by integration when name match fails")
        void getAccessToken_fallsBackToIntegration() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "gmail-credential"))
                    .thenReturn(Optional.empty());
            Credential cred = buildCredential(1L, USER_ID, "gmail",
                    Map.of("access_token", "ENC:tok"));
            when(credentialService.getCredentialsByIntegration(USER_ID, "gmail"))
                    .thenReturn(List.of(cred));
            when(encryptionService.decrypt("ENC:tok")).thenReturn("decrypted_tok");

            Optional<String> result = service.getAccessToken(USER_ID, "gmail-credential");

            assertThat(result).isPresent().contains("decrypted_tok");
        }

        @Test
        @DisplayName("should return empty when OAuth2 refresh fails")
        void getAccessToken_oauth2RefreshFails() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("client_id", "cid", "client_secret", "ENC:csec"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:csec")).thenReturn("csec");
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new RuntimeException("refresh failed"));

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
        }

        /**
         * Herd damper: when a successful refresh landed within the TTL window, subsequent
         * callers must NOT hammer the provider again. They read the persisted token on the
         * next request rather than re-entering the SETNX path.
         */
        @Test
        @DisplayName("skips OAuth2 refresh when client-creds cache is warm")
        void getAccessToken_skipsRefreshWhenCacheWarm() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("client_id", "cid", "client_secret", "ENC:csec"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:csec")).thenReturn("csec");
            when(redisTemplate.hasKey("oauth2:client-creds-cache:1")).thenReturn(Boolean.TRUE);

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
            // Herd defense: refresh must NOT be called when the cache says another caller
            // just landed one. Providers rate-limit client_credentials - extra calls risk 429.
            verify(oAuth2Service, never()).refreshToken(anyLong(), anyString());
        }

        /**
         * Terminal bucket must not trigger any fallback - OAuth2Service has already scrubbed
         * the stored access_token, and returning null/empty tells the caller to surface a
         * re-auth requirement rather than retry forever against a revoked credential.
         */
        @Test
        @DisplayName("returns empty on RefreshTerminalException (no fallback)")
        void getAccessToken_onTerminalException_returnsEmpty() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("client_id", "cid", "client_secret", "ENC:csec"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:csec")).thenReturn("csec");
            when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.FALSE);
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new RefreshTerminalException(
                            RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400,
                            "provider rejected refresh_token"));

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
        }

        /**
         * Transient bucket (5xx, 429, socket timeout). getAccessTokenInfo does NOT fall back
         * to the stored token here - that fallback belongs to forceRefreshAndGetToken. It
         * DOES set the herd-damper cache so the next 99 parallel callers don't all retry.
         */
        @Test
        @DisplayName("marks client-creds cache on RefreshTransientException to dampen herd")
        void getAccessToken_onTransientException_marksCache() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("client_id", "cid", "client_secret", "ENC:csec"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:csec")).thenReturn("csec");
            when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.FALSE);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new RefreshTransientException(
                            RefreshErrorBucket.TRANSIENT, null, 503, null, 0,
                            new RuntimeException("502 Bad Gateway")));

            Optional<String> result = service.getAccessToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
            verify(valueOps).set(eq("oauth2:client-creds-cache:1"), eq("1"), any(java.time.Duration.class));
        }
    }

    // ========== getCredentialDataMap ==========

    @Nested
    @DisplayName("getCredentialDataMap")
    class GetCredentialDataMapTests {

        @Test
        @DisplayName("should return empty map for null userId")
        void getCredentialDataMap_nullUserId() {
            Map<String, String> result = service.getCredentialDataMap(null, "cred");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty map for null credentialName")
        void getCredentialDataMap_nullCredentialName() {
            Map<String, String> result = service.getCredentialDataMap(USER_ID, null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should decrypt all string fields in credential data")
        void getCredentialDataMap_decryptsAll() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of(
                            "access_token", "ENC:enc_token",
                            "client_id", "plain_cid",
                            "some_number", 42 // non-string value should be skipped
                    ));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_token")).thenReturn("decrypted_token");
            when(encryptionService.decrypt("plain_cid")).thenReturn("plain_cid");

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "my-cred");

            assertThat(result).hasSize(2); // Only string values
            assertThat(result).containsEntry("access_token", "decrypted_token");
            assertThat(result).containsEntry("client_id", "plain_cid");
        }

        @Test
        @DisplayName("getCredentialDataMapById decrypts the selected credential data")
        void getCredentialDataMapById_decryptsSelectedCredential() {
            Credential cred = buildCredential(42L, USER_ID, "secondary",
                    Map.of("api_key", "ENC:selected_key"));
            when(credentialService.getCredential(42L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:selected_key")).thenReturn("selected_key");

            Map<String, String> result = service.getCredentialDataMapById(USER_ID, 42L, null);

            assertThat(result).containsEntry("api_key", "selected_key");
            verify(credentialService, never()).getCredentialByTenantAndName(anyString(), anyString());
            verify(credentialService, never()).getCredentialsByIntegration(anyString(), anyString());
        }

        @Test
        @DisplayName("should return empty map when credential not found")
        void getCredentialDataMap_notFound() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "missing"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "missing"))
                    .thenReturn(List.of());

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "missing");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty map when credential data is null")
        void getCredentialDataMap_nullData() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred", null);
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "my-cred");

            assertThat(result).isEmpty();
        }
    }

    // ========== getPlatformAccessToken ==========

    @Nested
    @DisplayName("getPlatformAccessToken")
    class GetPlatformAccessTokenTests {

        @Test
        @DisplayName("should return decrypted apiKey when present")
        void getPlatformAccessToken_returnsApiKey() {
            PlatformCredential cred = buildPlatformCredential("gmail", "ENC:enc_key");
            when(platformCredentialService.getRawCredential("gmail", null)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_key")).thenReturn("my_api_key");

            Optional<String> result = service.getPlatformAccessToken("gmail");

            assertThat(result).isPresent().contains("my_api_key");
        }

        @Test
        @DisplayName("should return empty when raw credential not found")
        void getPlatformAccessToken_notFound() {
            when(platformCredentialService.getRawCredential("missing", null)).thenReturn(Optional.empty());

            Optional<String> result = service.getPlatformAccessToken("missing");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when apiKey is null (OAuth2-only credential)")
        void getPlatformAccessToken_noApiKey() {
            PlatformCredential cred = new PlatformCredential(
                    1L, "gmail", "Gmail", AuthType.OAUTH2,
                    "cid", "csec", null, null, null,
                    "https://auth", "https://token", "scope",
                    "icon", "email", "desc", true,
                    Map.of(),
                    java.math.BigDecimal.ZERO,
                    500,
                    Instant.now(), Instant.now(), null, null
            );
            when(platformCredentialService.getRawCredential("gmail", null)).thenReturn(Optional.of(cred));

            Optional<String> result = service.getPlatformAccessToken("gmail");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when apiKey is blank")
        void getPlatformAccessToken_blankApiKey() {
            PlatformCredential cred = new PlatformCredential(
                    1L, "gmail", "Gmail", AuthType.API_KEY,
                    null, null, "  ", null, null,
                    null, null, null, "icon", "email", "desc", true,
                    Map.of(),
                    java.math.BigDecimal.ZERO,
                    500,
                    Instant.now(), Instant.now(), null, null
            );
            when(platformCredentialService.getRawCredential("gmail", null)).thenReturn(Optional.of(cred));

            Optional<String> result = service.getPlatformAccessToken("gmail");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("V362: with an active org, resolves via the org-aware getRawCredential(name, tenant, org)")
        void getPlatformAccessToken_orgAwareWhenOrgPresent() {
            PlatformCredential cred = buildPlatformCredential("gmail", "ENC:enc_key");
            when(platformCredentialService.getRawCredential("gmail", "tenant-1", "org-1"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_key")).thenReturn("my_api_key");

            Optional<String> result = service.getPlatformAccessToken("gmail", "tenant-1", "org-1");

            assertThat(result).isPresent().contains("my_api_key");
            verify(platformCredentialService).getRawCredential("gmail", "tenant-1", "org-1");
        }

        @Test
        @DisplayName("V362: with null org, falls back to the tenant-keyed lookup so backfilled rows resolve on un-threaded async paths")
        void getPlatformAccessToken_tenantKeyedFallbackWhenOrgNull() {
            PlatformCredential cred = buildPlatformCredential("gmail", "ENC:enc_key");
            when(platformCredentialService.getRawCredential("gmail", "tenant-1"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:enc_key")).thenReturn("my_api_key");

            Optional<String> result = service.getPlatformAccessToken("gmail", "tenant-1", null);

            assertThat(result).isPresent().contains("my_api_key");
            verify(platformCredentialService).getRawCredential("gmail", "tenant-1");
            verify(platformCredentialService, never()).getRawCredential("gmail", "tenant-1", null);
        }
    }

    // ========== forceRefreshAndGetToken ==========

    @Nested
    @DisplayName("forceRefreshAndGetToken")
    class ForceRefreshAndGetTokenTests {

        @Test
        @DisplayName("should return refreshed token on success")
        void forceRefresh_success() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred", Map.of("access_token", "old"));
            Credential refreshed = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:new_token"));

            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(1L, USER_ID)).thenReturn(refreshed);
            when(encryptionService.decrypt("ENC:new_token")).thenReturn("new_decrypted");

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("new_decrypted");
        }

        @Test
        @DisplayName("should return empty when refresh fails and no stored token is available")
        void forceRefresh_fails() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred", Map.of());
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new RuntimeException("failed"));
            // Stored credential data is empty - nothing to fall back to.
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
        }

        /**
         * When the refresh call blows up with a non-lock-contention exception (provider 5xx,
         * socket timeout, JSON parse error) we must NOT fail the user request - the persisted
         * access_token may still be valid. Returning it lets the caller's own 401-retry logic
         * take over; returning empty here short-circuits their flow with an auth error the
         * provider itself might not have surfaced.
         */
        @Test
        @DisplayName("generic refresh failure falls back to persisted access_token")
        void forceRefresh_onGenericException_fallsBackToStoredToken() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:stored"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new RuntimeException("refresh_token_failed"));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:stored")).thenReturn("stored_token");

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("stored_token");
        }

        @Test
        @DisplayName("should return empty when credential not found")
        void forceRefresh_notFound() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "missing"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "missing"))
                    .thenReturn(List.of());

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "missing");

            assertThat(result).isEmpty();
        }

        /**
         * Phase 6b race: scheduler holds the per-credential refresh lock while the reactive
         * 401-retry path arrives. OAuth2Service signals this as
         * {@code IllegalStateException("refresh_in_progress")}. We must not bubble that to the
         * HTTP caller - instead, back off, re-read the credential, and surface whatever token
         * the winning refresher has persisted by now. Otherwise the user's request fails with
         * auth_expired while a successful refresh is happening 400 ms away.
         */
        @Test
        @DisplayName("refresh_in_progress: reads the winning refresher's fresh access_token after backoff")
        void forceRefresh_onLockContention_readsFreshToken() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred", Map.of("access_token", "ENC:stale"));
            Credential afterWinner = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:fresh_from_winner"));

            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new IllegalStateException("refresh_in_progress"));
            // After our 400 ms backoff the winning caller has written the fresh token.
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(afterWinner));
            when(encryptionService.decrypt("ENC:fresh_from_winner")).thenReturn("fresh_from_winner");

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("fresh_from_winner");
            // We must not have hammered the token endpoint a second time when the winner
            // has already produced a usable token.
            verify(oAuth2Service, times(1)).refreshToken(1L, USER_ID);
        }

        /**
         * RefreshTerminalException: credential has been flipped to needs_reauth/error and the
         * stored access_token has been scrubbed. We must NOT call readLatestAccessToken - the
         * only thing that could return is null. Empty tells the caller to surface re-auth.
         */
        @Test
        @DisplayName("returns empty on RefreshTerminalException without fallback")
        void forceRefresh_onTerminalException_returnsEmptyWithoutFallback() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:stored"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new RefreshTerminalException(
                            RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400,
                            "provider rejected refresh_token"));

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
            // No fallback read - access_token is scrubbed upstream; re-reading it would be
            // pointless and would mislead callers into retrying against a revoked credential.
            verify(credentialService, never()).getCredential(anyLong());
        }

        /**
         * RefreshTransientException: provider 5xx / 429 / timeout. The stored access_token may
         * still be valid for a few minutes, so we fall back to it. If it's stale the caller's
         * 401-retry path handles it.
         */
        @Test
        @DisplayName("falls back to stored access_token on RefreshTransientException")
        void forceRefresh_onTransientException_fallsBackToStored() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:stored"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new RefreshTransientException(
                            RefreshErrorBucket.TRANSIENT, null, 503, null, 1,
                            new RuntimeException("503 Service Unavailable")));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:stored")).thenReturn("stored_token");

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("stored_token");
        }

        /**
         * Lock-contention → 400 ms backoff → re-read credential: if the winning refresher
         * flipped the credential to needs_reauth (invalid_grant during the race), we must NOT
         * retry the refresh. Retrying would trip the authoritativeGate and waste a SETNX
         * round-trip; worse, in a tight 100-way fan-in it would spam that gate for every
         * loser of the race.
         */
        @Test
        @DisplayName("refresh_in_progress + terminal flip during race: no retry, returns empty")
        void forceRefresh_onLockContention_terminalFlipDuringRace_skipsRetry() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:stale"));
            Credential afterRaceTerminal = buildCredentialWithStatus(1L, USER_ID, "my-cred",
                    Map.of(), CredentialStatus.needs_reauth);

            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new IllegalStateException("refresh_in_progress"));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(afterRaceTerminal));

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isEmpty();
            // Exactly one refresh attempt - no retry once the race winner landed a terminal.
            verify(oAuth2Service, times(1)).refreshToken(1L, USER_ID);
        }

        /**
         * Same race, but the winning refresher hasn't persisted yet. We fall through to a
         * second refresh attempt ourselves (the lock should be free by now) and return whatever
         * that produces. Failure on the second attempt is swallowed so the reactive caller
         * gets a graceful empty instead of a stacktrace.
         */
        @Test
        @DisplayName("refresh_in_progress: retries refresh when fresh token not yet persisted")
        void forceRefresh_onLockContention_retriesWhenStoreHasNoFreshToken() {
            Credential cred = buildCredential(1L, USER_ID, "my-cred", Map.of("access_token", "ENC:stale"));
            Credential refreshedBySelf = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:self_refreshed"));

            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            // First attempt: lock busy. Second attempt: we own it, succeeds.
            when(oAuth2Service.refreshToken(1L, USER_ID))
                    .thenThrow(new IllegalStateException("refresh_in_progress"))
                    .thenReturn(refreshedBySelf);
            // Winner hasn't written yet - fallback read returns a credential whose
            // access_token is blank, which getDecryptedField treats as absent (no decrypt
            // call). So we only need to stub decrypt for the successful self-refresh path.
            Credential stillStale = buildCredential(1L, USER_ID, "my-cred",
                    Map.of("access_token", "   "));
            when(credentialService.getCredential(1L)).thenReturn(Optional.of(stillStale));
            when(encryptionService.decrypt("ENC:self_refreshed")).thenReturn("self_refreshed");

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "my-cred");

            assertThat(result).isPresent().contains("self_refreshed");
            verify(oAuth2Service, times(2)).refreshToken(1L, USER_ID);
        }
    }

    // ========== Org-aware resolution (workspace-shared credentials) ==========

    @Nested
    @DisplayName("Org-aware resolution")
    class OrgAwareResolutionTests {

        private static final String ORG_ID = "org-1";
        private static final String OTHER_MEMBER = "user-999";

        @Test
        @DisplayName("resolves the workspace-shared credential when the executing user has none of their own")
        void orgSharedCredentialResolvedWhenExecutingUserHasNone() {
            // Reproduces the prod report: a workflow owned by user A runs under A's
            // identity, but the Twitter credential was connected by member B and shared
            // in the workspace. The executing user A has no twitter credential of their own.
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twitter"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "twitter"))
                    .thenReturn(List.of());
            Credential orgCred = buildCredential(242L, OTHER_MEMBER, "Twitter / X Credential",
                    Map.of("access_token", "ENC:org_token"));
            when(credentialService.getCredentialsByIntegrationForScope(USER_ID, ORG_ID, "twitter"))
                    .thenReturn(List.of(orgCred));
            when(encryptionService.decrypt("ENC:org_token")).thenReturn("org_token");

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "twitter", ORG_ID);

            assertThat(result).containsEntry("access_token", "org_token");
        }

        @Test
        @DisplayName("getAccessTokenInfo resolves the workspace-shared token via the org fallback")
        void getAccessTokenInfoResolvesOrgSharedToken() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twitter"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "twitter"))
                    .thenReturn(List.of());
            Credential orgCred = buildCredential(242L, OTHER_MEMBER, "Twitter / X Credential",
                    Map.of("access_token", "ENC:org_token"));
            when(credentialService.getCredentialsByIntegrationForScope(USER_ID, ORG_ID, "twitter"))
                    .thenReturn(List.of(orgCred));
            when(encryptionService.decrypt("ENC:org_token")).thenReturn("org_token");

            Optional<InternalCredentialService.AccessTokenInfo> result =
                    service.getAccessTokenInfo(USER_ID, "twitter", ORG_ID);

            assertThat(result).isPresent();
            assertThat(result.get().accessToken()).isEqualTo("org_token");
        }

        @Test
        @DisplayName("the executing user's own credential takes precedence - org fallback is not consulted")
        void ownCredentialTakesPrecedenceOverOrgShared() {
            Credential own = buildCredential(1L, USER_ID, "twitter",
                    Map.of("access_token", "ENC:own_token"));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twitter"))
                    .thenReturn(Optional.of(own));
            when(encryptionService.decrypt("ENC:own_token")).thenReturn("own_token");

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "twitter", ORG_ID);

            assertThat(result).containsEntry("access_token", "own_token");
            verify(credentialService, never())
                    .getCredentialsByIntegrationForScope(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("no org fallback when organizationId is absent - back-compat tenant-only resolution")
        void noOrgFallbackWhenOrganizationIdMissing() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twitter"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "twitter"))
                    .thenReturn(List.of());

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "twitter", null);

            assertThat(result).isEmpty();
            verify(credentialService, never())
                    .getCredentialsByIntegrationForScope(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("org fallback skips inactive workspace credentials")
        void orgFallbackSkipsInactiveCredentials() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twitter"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "twitter"))
                    .thenReturn(List.of());
            Credential inactive = buildCredentialWithStatus(242L, OTHER_MEMBER, "Twitter / X Credential",
                    Map.of("access_token", "ENC:org_token"), CredentialStatus.needs_reauth);
            when(credentialService.getCredentialsByIntegrationForScope(USER_ID, ORG_ID, "twitter"))
                    .thenReturn(List.of(inactive));

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "twitter", ORG_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("forceRefreshAndGetToken refreshes the workspace-shared credential via the org fallback")
        void forceRefreshUsesOrgSharedCredential() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twitter"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "twitter"))
                    .thenReturn(List.of());
            Credential orgCred = buildCredential(242L, OTHER_MEMBER, "Twitter / X Credential",
                    Map.of("access_token", "old"));
            Credential refreshed = buildCredential(242L, OTHER_MEMBER, "Twitter / X Credential",
                    Map.of("access_token", "ENC:new_token"));
            when(credentialService.getCredentialsByIntegrationForScope(USER_ID, ORG_ID, "twitter"))
                    .thenReturn(List.of(orgCred));
            // Refresh runs under the credential OWNER's id (cred.tenantId() == OTHER_MEMBER),
            // not the executing user - proving workspace-shared refresh targets the right tenant.
            when(oAuth2Service.refreshToken(242L, OTHER_MEMBER)).thenReturn(refreshed);
            when(encryptionService.decrypt("ENC:new_token")).thenReturn("new_decrypted");

            Optional<String> result = service.forceRefreshAndGetToken(USER_ID, "twitter", ORG_ID);

            assertThat(result).isPresent().contains("new_decrypted");
            verify(oAuth2Service).refreshToken(242L, OTHER_MEMBER);
        }
    }

    // ========== last_used stamping (settings "Last used" column) ==========

    @Nested
    @DisplayName("last_used stamping on execution resolution")
    class LastUsedStampingTests {

        private static final String ORG_ID = "org-1";
        private static final String OTHER_MEMBER = "user-999";

        @Test
        @DisplayName("data-map resolution stamps last_used for a Basic-auth credential that never refreshes (Twilio)")
        void dataMapResolutionStampsLastUsedForBasicAuthCredential() {
            // Reproduces the prod report: a Twilio (Basic-auth: username=AccountSid,
            // password=AuthToken) credential is resolved and handed to the outbound call,
            // but last_used stayed empty because only the OAuth2-refresh path stamped it.
            Credential twilio = buildCredentialWithLastUsed(243L, USER_ID, "Twilio",
                    Map.of("username", "ENC:sid", "password", "ENC:token"), null);
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twilio"))
                    .thenReturn(Optional.of(twilio));
            when(encryptionService.decrypt("ENC:sid")).thenReturn("ACxxxx");
            when(encryptionService.decrypt("ENC:token")).thenReturn("authtoken");

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "twilio", ORG_ID);

            assertThat(result).containsEntry("username", "ACxxxx").containsEntry("password", "authtoken");
            verify(credentialService).touchLastUsed(243L);
        }

        @Test
        @DisplayName("data-map by-id resolution stamps last_used on the workflow-selected credential")
        void dataMapByIdResolutionStampsLastUsed() {
            Credential cred = buildCredentialWithLastUsed(243L, USER_ID, "Twilio",
                    Map.of("api_key", "ENC:key"), null);
            when(credentialService.getCredential(243L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:key")).thenReturn("plain-key");

            service.getCredentialDataMapById(USER_ID, 243L, ORG_ID);

            verify(credentialService).touchLastUsed(243L);
        }

        @Test
        @DisplayName("access-token resolution stamps last_used for an API-key credential")
        void accessTokenResolutionStampsLastUsed() {
            Credential cred = buildCredentialWithLastUsed(7L, USER_ID, "my-cred",
                    Map.of("api_key", "ENC:key"), null);
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:key")).thenReturn("plain-key");

            service.getAccessToken(USER_ID, "my-cred");

            verify(credentialService).touchLastUsed(7L);
        }

        @Test
        @DisplayName("access-token by-id resolution stamps last_used on the workflow-selected credential")
        void accessTokenByIdResolutionStampsLastUsed() {
            Credential cred = buildCredentialWithLastUsed(243L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:tok"), null);
            when(credentialService.getCredential(243L)).thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:tok")).thenReturn("plain-tok");

            service.getAccessTokenInfoById(USER_ID, 243L, ORG_ID);

            verify(credentialService).touchLastUsed(243L);
        }

        @Test
        @DisplayName("force-refresh resolution stamps last_used (transient fallback returns the stored token = a real use)")
        void forceRefreshResolutionStampsLastUsed() {
            Credential cred = buildCredentialWithLastUsed(243L, USER_ID, "my-cred",
                    Map.of("access_token", "ENC:tok"), null);
            when(credentialService.getCredentialByTenantAndName(USER_ID, "my-cred"))
                    .thenReturn(Optional.of(cred));
            when(oAuth2Service.refreshToken(243L, USER_ID)).thenReturn(cred);
            when(encryptionService.decrypt("ENC:tok")).thenReturn("plain-tok");

            service.forceRefreshAndGetToken(USER_ID, "my-cred", ORG_ID);

            verify(credentialService).touchLastUsed(243L);
        }

        @Test
        @DisplayName("org-shared credential resolved cross-member is stamped (was the empty-last_used case in prod)")
        void orgSharedCredentialResolutionStampsLastUsed() {
            // Workflow owned by USER_ID, credential owned by OTHER_MEMBER, same workspace.
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twilio"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "twilio"))
                    .thenReturn(List.of());
            Credential orgCred = buildCredentialWithLastUsed(243L, OTHER_MEMBER, "Twilio",
                    Map.of("password", "ENC:token"), null);
            when(credentialService.getCredentialsByIntegrationForScope(USER_ID, ORG_ID, "twilio"))
                    .thenReturn(List.of(orgCred));
            when(encryptionService.decrypt("ENC:token")).thenReturn("authtoken");

            service.getCredentialDataMap(USER_ID, "twilio", ORG_ID);

            // The credential that was actually resolved (the org-shared one, id 243) is stamped.
            verify(credentialService).touchLastUsed(243L);
        }

        @Test
        @DisplayName("throttle: a credential used again within the window is NOT re-stamped")
        void lastUsedStampThrottledWhenRecentlyUsed() {
            Credential recentlyUsed = buildCredentialWithLastUsed(243L, USER_ID, "Twilio",
                    Map.of("api_key", "ENC:key"), Instant.now().minusSeconds(5));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twilio"))
                    .thenReturn(Optional.of(recentlyUsed));
            when(encryptionService.decrypt("ENC:key")).thenReturn("plain-key");

            service.getCredentialDataMap(USER_ID, "twilio", null);

            // Hot workflows resolve a credential per call - within the throttle window we must
            // not amplify writes on the same row.
            verify(credentialService, never()).touchLastUsed(anyLong());
        }

        @Test
        @DisplayName("throttle: a credential last used before the window IS re-stamped")
        void lastUsedReStampedWhenOlderThanThrottle() {
            Credential staleUse = buildCredentialWithLastUsed(243L, USER_ID, "Twilio",
                    Map.of("api_key", "ENC:key"), Instant.now().minusSeconds(120));
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twilio"))
                    .thenReturn(Optional.of(staleUse));
            when(encryptionService.decrypt("ENC:key")).thenReturn("plain-key");

            service.getCredentialDataMap(USER_ID, "twilio", null);

            verify(credentialService).touchLastUsed(243L);
        }

        @Test
        @DisplayName("no stamp when no credential resolves")
        void noStampWhenCredentialNotFound() {
            when(credentialService.getCredentialByTenantAndName(USER_ID, "missing"))
                    .thenReturn(Optional.empty());
            when(credentialService.getCredentialsByIntegration(USER_ID, "missing"))
                    .thenReturn(List.of());

            service.getCredentialDataMap(USER_ID, "missing", null);

            verify(credentialService, never()).touchLastUsed(anyLong());
        }

        @Test
        @DisplayName("a failed last_used stamp is swallowed and never breaks credential resolution")
        void stampFailureDoesNotBreakResolution() {
            Credential cred = buildCredentialWithLastUsed(243L, USER_ID, "Twilio",
                    Map.of("password", "ENC:token"), null);
            when(credentialService.getCredentialByTenantAndName(USER_ID, "twilio"))
                    .thenReturn(Optional.of(cred));
            when(encryptionService.decrypt("ENC:token")).thenReturn("authtoken");
            doThrow(new RuntimeException("db down")).when(credentialService).touchLastUsed(243L);

            Map<String, String> result = service.getCredentialDataMap(USER_ID, "twilio", null);

            // Resolution still returns the secret - a stamp failure must not strand the workflow.
            assertThat(result).containsEntry("password", "authtoken");
        }
    }

    // ========== Helpers ==========

    private Credential buildCredential(Long id, String tenantId, String name, Map<String, Object> data) {
        return buildCredentialWithStatus(id, tenantId, name, data, CredentialStatus.active);
    }

    private Credential buildCredentialWithLastUsed(Long id, String tenantId, String name,
                                                   Map<String, Object> data, Instant lastUsed) {
        Instant now = Instant.now();
        return new Credential(
                id, tenantId, name, "gmail",
                CredentialType.OAuth2, CredentialEnvironment.Production,
                CredentialStatus.active, "Test credential",
                data,
                List.of("email"), List.of("oauth2"),
                tenantId, "icon.url", true,
                lastUsed, now, now
        );
    }

    private Credential buildCredentialWithStatus(Long id, String tenantId, String name,
                                                  Map<String, Object> data, CredentialStatus status) {
        Instant now = Instant.now();
        return new Credential(
                id, tenantId, name, "gmail",
                CredentialType.OAuth2, CredentialEnvironment.Production,
                status, "Test credential",
                data,
                List.of("email"), List.of("oauth2"),
                tenantId, "icon.url", true,
                null, now, now
        );
    }

    private PlatformCredential buildPlatformCredential(String integrationName, String apiKey) {
        return new PlatformCredential(
                1L, integrationName, "Display " + integrationName, AuthType.API_KEY,
                null, null, apiKey, null, null,
                null, null, null, "icon", "email", "desc", true,
                Map.of(),
                java.math.BigDecimal.ZERO,
                500,
                Instant.now(), Instant.now(), null, null
        );
    }
}
