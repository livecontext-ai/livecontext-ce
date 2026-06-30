package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentSkillEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.repository.UserSkillOverrideRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the Agent Fleet skills BATCH path
 * ({@link SkillService#getAllAgentSkills}) - resolves the workspace's agent ids
 * then fetches every assignment in one IN query, replacing the per-agent
 * getAgentSkills fan-out.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService.getAllAgentSkills - fleet batch")
class SkillServiceFleetSkillsTest {

    @Mock private SkillRepository skillRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private UserSkillOverrideRepository userSkillOverrideRepository;

    @InjectMocks private SkillService skillService;

    private static final String ORG_ID = "org_X";

    private AgentEntity agent(UUID id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        return a;
    }

    @Test
    @DisplayName("resolves the org's agent ids then batch-fetches every agent's assignments")
    void returnsAllAgentSkillsForOrg() {
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        when(agentRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID))
            .thenReturn(List.of(agent(agentA), agent(agentB)));
        AgentSkillEntity a1 = new AgentSkillEntity(agentA, UUID.randomUUID(), 0);
        AgentSkillEntity a2 = new AgentSkillEntity(agentB, UUID.randomUUID(), 1);
        when(agentSkillRepository.findByAgentIdInOrderByAgentIdAscSortOrderAsc(List.of(agentA, agentB)))
            .thenReturn(List.of(a1, a2));

        List<AgentSkillEntity> result = skillService.getAllAgentSkills("tenant-1", ORG_ID);

        assertThat(result).containsExactly(a1, a2);
        verify(agentSkillRepository).findByAgentIdInOrderByAgentIdAscSortOrderAsc(List.of(agentA, agentB));
    }

    @Test
    @DisplayName("an org with no agents → empty list, never hits the agent-skill table")
    void noAgentsYieldsEmpty() {
        when(agentRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of());

        assertThat(skillService.getAllAgentSkills("tenant-1", ORG_ID)).isEmpty();
        verify(agentSkillRepository, never()).findByAgentIdInOrderByAgentIdAscSortOrderAsc(anyList());
    }

    @Test
    @DisplayName("blank or null org → empty list, never queries agents or assignments")
    void blankOrgYieldsEmptyWithoutQuery() {
        assertThat(skillService.getAllAgentSkills("tenant-1", "")).isEmpty();
        assertThat(skillService.getAllAgentSkills("tenant-1", null)).isEmpty();
        verify(agentRepository, never()).findByOrganizationIdStrictOrderByCreatedAtDesc(any());
        verify(agentSkillRepository, never()).findByAgentIdInOrderByAgentIdAscSortOrderAsc(anyList());
    }
}
