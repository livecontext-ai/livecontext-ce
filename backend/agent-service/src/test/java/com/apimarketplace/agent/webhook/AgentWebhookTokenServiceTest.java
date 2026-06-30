package com.apimarketplace.agent.webhook;

import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.repository.AgentWebhookTokenRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWebhookTokenService Tests")
class AgentWebhookTokenServiceTest {

    @Mock
    private AgentWebhookTokenRepository repository;

    @Mock
    private CredentialEncryptionService encryptionService;

    @InjectMocks
    private AgentWebhookTokenService service;

    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TOKEN_PREFIX = "ag_";
    private static final int FULL_TOKEN_LENGTH = 3 + 32; // "ag_" + 32 hex chars

    // ---------------------------------------------------------------------------
    // generateToken
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("generateToken()")
    class GenerateTokenTests {

        @Test
        @DisplayName("should produce token with ag_ prefix followed by 32 hex chars")
        void shouldProduceCorrectFormat() {
            String token = service.generateToken();

            assertThat(token).startsWith(TOKEN_PREFIX);
            assertThat(token).hasSize(FULL_TOKEN_LENGTH);

            String hexPart = token.substring(TOKEN_PREFIX.length());
            assertThat(hexPart).matches("[0-9a-f]{32}");
        }

        @Test
        @DisplayName("should produce unique tokens on successive calls")
        void shouldProduceUniqueTokens() {
            Set<String> tokens = new java.util.HashSet<>();
            for (int i = 0; i < 20; i++) {
                tokens.add(service.generateToken());
            }
            assertThat(tokens).hasSize(20);
        }
    }

    // ---------------------------------------------------------------------------
    // isValidTokenFormat
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("isValidTokenFormat()")
    class IsValidTokenFormatTests {

        @Test
        @DisplayName("should accept a correctly formatted token")
        void shouldAcceptValidToken() {
            String token = service.generateToken();
            assertThat(service.isValidTokenFormat(token)).isTrue();
        }

        @Test
        @DisplayName("should accept token with uppercase hex chars")
        void shouldAcceptUppercaseHexToken() {
            String token = TOKEN_PREFIX + "ABCDEF0123456789ABCDEF0123456789";
            assertThat(service.isValidTokenFormat(token)).isTrue();
        }

        @Test
        @DisplayName("should reject null token")
        void shouldRejectNull() {
            assertThat(service.isValidTokenFormat(null)).isFalse();
        }

        @Test
        @DisplayName("should reject blank token")
        void shouldRejectBlank() {
            assertThat(service.isValidTokenFormat("   ")).isFalse();
        }

        @Test
        @DisplayName("should reject token without ag_ prefix")
        void shouldRejectWrongPrefix() {
            assertThat(service.isValidTokenFormat("sk_a1b2c3d4e5f67890abcdef1234567890")).isFalse();
            assertThat(service.isValidTokenFormat("a1b2c3d4e5f67890abcdef1234567890")).isFalse();
        }

        @Test
        @DisplayName("should reject token with wrong hex length")
        void shouldRejectWrongLength() {
            // Too short (31 hex chars)
            assertThat(service.isValidTokenFormat(TOKEN_PREFIX + "a1b2c3d4e5f67890abcdef123456789")).isFalse();
            // Too long (33 hex chars)
            assertThat(service.isValidTokenFormat(TOKEN_PREFIX + "a1b2c3d4e5f67890abcdef12345678901")).isFalse();
        }

        @Test
        @DisplayName("should reject token with non-hex characters in hex portion")
        void shouldRejectNonHexChars() {
            assertThat(service.isValidTokenFormat(TOKEN_PREFIX + "g1b2c3d4e5f67890abcdef1234567890")).isFalse();
            assertThat(service.isValidTokenFormat(TOKEN_PREFIX + "a1b2c3d4e5f67890abcdef123456789z")).isFalse();
        }
    }

    // ---------------------------------------------------------------------------
    // createOrUpdateWebhook
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("createOrUpdateWebhook()")
    class CreateOrUpdateWebhookTests {

        @BeforeEach
        void setUpEncryptionStub() {
            // encryptAuthConfig calls encryptionService.encrypt for sensitive keys
            lenient().when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));
        }

        @Test
        @DisplayName("should create new entity with generated token when none exists")
        void shouldCreateNewEntityWithGeneratedToken() {
            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());
            when(repository.save(any(AgentWebhookTokenEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AgentWebhookTokenEntity result = service.createOrUpdateWebhook(
                    AGENT_ID, "POST", "none", null, null);

            assertThat(result.getAgentId()).isEqualTo(AGENT_ID);
            assertThat(result.getToken()).startsWith(TOKEN_PREFIX);
            assertThat(result.getToken()).hasSize(FULL_TOKEN_LENGTH);
            assertThat(result.getHttpMethod()).isEqualTo("POST");
            assertThat(result.getAuthType()).isEqualTo("none");
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();

            verify(repository).save(any(AgentWebhookTokenEntity.class));
        }

        @Test
        @DisplayName("should update existing entity without changing the token")
        void shouldUpdateExistingEntityWithoutChangingToken() {
            String existingToken = service.generateToken();
            AgentWebhookTokenEntity existing = new AgentWebhookTokenEntity(AGENT_ID, existingToken);
            existing.setHttpMethod("GET");
            existing.setAuthType("none");

            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(existing));
            when(repository.save(any(AgentWebhookTokenEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AgentWebhookTokenEntity result = service.createOrUpdateWebhook(
                    AGENT_ID, "POST", "basic",
                    Map.of("basicPassword", "secret123"), null);

            // Token must remain the same
            assertThat(result.getToken()).isEqualTo(existingToken);
            assertThat(result.getHttpMethod()).isEqualTo("POST");
            assertThat(result.getAuthType()).isEqualTo("basic");

            verify(repository).save(existing);
        }

        @Test
        @DisplayName("should use POST and none as defaults when httpMethod and authType are null")
        void shouldApplyDefaultsForNullParams() {
            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());
            when(repository.save(any(AgentWebhookTokenEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AgentWebhookTokenEntity result = service.createOrUpdateWebhook(
                    AGENT_ID, null, null, null, null);

            assertThat(result.getHttpMethod()).isEqualTo("POST");
            assertThat(result.getAuthType()).isEqualTo("none");
        }

        @Test
        @DisplayName("should encrypt sensitive fields in auth config before saving")
        void shouldEncryptSensitiveAuthConfigFields() {
            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());
            when(repository.save(any(AgentWebhookTokenEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> authConfig = Map.of(
                    "basicPassword", "mypassword",
                    "someOtherKey", "plainvalue"
            );

            AgentWebhookTokenEntity result = service.createOrUpdateWebhook(
                    AGENT_ID, "POST", "basic", authConfig, null);

            verify(encryptionService).encrypt("mypassword");
            // Non-sensitive keys are not encrypted
            verifyNoMoreInteractions(encryptionService);
        }
    }

    // ---------------------------------------------------------------------------
    // regenerateToken
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("regenerateToken()")
    class RegenerateTokenTests {

        @Test
        @DisplayName("should replace old token with a new unique one and save")
        void shouldReplaceTokenAndSave() {
            String oldToken = service.generateToken();
            AgentWebhookTokenEntity entity = new AgentWebhookTokenEntity(AGENT_ID, oldToken);

            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(entity));
            when(repository.save(any(AgentWebhookTokenEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            String newToken = service.regenerateToken(AGENT_ID);

            assertThat(newToken).startsWith(TOKEN_PREFIX);
            assertThat(newToken).hasSize(FULL_TOKEN_LENGTH);
            assertThat(newToken).isNotEqualTo(oldToken);
            assertThat(entity.getToken()).isEqualTo(newToken);

            verify(repository).save(entity);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when no webhook exists for agent")
        void shouldThrowWhenNoWebhookFound() {
            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.regenerateToken(AGENT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(AGENT_ID.toString());

            verify(repository, never()).save(any());
        }
    }

    // ---------------------------------------------------------------------------
    // setWebhookActive
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("setWebhookActive()")
    class SetWebhookActiveTests {

        @Test
        @DisplayName("should set isActive to true and save")
        void shouldEnableWebhook() {
            AgentWebhookTokenEntity entity = new AgentWebhookTokenEntity(AGENT_ID, service.generateToken());
            entity.setIsActive(false);

            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.setWebhookActive(AGENT_ID, true);

            assertThat(entity.getIsActive()).isTrue();
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("should set isActive to false and save")
        void shouldDisableWebhook() {
            AgentWebhookTokenEntity entity = new AgentWebhookTokenEntity(AGENT_ID, service.generateToken());
            entity.setIsActive(true);

            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.setWebhookActive(AGENT_ID, false);

            assertThat(entity.getIsActive()).isFalse();
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("should do nothing when no webhook exists for agent")
        void shouldDoNothingWhenNoWebhookExists() {
            when(repository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());

            service.setWebhookActive(AGENT_ID, true);

            verify(repository, never()).save(any());
        }
    }

    // ---------------------------------------------------------------------------
    // deleteWebhook
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("deleteWebhook()")
    class DeleteWebhookTests {

        @Test
        @DisplayName("should call deleteByAgentId on the repository")
        void shouldCallDeleteByAgentId() {
            service.deleteWebhook(AGENT_ID);

            verify(repository).deleteByAgentId(AGENT_ID);
        }
    }

    // ---------------------------------------------------------------------------
    // hasWebhook
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("hasWebhook()")
    class HasWebhookTests {

        @Test
        @DisplayName("should return true when existsByAgentId returns true")
        void shouldReturnTrueWhenExists() {
            when(repository.existsByAgentId(AGENT_ID)).thenReturn(true);

            assertThat(service.hasWebhook(AGENT_ID)).isTrue();
        }

        @Test
        @DisplayName("should return false when existsByAgentId returns false")
        void shouldReturnFalseWhenNotExists() {
            when(repository.existsByAgentId(AGENT_ID)).thenReturn(false);

            assertThat(service.hasWebhook(AGENT_ID)).isFalse();
        }
    }

    // ---------------------------------------------------------------------------
    // getWebhookUrl
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("getWebhookUrl()")
    class GetWebhookUrlTests {

        @Test
        @DisplayName("should build correct URL without trailing slash in base URL")
        void shouldBuildCorrectUrl() {
            String token = service.generateToken();
            String url = service.getWebhookUrl("https://example.com", token);

            assertThat(url).isEqualTo("https://example.com/webhook/agent/" + token);
        }

        @Test
        @DisplayName("should strip trailing slash from base URL")
        void shouldStripTrailingSlash() {
            String token = service.generateToken();
            String url = service.getWebhookUrl("https://example.com/", token);

            assertThat(url).isEqualTo("https://example.com/webhook/agent/" + token);
        }

        @Test
        @DisplayName("should return null when token is null")
        void shouldReturnNullForNullToken() {
            String url = service.getWebhookUrl("https://example.com", null);

            assertThat(url).isNull();
        }

        @Test
        @DisplayName("should return null when token is blank")
        void shouldReturnNullForBlankToken() {
            String url = service.getWebhookUrl("https://example.com", "   ");

            assertThat(url).isNull();
        }
    }

    // ---------------------------------------------------------------------------
    // findByToken / findActiveByToken
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("findByToken()")
    class FindByTokenTests {

        @Test
        @DisplayName("should return empty when token is null")
        void shouldReturnEmptyForNull() {
            assertThat(service.findByToken(null)).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("should return empty when token is blank")
        void shouldReturnEmptyForBlank() {
            assertThat(service.findByToken("  ")).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("should delegate to repository.findByToken for a non-blank token")
        void shouldDelegateToRepository() {
            String token = service.generateToken();
            AgentWebhookTokenEntity entity = new AgentWebhookTokenEntity(AGENT_ID, token);

            when(repository.findByToken(token)).thenReturn(Optional.of(entity));

            Optional<AgentWebhookTokenEntity> result = service.findByToken(token);

            assertThat(result).isPresent().contains(entity);
            verify(repository).findByToken(token);
        }

        @Test
        @DisplayName("should return empty when repository finds nothing")
        void shouldReturnEmptyWhenRepoReturnsEmpty() {
            String token = service.generateToken();
            when(repository.findByToken(token)).thenReturn(Optional.empty());

            assertThat(service.findByToken(token)).isEmpty();
        }
    }

    @Nested
    @DisplayName("findActiveByToken()")
    class FindActiveByTokenTests {

        @Test
        @DisplayName("should return empty when token is null")
        void shouldReturnEmptyForNull() {
            assertThat(service.findActiveByToken(null)).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("should return empty when token is blank")
        void shouldReturnEmptyForBlank() {
            assertThat(service.findActiveByToken("  ")).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("should delegate to repository.findActiveByToken for a non-blank token")
        void shouldDelegateToRepository() {
            String token = service.generateToken();
            AgentWebhookTokenEntity entity = new AgentWebhookTokenEntity(AGENT_ID, token);
            entity.setIsActive(true);

            when(repository.findActiveByToken(token)).thenReturn(Optional.of(entity));

            Optional<AgentWebhookTokenEntity> result = service.findActiveByToken(token);

            assertThat(result).isPresent().contains(entity);
            verify(repository).findActiveByToken(token);
        }

        @Test
        @DisplayName("should return empty when token is inactive according to repository")
        void shouldReturnEmptyWhenInactive() {
            String token = service.generateToken();
            when(repository.findActiveByToken(token)).thenReturn(Optional.empty());

            assertThat(service.findActiveByToken(token)).isEmpty();
        }
    }
}
