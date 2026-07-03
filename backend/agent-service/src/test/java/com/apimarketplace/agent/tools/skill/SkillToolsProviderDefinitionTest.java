package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the LLM-facing skill tool definition after the 2026-07 compaction:
 * the description dropped the action list (already on the action enum) and the
 * ADDITIVE/max-10 assign rule (lives on skill_ids). These guards ensure the
 * surviving single copy of each rule is not lost in a later edit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillToolsProvider definition")
class SkillToolsProviderDefinitionTest {

    @Mock private SkillCrudModule crudModule;
    @Mock private SkillHelpModule helpModule;
    @Mock private SkillFolderModule folderModule;
    @Mock private SkillPublishModule publishModule;

    private SkillToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SkillToolsProvider(crudModule, helpModule, folderModule, publishModule);
    }

    private ToolParameter findParam(String name) {
        return provider.getTools().get(0).parameters().stream()
                .filter(p -> name.equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Parameter not found: " + name));
    }

    @Test
    @DisplayName("skill_ids keeps the ADDITIVE + max-10 assign rule (single remaining copy)")
    void skillIdsKeepsAdditiveRule() {
        String d = findParam("skill_ids").description();
        assertThat(d)
                .contains("ADDITIVE")
                .contains("Max 10");
    }

    @Test
    @DisplayName("description keeps the description-vs-instructions split and the marketplace rule")
    void descriptionKeepsCoreModel() {
        AgentToolDefinition tool = provider.getTools().get(0);
        assertThat(tool.description())
                // the token-economy mental model: short description always in prompt,
                // long instructions fetched on demand
                .contains("'description' (short)")
                .contains("'instructions' (detailed markdown)")
                .contains("on demand")
                .contains("publish requires title + interface_id");
    }

    @Test
    @DisplayName("every action stays discoverable via the action enum after the description dropped its list")
    void actionEnumStillCarriesAllActions() {
        ToolParameter action = findParam("action");
        assertThat(action.enumValues()).contains(
                "create", "get", "list", "update", "delete", "assign",
                "create_folder", "list_folders", "rename_folder", "move_folder", "delete_folder",
                "publish", "unpublish", "help");
    }
}
