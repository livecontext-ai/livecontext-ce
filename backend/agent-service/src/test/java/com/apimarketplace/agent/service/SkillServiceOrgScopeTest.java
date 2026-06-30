package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.agent.domain.AgentSkillEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6c regression coverage for the org-aware overloads on
 * {@link SkillService}. The pre-Phase-6c implementation rejected
 * legitimate org-teammate access to a workspace-shared skill / agent
 * because the tenant_id of the row never matched the teammate's userId.
 * The new {@code findInScope} / {@code findInScopeOrGlobal} routing pair
 * (org-strict / personal-strict + global fallback) closes that gap.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService - Phase 6c org-aware overloads")
class SkillServiceOrgScopeTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private AgentSkillRepository agentSkillRepository;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private StorageBreakdownService breakdownService;

    @Mock
    private com.apimarketplace.agent.repository.UserSkillOverrideRepository userSkillOverrideRepository;

    @InjectMocks
    private SkillService skillService;

    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String OWNER_TENANT = "owner_user";
    private static final String TEAMMATE_TENANT = "teammate_user";
    private static final String ORG_ID = "org_X";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(skillService, "maxSkillsPerAgent", 10);
    }

    private SkillEntity skill(UUID id, String tenantId, String orgId) {
        SkillEntity s = new SkillEntity(tenantId, "Skill", "Desc", "icon", "Instructions", true);
        s.setId(id);
        if (orgId != null) {
            s.setOrganizationId(orgId);
        }
        return s;
    }

    @Test
    @DisplayName("listSkills(org) routes through findVisibleForOrganization so teammates see workspace skills + globals - and still runs the per-tenant seed check (2026-06-11: real traffic always carries an org, so an org-gated seed never fires)")
    void listSkillsOrgScopeReturnsOrgVisibleRows() {
        SkillEntity orgSkill = skill(SKILL_ID, OWNER_TENANT, ORG_ID);
        // Tenant already fully seeded → the seed check is a no-op count.
        when(skillRepository.countByTenantIdAndDefaultKeyIsNotNull(TEAMMATE_TENANT))
                .thenReturn((long) com.apimarketplace.agent.skills.DefaultSkillsProvider.getAll().size());
        when(skillRepository.findVisibleForOrganization(ORG_ID))
                .thenReturn(List.of(orgSkill));

        List<SkillEntity> result = skillService.listSkills(TEAMMATE_TENANT, ORG_ID);

        assertThat(result).containsExactly(orgSkill);
        // Org scope must NEVER widen to the personal-tenant view…
        verify(skillRepository, never()).findVisibleForTenant(any());
        // …but the seed check DOES run here now: post-V261 the org branch is
        // the only branch real traffic reaches (the old "seed only when
        // organizationId==null" contract meant new tenants got zero built-ins).
        verify(skillRepository).countByTenantIdAndDefaultKeyIsNotNull(TEAMMATE_TENANT);
        verify(skillRepository, never()).save(any(SkillEntity.class));
    }

    @Test
    @DisplayName("listSkills(personal) keeps the auto-seed default-skills behavior for personal workspace")
    void listSkillsPersonalKeepsAutoSeed() {
        // Personal-strict path runs the auto-seed branch first.
        when(skillRepository.countByTenantIdAndDefaultKeyIsNotNull(OWNER_TENANT))
                .thenReturn(0L);
        when(skillRepository.findByTenantIdAndDefaultKey(any(), any()))
                .thenReturn(Optional.empty());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRepository.findVisibleForTenant(OWNER_TENANT))
                .thenReturn(List.of());

        skillService.listSkills(OWNER_TENANT, null);

        verify(skillRepository).findVisibleForTenant(OWNER_TENANT);
        verify(skillRepository, atLeastOnce()).save(any(SkillEntity.class));
        verify(skillRepository, never()).findVisibleForOrganization(any());
    }

    @Test
    @DisplayName("getSkill accepts org-teammate fetch of a workspace-shared skill - Phase 6c regression")
    void getSkillAcceptsOrgTeammate() {
        SkillEntity orgSkill = skill(SKILL_ID, OWNER_TENANT, ORG_ID);
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG_ID))
                .thenReturn(Optional.of(orgSkill));

        Optional<SkillEntity> result = skillService.getSkill(SKILL_ID, TEAMMATE_TENANT, ORG_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(SKILL_ID);
    }

    @Test
    @DisplayName("updateSkill rejects when caller is in a different workspace than the skill")
    void updateSkillRejectsCrossWorkspace() {
        // Org-strict returns empty (caller is in org_other), global fallback empty.
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, "org_other"))
                .thenReturn(Optional.empty());
        when(skillRepository.findById(SKILL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> skillService.updateSkill(
                SKILL_ID, TEAMMATE_TENANT, "org_other", "new", "d", null, "i", null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill not found");

        verify(skillRepository, never()).save(any(SkillEntity.class));
    }

    @Test
    @DisplayName("setAgentSkills accepts a teammate assigning an org-shared skill to an org-shared agent")
    void setAgentSkillsAcceptsOrgTeammate() {
        SkillEntity orgSkill = skill(SKILL_ID, OWNER_TENANT, ORG_ID);
        when(agentRepository.existsByIdAndOrganizationIdStrict(AGENT_ID, ORG_ID))
                .thenReturn(true);
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG_ID))
                .thenReturn(Optional.of(orgSkill));

        skillService.setAgentSkills(AGENT_ID, TEAMMATE_TENANT, ORG_ID,
                List.of(new SkillService.SkillAssignment(SKILL_ID)));

        verify(agentSkillRepository).deleteByAgentId(AGENT_ID);
        verify(agentSkillRepository).save(any(AgentSkillEntity.class));
    }

    @Test
    @DisplayName("setAgentSkills rejects when agent belongs to a different workspace than caller")
    void setAgentSkillsRejectsCrossWorkspaceAgent() {
        when(agentRepository.existsByIdAndOrganizationIdStrict(AGENT_ID, "org_other"))
                .thenReturn(false);

        assertThatThrownBy(() -> skillService.setAgentSkills(AGENT_ID, TEAMMATE_TENANT, "org_other",
                List.of(new SkillService.SkillAssignment(SKILL_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found or workspace mismatch");

        verify(agentSkillRepository, never()).deleteByAgentId(any());
        verify(agentSkillRepository, never()).save(any());
    }
}
