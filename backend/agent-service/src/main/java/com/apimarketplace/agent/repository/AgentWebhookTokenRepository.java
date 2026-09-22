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
     * Lookup by the HMAC of the plaintext token ({@code TokenAtRest.hash}). The token column
     * itself is encrypted with a random IV, so a JPQL equality on it can never match: there is
     * no {@code findByToken} on purpose.
     */
    Optional<AgentWebhookTokenEntity> findByTokenHash(String tokenHash);

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
    @Query("SELECT t FROM AgentWebhookTokenEntity t WHERE t.tokenHash = :tokenHash AND t.isActive = true")
    Optional<AgentWebhookTokenEntity> findActiveByTokenHash(@Param("tokenHash") String tokenHash);

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

    /**
     * READ-ONLY plaintext match for a row written before 2026-09-17 (token in clear, no hash).
     * Native on purpose: a JPQL comparison would convert the parameter through the encrypting
     * converter. Rewrites nothing; the delayed startup backfill does. Gated by the service on
     * {@code PlaintextTokenBackfill.mayHaveLegacyRows}.
     */
    @Query(value = "SELECT * FROM agent.agent_webhook_tokens WHERE token = :plain AND token_hash IS NULL", nativeQuery = true)
    Optional<AgentWebhookTokenEntity> findLegacyPlaintext(@Param("plain") String plain);
}
