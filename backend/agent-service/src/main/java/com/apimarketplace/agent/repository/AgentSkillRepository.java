package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentSkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkillEntity, UUID> {

    List<AgentSkillEntity> findByAgentIdOrderBySortOrderAsc(UUID agentId);

    /**
     * All skill assignments for a set of agents - the Agent Fleet batch lookup, so
     * one query covers every workspace agent instead of one
     * {@link #findByAgentIdOrderBySortOrderAsc} per agent. Derived query (not JPQL)
     * so the {@code @ManyToOne(EAGER)} skill loads exactly like the per-agent finder
     * - a JPQL subquery here tripped a Hibernate 6 result-column mis-mapping, so the
     * caller pre-resolves the org's agent ids and passes them in.
     */
    List<AgentSkillEntity> findByAgentIdInOrderByAgentIdAscSortOrderAsc(List<UUID> agentIds);

    List<AgentSkillEntity> findBySkillId(UUID skillId);

    @Modifying
    @Query("DELETE FROM AgentSkillEntity a WHERE a.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") UUID agentId);

    @Modifying
    @Query("DELETE FROM AgentSkillEntity a WHERE a.agentId = :agentId AND a.skillId = :skillId")
    void deleteByAgentIdAndSkillId(@Param("agentId") UUID agentId, @Param("skillId") UUID skillId);
}
