package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Per-member org RBAC for the "skill" resource type. The backend write-restriction for skills
 * existed in publication-service but skill listing/read/CRUD in agent-service ignored the
 * per-member deny-list entirely (and the frontend modal could never set it). These tests pin the
 * deny-filter on list, the read gate on get, and the write gate on update/delete/move.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillController - per-member org RBAC (skill)")
class SkillControllerOrgRbacTest {

    @Mock private SkillService skillService;
    @Mock private SkillFolderService skillFolderService;
    @Mock private AgentService agentService;
    @Mock private TenantResolver tenantResolver;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private HttpServletRequest request;

    private SkillController controller;

    private static final String TENANT = "user-7";
    private static final String ORG = "org-42";
    private static final UUID SKILL_A = UUID.randomUUID();
    private static final UUID SKILL_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new SkillController(skillService, skillFolderService, agentService,
                tenantResolver, new RequestParameterExtractor(), orgAccessGuard);
        when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG);
    }

    private SkillEntity skill(UUID id, String name) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(name);
        return s;
    }

    @Test
    @DisplayName("listSkills drops the caller's DENY-restricted skills")
    void listSkillsFiltersRestricted() {
        when(skillService.listSkills(TENANT, ORG)).thenReturn(List.of(skill(SKILL_A, "A"), skill(SKILL_B, "B")));
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.getRestrictedResourceIds(ORG, TENANT, "skill", "MEMBER"))
                .thenReturn(Set.of(SKILL_B.toString()));

        ResponseEntity<List<SkillEntity>> resp = controller.listSkills(request, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).extracting(SkillEntity::getId).containsExactly(SKILL_A);
    }

    @Test
    @DisplayName("listSkills returns all when unrestricted (empty deny-list = no-op, backward compatible)")
    void listSkillsUnrestrictedReturnsAll() {
        when(skillService.listSkills(TENANT, ORG)).thenReturn(List.of(skill(SKILL_A, "A"), skill(SKILL_B, "B")));
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.getRestrictedResourceIds(ORG, TENANT, "skill", "MEMBER")).thenReturn(Set.of());

        ResponseEntity<List<SkillEntity>> resp = controller.listSkills(request, null);

        assertThat(resp.getBody()).hasSize(2);
    }

    @Test
    @DisplayName("getSkill of a DENY-restricted skill reads as not-found (404), never hitting the service")
    void getSkillDeniedWhenRestricted() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.getRestrictedResourceIds(ORG, TENANT, "skill", "MEMBER"))
                .thenReturn(Set.of(SKILL_A.toString()));

        ResponseEntity<SkillEntity> resp = controller.getSkill(SKILL_A, request, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(skillService, never()).getSkill(any(), any(), any());
    }

    @Test
    @DisplayName("getSkill of an allowed skill passes through to the service")
    void getSkillAllowedPassesThrough() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.getRestrictedResourceIds(ORG, TENANT, "skill", "MEMBER")).thenReturn(Set.of());
        when(skillService.getSkill(SKILL_A, TENANT, ORG)).thenReturn(Optional.of(skill(SKILL_A, "A")));

        ResponseEntity<SkillEntity> resp = controller.getSkill(SKILL_A, request, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("updateSkill is denied (403) when the member has a per-member write restriction on the skill")
    void updateSkillDeniedWhenWriteRestricted() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.canWrite(ORG, TENANT, "skill", SKILL_A.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<SkillEntity> resp = controller.updateSkill(SKILL_A, request, Map.of("name", "X"));

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        verify(skillService, never()).updateSkill(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("deleteSkill is denied (403) under a per-member write restriction")
    void deleteSkillDeniedWhenWriteRestricted() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.canWrite(ORG, TENANT, "skill", SKILL_A.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<Void> resp = controller.deleteSkill(SKILL_A, request, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        verify(skillService, never()).deleteSkill(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("moveSkill is denied (403) under a per-member write restriction")
    void moveSkillDeniedWhenWriteRestricted() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.canWrite(ORG, TENANT, "skill", SKILL_A.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<SkillEntity> resp = controller.moveSkill(SKILL_A, request, Map.of("folderId", UUID.randomUUID().toString()));

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        verify(skillService, never()).moveSkill(any(), any(), any(), any());
    }

    @Test
    @DisplayName("updateSkill proceeds when write is permitted (no restriction)")
    void updateSkillAllowedWhenWritePermitted() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
        when(orgAccessGuard.canWrite(ORG, TENANT, "skill", SKILL_A.toString(), "MEMBER")).thenReturn(true);
        when(skillService.updateSkill(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(skill(SKILL_A, "A"));

        ResponseEntity<SkillEntity> resp = controller.updateSkill(SKILL_A, request, Map.of("name", "A2"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(skillService).updateSkill(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
