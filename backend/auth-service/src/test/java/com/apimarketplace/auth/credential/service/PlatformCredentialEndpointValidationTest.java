package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.CreatePlatformCredentialRequest;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.UpdatePlatformCredentialRequest;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** LC-052: a BYOK/platform OAuth2 endpoint that a user saves must be a public https URL. */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialService refuses unsafe OAuth2 endpoints at save time")
class PlatformCredentialEndpointValidationTest {

    @Mock private PlatformCredentialRepository repository;
    private PlatformCredentialService service;

    @BeforeEach
    void setUp() {
        service = new PlatformCredentialService(repository);
    }

    private static CreatePlatformCredentialRequest create(String authUrl, String tokenUrl) {
        return new CreatePlatformCredentialRequest("acme", "Acme", "oauth2", "cid", "sec", null, null, null,
                authUrl, tokenUrl, "read", "acme", "Other", null, null, null, null);
    }

    @Test
    @DisplayName("user BYOK save with a token URL on the cloud metadata address: refused, nothing written")
    void byokSaveRefusesMetadataAddress() {
        assertThatThrownBy(() -> service.saveCredential(
                create("https://8.8.8.8/authorize", "https://169.254.169.254/latest/meta-data/"), "user-1", "org-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenUrl");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("an http authorize URL is refused")
    void saveRefusesHttp() {
        assertThatThrownBy(() -> service.saveCredential(
                create("http://8.8.8.8/authorize", "https://8.8.8.8/token"), "user-1", "org-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authUrl");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("the update path applies the same rule")
    void updateRefusesInternal() {
        UpdatePlatformCredentialRequest update = new UpdatePlatformCredentialRequest(null, null, null, null, null,
                null, null, "https://10.0.9.5/token", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateCredential("acme", update, "user-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }
}
