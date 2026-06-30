package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentWidgetConfigEntity;
import com.apimarketplace.agent.repository.AgentWidgetConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing agent widget configurations.
 * Allows agents to be embedded as chat widgets on external websites.
 */
@Service
public class AgentWidgetConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AgentWidgetConfigService.class);

    private static final String TOKEN_PREFIX = "wid_";
    private static final int TOKEN_HEX_LENGTH = 32; // 32 hex chars = 128 bits
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AgentWidgetConfigRepository repository;

    public AgentWidgetConfigService(AgentWidgetConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * Generate a new secure widget token.
     * Format: wid_[32 random hex chars]
     */
    public String generateWidgetToken() {
        byte[] bytes = new byte[TOKEN_HEX_LENGTH / 2];
        SECURE_RANDOM.nextBytes(bytes);

        StringBuilder hex = new StringBuilder(TOKEN_PREFIX);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Create or update widget configuration for an agent.
     */
    @Transactional
    public AgentWidgetConfigEntity createOrUpdateWidgetConfig(
            UUID agentId,
            String position,
            String theme,
            String primaryColor,
            String welcomeMessage,
            String bubbleText,
            Boolean showAvatar,
            Integer autoOpenDelay,
            String allowedOrigins) {

        AgentWidgetConfigEntity entity = repository.findByAgentId(agentId)
            .orElseGet(() -> {
                AgentWidgetConfigEntity newEntity = new AgentWidgetConfigEntity();
                newEntity.setAgentId(agentId);
                newEntity.setCreatedAt(Instant.now());
                newEntity.setWidgetToken(generateWidgetToken());
                return newEntity;
            });

        // Auto-generate token if missing (for existing records before V105)
        if (entity.getWidgetToken() == null || entity.getWidgetToken().isBlank()) {
            entity.setWidgetToken(generateWidgetToken());
        }

        // Update fields if provided
        if (position != null) {
            entity.setPosition(position);
        }
        if (theme != null) {
            entity.setTheme(theme);
        }
        if (primaryColor != null) {
            entity.setPrimaryColor(primaryColor);
        }
        if (welcomeMessage != null) {
            entity.setWelcomeMessage(welcomeMessage);
        }
        if (bubbleText != null) {
            entity.setBubbleText(bubbleText);
        }
        if (showAvatar != null) {
            entity.setShowAvatar(showAvatar);
        }
        if (autoOpenDelay != null) {
            entity.setAutoOpenDelay(autoOpenDelay);
        }
        if (allowedOrigins != null) {
            entity.setAllowedOrigins(allowedOrigins);
        }

        entity.setIsActive(true);
        entity.setUpdatedAt(Instant.now());

        AgentWidgetConfigEntity saved = repository.save(entity);
        logger.info("Created/updated widget config for agent {}", agentId);

        return saved;
    }

    /**
     * Get widget configuration for an agent.
     */
    public Optional<AgentWidgetConfigEntity> getWidgetConfig(UUID agentId) {
        return repository.findByAgentId(agentId);
    }

    /**
     * Get active widget configuration for an agent.
     */
    public Optional<AgentWidgetConfigEntity> getActiveWidgetConfig(UUID agentId) {
        return repository.findActiveByAgentId(agentId);
    }

    /**
     * Find widget config by widget token.
     */
    public Optional<AgentWidgetConfigEntity> findByWidgetToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repository.findByWidgetToken(token);
    }

    /**
     * Find active widget config by widget token.
     */
    public Optional<AgentWidgetConfigEntity> findActiveByWidgetToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repository.findByWidgetTokenAndIsActiveTrue(token);
    }

    /**
     * Validate origin against allowed origins for a widget.
     * Returns true if the origin is allowed.
     */
    public boolean validateOrigin(AgentWidgetConfigEntity widget, String origin) {
        String allowedOrigins = widget.getAllowedOrigins();

        // If no allowed origins configured, allow all
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return true;
        }

        // If no origin provided (e.g., direct browser request), deny if restrictions exist
        if (origin == null || origin.isBlank()) {
            return false;
        }

        // Check each allowed origin
        String[] origins = allowedOrigins.split(",");
        for (String allowed : origins) {
            String trimmed = allowed.trim();
            if (trimmed.isEmpty()) continue;

            // Match exact origin or wildcard subdomain
            if (origin.equals(trimmed) || origin.endsWith("." + trimmed.replaceFirst("^https?://", ""))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Regenerate widget token.
     */
    @Transactional
    public String regenerateWidgetToken(UUID agentId) {
        AgentWidgetConfigEntity entity = repository.findByAgentId(agentId)
            .orElseThrow(() -> new IllegalArgumentException("No widget config found for agent: " + agentId));

        String newToken = generateWidgetToken();
        entity.setWidgetToken(newToken);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);

        logger.info("Regenerated widget token for agent {}", agentId);
        return newToken;
    }

    /**
     * Enable or disable widget for an agent.
     */
    @Transactional
    public void setWidgetActive(UUID agentId, boolean active) {
        repository.findByAgentId(agentId).ifPresent(entity -> {
            entity.setIsActive(active);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            logger.info("Set widget active={} for agent {}", active, agentId);
        });
    }

    /**
     * Delete widget configuration for an agent.
     */
    @Transactional
    public void deleteWidgetConfig(UUID agentId) {
        repository.deleteByAgentId(agentId);
        logger.info("Deleted widget config for agent {}", agentId);
    }

    /**
     * Check if agent has a widget configuration.
     */
    public boolean hasWidgetConfig(UUID agentId) {
        return repository.existsByAgentId(agentId);
    }

    /**
     * Get the widget embed script URL.
     */
    public String getWidgetScriptUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl + "/widget.js";
    }

    /**
     * Validate position value.
     */
    public boolean isValidPosition(String position) {
        if (position == null) return true; // null means keep current
        return position.equals("bottom-right") ||
               position.equals("bottom-left") ||
               position.equals("top-right") ||
               position.equals("top-left");
    }

    /**
     * Validate theme value.
     */
    public boolean isValidTheme(String theme) {
        if (theme == null) return true; // null means keep current
        return theme.equals("light") ||
               theme.equals("dark") ||
               theme.equals("auto");
    }

    /**
     * Validate color format (hex).
     */
    public boolean isValidColor(String color) {
        if (color == null) return true; // null means keep current
        return color.matches("^#[0-9A-Fa-f]{6}$");
    }
}
