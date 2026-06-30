package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for agent webhook tokens.
 */
@Repository
public interface AgentWebhookTokenRepository extends JpaRepository<AgentWebhookTokenEntity, Long> {

    /**
     * Find webhook token by token value.
     */
    Optional<AgentWebhookTokenEntity> findByToken(String token);

    /**
     * Find webhook token by agent ID.
     */
    Optional<AgentWebhookTokenEntity> findByAgentId(UUID agentId);

    /**
     * Check if agent has a webhook token.
     */
    boolean existsByAgentId(UUID agentId);

    /**
     * Delete webhook token by agent ID.
     */
    @Modifying
    @Query("DELETE FROM AgentWebhookTokenEntity t WHERE t.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") UUID agentId);

    /**
     * Find active webhook by token.
     */
    @Query("SELECT t FROM AgentWebhookTokenEntity t WHERE t.token = :token AND t.isActive = true")
    Optional<AgentWebhookTokenEntity> findActiveByToken(@Param("token") String token);

    /**
     * Find every active webhook owned by a tenant - used by the dashboard
     * "active automations" widget. Joins through AgentEntity so the tenant
     * filter happens in agent-service's own schema (no cross-schema query).
     *
     * <p>Ordered by {@code createdAt ASC} so that when an agent owns multiple
     * tokens (rare but possible during regeneration races), the oldest one
     * "wins" the surface representation deterministically. Without this,
     * Postgres would be free to return rows in any order and the strip's
     * displayed httpMethod could flicker between callers.
     */
    @Query("SELECT t FROM AgentWebhookTokenEntity t " +
           "WHERE t.agentId IN (SELECT a.id FROM AgentEntity a WHERE a.tenantId = :tenantId) " +
           "AND t.isActive = true " +
           "ORDER BY t.createdAt ASC")
    List<AgentWebhookTokenEntity> findActiveByTenantId(@Param("tenantId") String tenantId);

    /**
     * Find every active webhook in a WORKSPACE (organization) - used by the Agent
     * Fleet batch trigger lookup so a shared workspace surfaces teammate-owned
     * agents' webhooks too (the {@link #findActiveByTenantId} variant only sees the
     * caller's own rows). Org-strict: joins through AgentEntity on organization_id,
     * matching the strict-org reads elsewhere post-V261.
     */
    @Query("SELECT t FROM AgentWebhookTokenEntity t " +
           "WHERE t.agentId IN (SELECT a.id FROM AgentEntity a WHERE a.organizationId = :orgId) " +
           "AND t.isActive = true " +
           "ORDER BY t.createdAt ASC")
    List<AgentWebhookTokenEntity> findActiveByOrganizationId(@Param("orgId") String orgId);
}
