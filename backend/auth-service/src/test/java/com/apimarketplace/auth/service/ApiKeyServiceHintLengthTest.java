package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.ApiKeyResponse;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the generated hint ("lc_live_" + "..." + last 4 = 15 chars) NEVER fit the
 * original api_key_hint VARCHAR(10) from V3, so every regenerate 500'd with
 * "value too long for type character varying(10)". Latent while the API-keys page was
 * hidden; surfaced by the MCP server settings page. Fixed by V382 (column -> VARCHAR(20))
 * + the entity length; this test pins hint-vs-column agreement so it cannot drift again.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService hint length vs persisted column")
class ApiKeyServiceHintLengthTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CredentialEncryptionService encryptionService;

    @Mock
    private UserResolutionService userResolutionService;

    @InjectMocks
    private ApiKeyService apiKeyService;

    @Test
    @DisplayName("generated hint fits the User.apiKeyHint mapped column length")
    void generatedHintFitsPersistedColumnLength() throws Exception {
        User user = new User();
        user.setId(42L);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(encryptionService.hmacHash(anyString())).thenReturn("h".repeat(64));

        ApiKeyResponse response = apiKeyService.regenerateKey(42L);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        String hint = saved.getValue().getApiKeyHint();
        int columnLength = User.class.getDeclaredField("apiKeyHint")
                .getAnnotation(Column.class).length();

        assertThat(hint).isEqualTo(response.getMaskedApiKey());
        assertThat(hint).matches("^lc_live_\\.\\.\\..{4}$");
        assertThat(hint.length())
                .as("hint must fit the persisted column (V382 widened it to 20)")
                .isLessThanOrEqualTo(columnLength);
    }
}
