package com.apimarketplace.agent.repository;


import com.apimarketplace.agent.domain.AgentWidgetConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for agent widget configurations.
 */
@Repository
public interface AgentWidgetConfigRepository extends JpaRepository<AgentWidgetConfigEntity, Long> {

    /**
     * Find widget config by agent ID.
     */
    Optional<AgentWidgetConfigEntity> findByAgentId(UUID agentId);

    /**
     * Check if agent has a widget config.
     */
    boolean existsByAgentId(UUID agentId);

    /**
     * Delete widget config by agent ID.
     */
    @Modifying
    @Query("DELETE FROM AgentWidgetConfigEntity w WHERE w.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") UUID agentId);

    /**
     * Find active widget config by agent ID.
     */
    @Query("SELECT w FROM AgentWidgetConfigEntity w WHERE w.agentId = :agentId AND w.isActive = true")
    Optional<AgentWidgetConfigEntity> findActiveByAgentId(@Param("agentId") UUID agentId);

    /** Lookup by HMAC of the plaintext widget token; the column itself is encrypted (see TokenAtRest). */
    Optional<AgentWidgetConfigEntity> findByWidgetTokenHash(String widgetTokenHash);

    /**
     * Find active widget config by widget token.
     */
    @Query("SELECT w FROM AgentWidgetConfigEntity w WHERE w.widgetTokenHash = :tokenHash AND w.isActive = true")
    Optional<AgentWidgetConfigEntity> findByWidgetTokenHashAndIsActiveTrue(@Param("tokenHash") String tokenHash);

    /**
     * READ-ONLY plaintext match for a row written before 2026-09-17 (token in clear, no hash).
     * Native on purpose: a JPQL comparison would convert the parameter through the encrypting
     * converter. Rewrites nothing; the delayed startup backfill does. Gated by the service on
     * {@code PlaintextTokenBackfill.mayHaveLegacyRows}.
     */
    @Query(value = "SELECT * FROM agent.agent_widget_configs WHERE widget_token = :plain AND widget_token_hash IS NULL", nativeQuery = true)
    Optional<AgentWidgetConfigEntity> findLegacyPlaintext(@Param("plain") String plain);
}
