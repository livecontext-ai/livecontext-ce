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

    /**
     * Find widget config by widget token.
     */
    Optional<AgentWidgetConfigEntity> findByWidgetToken(String widgetToken);

    /**
     * Find active widget config by widget token.
     */
    @Query("SELECT w FROM AgentWidgetConfigEntity w WHERE w.widgetToken = :token AND w.isActive = true")
    Optional<AgentWidgetConfigEntity> findByWidgetTokenAndIsActiveTrue(@Param("token") String token);
}
