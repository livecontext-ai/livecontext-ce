package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.ApiKeyRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression (MCP server audit M1): rotating an API key must bust the gateway's
 * user-resolution cache for the key owner, otherwise the OLD plaintext key kept
 * authenticating from the gateway cache for up to the TTL while the settings UI
 * promised immediate invalidation. Outside a transaction the bust runs inline;
 * inside one it is deferred to afterCommit (OrganizationMemberService pattern).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService gateway cache bust on rotation")
class ApiKeyServiceCacheBustTest {

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

    @InjectMocks
    private ApiKeyService apiKeyService;

    @Test
    @DisplayName("regenerateKey invalidates the gateway cache for the key owner's providerId")
    void regenerateKeyBustsGatewayCacheForOwner() {
        User user = new User();
        user.setId(42L);
        user.setProviderId("local:owner@example.com");
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(encryptionService.hmacHash(anyString())).thenReturn("h".repeat(64));

        apiKeyService.regenerateKey(42L);

        verify(gatewayCacheClient).invalidateUserCache("local:owner@example.com");
    }

    @Test
    @DisplayName("a user without providerId skips the bust instead of publishing a blank invalidation")
    void missingProviderIdSkipsBust() {
        User user = new User();
        user.setId(42L);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(encryptionService.hmacHash(anyString())).thenReturn("h".repeat(64));

        apiKeyService.regenerateKey(42L);

        verifyNoInteractions(gatewayCacheClient);
    }
}
