package com.apimarketplace.agent.webhook;

import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.repository.AgentWebhookTokenRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for generating and managing agent webhook tokens.
 *
 * Token format: ag_[32 random hex chars] (e.g., ag_a1b2c3d4e5f67890abcdef1234567890)
 */
@Service
public class AgentWebhookTokenService {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebhookTokenService.class);

    private static final String TOKEN_PREFIX = "ag_";
    private static final int TOKEN_LENGTH = 32; // 32 hex chars = 128 bits of entropy
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AgentWebhookTokenRepository repository;
    private final CredentialEncryptionService encryptionService;

    public AgentWebhookTokenService(AgentWebhookTokenRepository repository,
                                    CredentialEncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    /**
     * Generate a new secure agent webhook token.
     * Format: ag_[32 random hex chars]
     */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH / 2]; // 16 bytes = 32 hex chars
        SECURE_RANDOM.nextBytes(bytes);

        StringBuilder hex = new StringBuilder(TOKEN_PREFIX);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }

    /**
     * Create or update webhook for an agent.
     */
    @Transactional
    public AgentWebhookTokenEntity createOrUpdateWebhook(UUID agentId, String httpMethod, String authType, Map<String, Object> authConfig, Boolean memoryEnabled) {
        AgentWebhookTokenEntity entity = repository.findByAgentId(agentId)
            .orElseGet(() -> {
                AgentWebhookTokenEntity newEntity = new AgentWebhookTokenEntity();
                newEntity.setAgentId(agentId);
                newEntity.setToken(generateToken());
                newEntity.setCreatedAt(Instant.now());
                return newEntity;
            });

        entity.setHttpMethod(httpMethod != null ? httpMethod : "POST");
        entity.setAuthType(authType != null ? authType : "none");
        entity.setAuthConfig(encryptAuthConfig(authConfig));
        entity.setIsActive(true);
        if (memoryEnabled != null) {
            entity.setMemoryEnabled(memoryEnabled);
        }
        entity.setUpdatedAt(Instant.now());

        AgentWebhookTokenEntity saved = repository.save(entity);
        logger.info("Created/updated webhook for agent {}: token={}...", agentId,
            saved.getToken().substring(0, Math.min(12, saved.getToken().length())));

        return saved;
    }

    /**
     * Get webhook configuration for an agent.
     */
    public Optional<AgentWebhookTokenEntity> getWebhookForAgent(UUID agentId) {
        return repository.findByAgentId(agentId);
    }

    /**
     * Every ACTIVE webhook in a workspace (organization) - the Agent Fleet batch
     * trigger lookup, so one call covers all agents (incl. teammate-owned ones)
     * instead of one GET /{id}/webhook per agent.
     */
    public java.util.List<AgentWebhookTokenEntity> findActiveByOrganization(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return java.util.List.of();
        }
        return repository.findActiveByOrganizationId(organizationId);
    }

    /**
     * Find webhook by token.
     */
    public Optional<AgentWebhookTokenEntity> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repository.findByToken(token);
    }

    /**
     * Find active webhook by token.
     */
    public Optional<AgentWebhookTokenEntity> findActiveByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveByToken(token);
    }

    /**
     * Regenerate token for an agent's webhook.
     */
    @Transactional
    public String regenerateToken(UUID agentId) {
        AgentWebhookTokenEntity entity = repository.findByAgentId(agentId)
            .orElseThrow(() -> new IllegalArgumentException("No webhook found for agent: " + agentId));

        String oldToken = entity.getToken();
        String newToken = generateToken();
        entity.setToken(newToken);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);

        logger.info("Regenerated webhook token for agent {}: old={}... new={}...",
            agentId,
            oldToken.substring(0, Math.min(12, oldToken.length())),
            newToken.substring(0, Math.min(12, newToken.length())));

        return newToken;
    }

    /**
     * Enable or disable webhook for an agent.
     */
    @Transactional
    public void setWebhookActive(UUID agentId, boolean active) {
        repository.findByAgentId(agentId).ifPresent(entity -> {
            entity.setIsActive(active);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            logger.info("Set webhook active={} for agent {}", active, agentId);
        });
    }

    /**
     * Delete webhook for an agent.
     */
    @Transactional
    public void deleteWebhook(UUID agentId) {
        repository.deleteByAgentId(agentId);
        logger.info("Deleted webhook for agent {}", agentId);
    }

    /**
     * Check if agent has a webhook.
     */
    public boolean hasWebhook(UUID agentId) {
        return repository.existsByAgentId(agentId);
    }

    /**
     * Validate token format.
     */
    public boolean isValidTokenFormat(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        if (!token.startsWith(TOKEN_PREFIX)) {
            return false;
        }

        String hexPart = token.substring(TOKEN_PREFIX.length());
        if (hexPart.length() != TOKEN_LENGTH) {
            return false;
        }

        return hexPart.chars().allMatch(c ->
            (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
        );
    }

    /**
     * Get the webhook URL for an agent.
     */
    public String getWebhookUrl(String baseUrl, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl + "/webhook/agent/" + token;
    }

    /**
     * Encrypt sensitive values in webhook auth config before persisting.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> encryptAuthConfig(Map<String, Object> authConfig) {
        if (authConfig == null) return null;
        Map<String, Object> encrypted = new LinkedHashMap<>(authConfig);
        for (String key : List.of("basicPassword", "authHeaderValue", "jwtSecretKey")) {
            if (encrypted.containsKey(key) && encrypted.get(key) instanceof String s) {
                encrypted.put(key, encryptionService.encrypt(s));
            }
        }
        return encrypted;
    }

    /**
     * Decrypt sensitive values in webhook auth config after reading from DB.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> decryptAuthConfig(Map<String, Object> authConfig) {
        if (authConfig == null) return null;
        Map<String, Object> decrypted = new LinkedHashMap<>(authConfig);
        for (String key : List.of("basicPassword", "authHeaderValue", "jwtSecretKey")) {
            if (decrypted.containsKey(key) && decrypted.get(key) instanceof String s) {
                decrypted.put(key, encryptionService.decrypt(s));
            }
        }
        return decrypted;
    }
}
