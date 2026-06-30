package com.apimarketplace.catalog.service;

import com.apimarketplace.credential.client.CredentialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserCredentialService.
 * Verifies delegation to CredentialClient (all credential logic now in orchestrator-service).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserCredentialService")
class UserCredentialServiceTest {

    @Mock
    private CredentialClient credentialClient;

    private UserCredentialService userCredentialService;

    @BeforeEach
    void setUp() {
        userCredentialService = new UserCredentialService(credentialClient);
    }

    // ========================================================================
    // getAccessToken tests
    // ========================================================================

    @Nested
    @DisplayName("getAccessToken")
    class GetAccessTokenTests {

        @Test
        @DisplayName("should return empty when userId is null")
        void shouldReturnEmptyWhenUserIdIsNull() {
            Optional<String> result = userCredentialService.getAccessToken(null, "credential-name");
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should return empty when userId is blank")
        void shouldReturnEmptyWhenUserIdIsBlank() {
            Optional<String> result = userCredentialService.getAccessToken("   ", "credential-name");
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should return empty when credentialName is null")
        void shouldReturnEmptyWhenCredentialNameIsNull() {
            Optional<String> result = userCredentialService.getAccessToken("user-123", null);
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should return empty when credentialName is blank")
        void shouldReturnEmptyWhenCredentialNameIsBlank() {
            Optional<String> result = userCredentialService.getAccessToken("user-123", "   ");
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should delegate to credentialClient and return token")
        void shouldDelegateToCredentialClient() {
            when(credentialClient.getAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.of("test-access-token"));

            Optional<String> result = userCredentialService.getAccessToken("user-123", "gmail-credential");

            assertTrue(result.isPresent());
            assertEquals("test-access-token", result.get());
            verify(credentialClient).getAccessToken("user-123", "gmail-credential");
        }

        @Test
        @DisplayName("should return empty when credential not found")
        void shouldReturnEmptyWhenNotFound() {
            when(credentialClient.getAccessToken("user-123", "unknown"))
                    .thenReturn(Optional.empty());

            Optional<String> result = userCredentialService.getAccessToken("user-123", "unknown");

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // refreshAccessToken tests
    // ========================================================================

    @Nested
    @DisplayName("refreshAccessToken")
    class RefreshAccessTokenTests {

        @Test
        @DisplayName("should return empty when userId is null")
        void shouldReturnEmptyWhenUserIdIsNull() {
            Optional<String> result = userCredentialService.refreshAccessToken(null, "credential");
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should return empty when userId is blank")
        void shouldReturnEmptyWhenUserIdIsBlank() {
            Optional<String> result = userCredentialService.refreshAccessToken("   ", "credential");
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should delegate to credentialClient")
        void shouldDelegateToCredentialClient() {
            when(credentialClient.refreshAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.of("new-access-token"));

            Optional<String> result = userCredentialService.refreshAccessToken("user-123", "gmail-credential");

            assertTrue(result.isPresent());
            assertEquals("new-access-token", result.get());
        }

        @Test
        @DisplayName("should return empty when refresh fails")
        void shouldReturnEmptyWhenRefreshFails() {
            when(credentialClient.refreshAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.empty());

            Optional<String> result = userCredentialService.refreshAccessToken("user-123", "gmail-credential");

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // getOrRefreshAccessToken tests
    // ========================================================================

    @Nested
    @DisplayName("getOrRefreshAccessToken")
    class GetOrRefreshAccessTokenTests {

        @Test
        @DisplayName("should return existing token if available")
        void shouldReturnExistingTokenIfAvailable() {
            when(credentialClient.getAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.of("existing-token"));

            Optional<String> result = userCredentialService.getOrRefreshAccessToken("user-123", "gmail-credential");

            assertTrue(result.isPresent());
            assertEquals("existing-token", result.get());
            verify(credentialClient, never()).refreshAccessToken(anyString(), anyString());
        }

        @Test
        @DisplayName("should try refresh when no existing token")
        void shouldTryRefreshWhenNoExistingToken() {
            when(credentialClient.getAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.empty());
            when(credentialClient.refreshAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.of("refreshed-token"));

            Optional<String> result = userCredentialService.getOrRefreshAccessToken("user-123", "gmail-credential");

            assertTrue(result.isPresent());
            assertEquals("refreshed-token", result.get());
        }

        @Test
        @DisplayName("should return empty when both get and refresh fail")
        void shouldReturnEmptyWhenBothFail() {
            when(credentialClient.getAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.empty());
            when(credentialClient.refreshAccessToken("user-123", "gmail-credential"))
                    .thenReturn(Optional.empty());

            Optional<String> result = userCredentialService.getOrRefreshAccessToken("user-123", "gmail-credential");

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // forceRefreshAndGetToken tests
    // ========================================================================

    @Nested
    @DisplayName("forceRefreshAndGetToken")
    class ForceRefreshAndGetTokenTests {

        @Test
        @DisplayName("should return empty when userId is null")
        void shouldReturnEmptyWhenUserIdIsNull() {
            Optional<String> result = userCredentialService.forceRefreshAndGetToken(null, "credential");
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should delegate to credentialClient")
        void shouldDelegateToCredentialClient() {
            when(credentialClient.forceRefreshAndGetToken("user-123", "amadeus-credential"))
                    .thenReturn(Optional.of("fresh-token"));

            Optional<String> result = userCredentialService.forceRefreshAndGetToken("user-123", "amadeus-credential");

            assertTrue(result.isPresent());
            assertEquals("fresh-token", result.get());
        }

        @Test
        @DisplayName("should return empty when force refresh fails")
        void shouldReturnEmptyWhenForceRefreshFails() {
            when(credentialClient.forceRefreshAndGetToken("user-123", "credential"))
                    .thenReturn(Optional.empty());

            Optional<String> result = userCredentialService.forceRefreshAndGetToken("user-123", "credential");

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // getCredentialDataMap tests
    // ========================================================================

    @Nested
    @DisplayName("getCredentialDataMap")
    class GetCredentialDataMapTests {

        @Test
        @DisplayName("should return empty map when userId is null")
        void shouldReturnEmptyMapWhenUserIdIsNull() {
            Map<String, String> result = userCredentialService.getCredentialDataMap(null, "cred");
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should return empty map when credentialName is null")
        void shouldReturnEmptyMapWhenCredentialNameIsNull() {
            Map<String, String> result = userCredentialService.getCredentialDataMap("user-1", null);
            assertTrue(result.isEmpty());
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("should delegate to credentialClient")
        void shouldDelegateToCredentialClient() {
            Map<String, String> data = Map.of("api_key", "sk-test", "base_url", "https://api.example.com");
            when(credentialClient.getCredentialDataMap("user-123", "my-cred")).thenReturn(data);

            Map<String, String> result = userCredentialService.getCredentialDataMap("user-123", "my-cred");

            assertEquals(data, result);
        }
    }
}
