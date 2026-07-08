package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillCrudModule Tests")
class SkillCrudModuleTest {

    @Mock private SkillService skillService;
    @Mock private AgentService agentService;

    private SkillCrudModule module;
    private AgentDefaultsConfig agentDefaults;

    private static final String TENANT = "tenant-123";
    private static final UUID SKILL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentDefaults = new AgentDefaultsConfig();
        module = new SkillCrudModule(skillService, agentService, agentDefaults);
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(TENANT, null, Map.of("turnId", "turn-1"), null, null, null, null, null);
    }

    /** Context whose credentials carry the platform ADMIN role (as the gateway injects via __userRoles__). */
    private ToolExecutionContext adminCtx() {
        return new ToolExecutionContext(TENANT, Map.of("__userRoles__", "USER,ADMIN"),
            Map.of("turnId", "turn-1"), null, null, null, null, null);
    }

    /** Context whose credentials carry only a non-admin role. */
    private ToolExecutionContext memberCtx() {
        return new ToolExecutionContext(TENANT, Map.of("__userRoles__", "USER"),
            Map.of("turnId", "turn-1"), null, null, null, null, null);
    }

    private SkillEntity mockSkill(UUID id, String name) {
        SkillEntity e = new SkillEntity();
        e.setId(id);
        e.setName(name);
        e.setDescription("A skill");
        e.setInstructions("## Steps\n1. Do things");
        e.setIsActive(true);
        return e;
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {
        @Test void create() { assertThat(module.canHandle("create")).isTrue(); }
        @Test void get() { assertThat(module.canHandle("get")).isTrue(); }
        @Test void list() { assertThat(module.canHandle("list")).isTrue(); }
        @Test void update() { assertThat(module.canHandle("update")).isTrue(); }
        @Test void delete() { assertThat(module.canHandle("delete")).isTrue(); }
        @Test void assign() { assertThat(module.canHandle("assign")).isTrue(); }
        @Test void rejectsHelp() { assertThat(module.canHandle("help")).isFalse(); }
        @Test void rejectsFolder() { assertThat(module.canHandle("create_folder")).isFalse(); }
    }

    @Nested
    @DisplayName("Create")
    class Create {

        @Test
        @DisplayName("Success with required params")
        void createSuccess() {
            SkillEntity created = mockSkill(SKILL_ID, "My Skill");
            when(skillService.createSkill(eq(TENANT), eq("My Skill"), eq("Does things"),
                isNull(), eq("## Steps"), isNull(), eq(false), eq(false), isNull()))
                .thenReturn(created);

            Map<String, Object> params = Map.of(
                "action", "create", "name", "My Skill",
                "description", "Does things", "instructions", "## Steps"
            );
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("CREATED");
        }

        @Test
        @DisplayName("Fails without name")
        void failsWithoutName() {
            Map<String, Object> params = Map.of("action", "create", "description", "x", "instructions", "y");
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("name");
        }

        @Test
        @DisplayName("Fails without description")
        void failsWithoutDesc() {
            Map<String, Object> params = Map.of("action", "create", "name", "X", "instructions", "y");
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("description");
        }

        @Test
        @DisplayName("Fails without instructions")
        void failsWithoutInstructions() {
            Map<String, Object> params = Map.of("action", "create", "name", "X", "description", "y");
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("instructions");
        }
    }

    @Nested
    @DisplayName("Get")
    class Get {

        @Test
        @DisplayName("Returns skill details")
        void getSuccess() {
            SkillEntity skill = mockSkill(SKILL_ID, "Test Skill");
            when(skillService.getSkill(SKILL_ID, TENANT)).thenReturn(Optional.of(skill));

            Map<String, Object> params = Map.of("action", "get", "skill_id", SKILL_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("Test Skill");
        }

        @Test
        @DisplayName("Handles default skill IDs")
        void getDefaultSkill() {
            // Default skills start with "default:" - these are handled by DefaultSkillsProvider
            Map<String, Object> params = Map.of("action", "get", "skill_id", "default:nonexistent");
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            // Should not call skillService for default skills
            verify(skillService, never()).getSkill(any(), any());
        }

        @Test
        @DisplayName("Fails without skill_id")
        void failsWithoutId() {
            Map<String, Object> params = Map.of("action", "get");
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }

        @Test
        @DisplayName("Fails when not found")
        void failsNotFound() {
            when(skillService.getSkill(SKILL_ID, TENANT)).thenReturn(Optional.empty());
            Map<String, Object> params = Map.of("action", "get", "skill_id", SKILL_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }
    }

    @Nested
    @DisplayName("List")
    class ListSkills {

        @Test
        @DisplayName("Returns combined default + user skills")
        void listSuccess() {
            SkillEntity s1 = mockSkill(UUID.randomUUID(), "Skill 1");
            when(skillService.listSkills(TENANT)).thenReturn(List.of(s1));

            Map<String, Object> params = Map.of("action", "list");
            Optional<ToolExecutionResult> result = module.execute("list", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("Skill 1");
        }

        @Test
        @DisplayName("query filters skills by name OR description (case-insensitive) before pagination")
        @SuppressWarnings("unchecked")
        void queryFiltersByNameAndDescription() {
            // Unique tokens so no built-in default skill collides with the filter.
            SkillEntity byName = mockSkill(UUID.randomUUID(), "ZzUnique Invoice Skill");
            SkillEntity byDesc = mockSkill(UUID.randomUUID(), "Ordinary Name");
            byDesc.setDescription("handles zzunique invoice work");
            when(skillService.listSkills(TENANT)).thenReturn(List.of(byName, byDesc));

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of("query", "zzunique invoice"), TENANT, ctx()).get().data();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("skills");
            assertThat(items).extracting(m -> m.get("name"))
                    .containsExactlyInAnyOrder("ZzUnique Invoice Skill", "Ordinary Name");
            assertThat(data.get("total")).isEqualTo(2L);
        }

        @Test
        @DisplayName("query with no matches returns empty + broaden hint (defaults + user skills both filtered)")
        @SuppressWarnings("unchecked")
        void queryNoMatchReturnsEmpty() {
            when(skillService.listSkills(TENANT)).thenReturn(List.of(mockSkill(UUID.randomUUID(), "Invoice")));

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of("query", "zzz-no-such-skill"), TENANT, ctx()).get().data();
            assertThat(data.get("count")).isEqualTo(0);
            assertThat(data.get("total")).isEqualTo(0L);
            Map<String, Object> hint = (Map<String, Object>) data.get("hint");
            assertThat(hint.get("action")).isEqualTo("broaden");
        }

        @Test
        @DisplayName("Emits canonical AgentListEnvelope keys (PR3 migration)")
        @SuppressWarnings("unchecked")
        void listEmitsCanonicalEnvelope() {
            SkillEntity s1 = mockSkill(UUID.randomUUID(), "Skill 1");
            when(skillService.listSkills(TENANT)).thenReturn(List.of(s1));

            Optional<ToolExecutionResult> result = module.execute("list", Map.of(), TENANT, ctx());
            Map<String, Object> data = (Map<String, Object>) result.get().data();

            assertThat(data).containsKeys("status", "kind", "skills",
                    "count", "total", "offset", "limit", "hasMore");
            assertThat(data.get("kind")).isEqualTo("skills");
            // Default skills + 1 user skill - exact count depends on default catalog.
            // We only assert the envelope shape; counts vary by environment.
            assertThat(data).doesNotContainKey("message");
        }
    }

    @Nested
    @DisplayName("Update")
    class Update {

        @Test
        @DisplayName("Updates skill fields")
        void updateSuccess() {
            SkillEntity updated = mockSkill(SKILL_ID, "Updated");
            when(skillService.updateSkill(eq(SKILL_ID), eq(TENANT), eq("Updated"),
                isNull(), isNull(), isNull(), isNull(), isNull(), anyBoolean()))
                .thenReturn(updated);

            Map<String, Object> params = Map.of("action", "update", "skill_id", SKILL_ID.toString(), "name", "Updated");
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("UPDATED");
        }

        @Test
        @DisplayName("Moves skill to folder when folder_id provided")
        void updateWithFolderMove() {
            UUID folderId = UUID.randomUUID();
            SkillEntity updated = mockSkill(SKILL_ID, "Moved");
            when(skillService.updateSkill(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "skill_id", SKILL_ID.toString(),
                "folder_id", folderId.toString()
            ));
            module.execute("update", params, TENANT, ctx());

            verify(skillService).moveSkill(SKILL_ID, TENANT, folderId);
        }

        @Test
        @DisplayName("Admin caller propagates callerIsAdmin=true (can edit global skills)")
        void updatePropagatesAdminFromContext() {
            SkillEntity updated = mockSkill(SKILL_ID, "Updated");
            when(skillService.updateSkill(eq(SKILL_ID), eq(TENANT), eq("Updated"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(true)))
                .thenReturn(updated);

            Map<String, Object> params = Map.of("action", "update", "skill_id", SKILL_ID.toString(), "name", "Updated");
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, adminCtx());

            assertThat(result.get().success()).isTrue();
            // The admin role from __userRoles__ MUST reach the service layer.
            verify(skillService).updateSkill(SKILL_ID, TENANT, "Updated", null, null, null, null, null, true);
        }

        @Test
        @DisplayName("Non-admin caller propagates callerIsAdmin=false")
        void updateNonAdminPropagatesFalse() {
            SkillEntity updated = mockSkill(SKILL_ID, "Updated");
            when(skillService.updateSkill(eq(SKILL_ID), eq(TENANT), eq("Updated"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(updated);

            Map<String, Object> params = Map.of("action", "update", "skill_id", SKILL_ID.toString(), "name", "Updated");
            module.execute("update", params, TENANT, memberCtx());

            verify(skillService).updateSkill(SKILL_ID, TENANT, "Updated", null, null, null, null, null, false);
        }
    }

    @Nested
    @DisplayName("Delete")
    class Delete {

        @Test
        @DisplayName("Deletes skill by ID")
        void deleteSuccess() {
            when(skillService.getSkill(SKILL_ID, TENANT))
                .thenReturn(Optional.of(mockSkill(SKILL_ID, "ToDelete")));

            Map<String, Object> params = Map.of("action", "delete", "skill_id", SKILL_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("delete", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("DELETED");
            verify(skillService).deleteSkill(SKILL_ID, TENANT, false);
        }

        @Test
        @DisplayName("Admin caller propagates callerIsAdmin=true (can delete global skills)")
        void deletePropagatesAdminFromContext() {
            when(skillService.getSkill(SKILL_ID, TENANT))
                .thenReturn(Optional.of(mockSkill(SKILL_ID, "ToDelete")));

            Map<String, Object> params = Map.of("action", "delete", "skill_id", SKILL_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("delete", params, TENANT, adminCtx());

            assertThat(result.get().success()).isTrue();
            verify(skillService).deleteSkill(SKILL_ID, TENANT, true);
        }
    }

    @Nested
    @DisplayName("Assign")
    class Assign {

        @Test
        @DisplayName("Assigns skills to agent")
        void assignSuccess() {
            UUID agentId = UUID.randomUUID();
            UUID skillId1 = UUID.randomUUID();
            UUID skillId2 = UUID.randomUUID();
            when(skillService.addAgentSkills(eq(agentId), eq(TENANT), anyList())).thenReturn(2);

            Map<String, Object> params = Map.of(
                "action", "assign", "agent_id", agentId.toString(),
                "skill_ids", List.of(skillId1.toString(), skillId2.toString())
            );
            Optional<ToolExecutionResult> result = module.execute("assign", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("ASSIGNED");
        }

        @Test
        @DisplayName("Fails without agent_id")
        void failsWithoutAgentId() {
            Map<String, Object> params = Map.of("action", "assign", "skill_ids", List.of("abc"));
            Optional<ToolExecutionResult> result = module.execute("assign", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }

        @Test
        @DisplayName("Fails without skill_ids")
        void failsWithoutSkillIds() {
            Map<String, Object> params = Map.of("action", "assign", "agent_id", UUID.randomUUID().toString());
            Optional<ToolExecutionResult> result = module.execute("assign", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }

        @Test
        @DisplayName("Fails with invalid UUID in skill_ids")
        void failsWithInvalidUuid() {
            Map<String, Object> params = Map.of(
                "action", "assign",
                "agent_id", UUID.randomUUID().toString(),
                "skill_ids", List.of("not-a-uuid")
            );
            Optional<ToolExecutionResult> result = module.execute("assign", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("Invalid skill UUID");
        }
    }

    // ==========================================================================
    // V100: per-agent unified maxPerResourcePerTurn override resolution
    // ==========================================================================
    @Nested
    @DisplayName("resolveMaxPerResourcePerTurn (V100 unified per-resource cap)")
    class ResolveMaxPerResourcePerTurn {

        private AgentEntity mockAgent(UUID id) {
            AgentEntity e = new AgentEntity();
            e.setId(id);
            e.setName("Caller");
            return e;
        }

        @Test
        @DisplayName("Returns YAML default (5) when no __agentId__ in credentials")
        void fallsBackWhenNoAgentId() {
            assertThat(module.resolveMaxPerResourcePerTurn(ctx())).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns per-agent override when entity has non-null positive value")
        void usesPerAgentOverride() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockAgent(callerId);
            caller.setMaxPerResourcePerTurn(25);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", callerId.toString(), "turnId", "turn-x"),
                null, null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(25);
        }

        @Test
        @DisplayName("Falls back to default when entity override is null")
        void fallsBackWhenOverrideNull() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockAgent(callerId);
            caller.setMaxPerResourcePerTurn(null);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", callerId.toString(), "turnId", "turn-x"),
                null, null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back on lookup exception (soft-fail)")
        void softFailsOnException() {
            UUID callerId = UUID.randomUUID();
            when(agentService.findById(callerId)).thenThrow(new RuntimeException("DB down"));

            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", callerId.toString(), "turnId", "turn-x"),
                null, null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }
    }
}
