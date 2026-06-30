package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InterfaceHelpModule")
class InterfaceHelpModuleTest {

    private InterfaceHelpModule module;

    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setUp() {
        module = new InterfaceHelpModule();
    }

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(TENANT);
    }

    // ==================== canHandle ====================

    @Test
    @DisplayName("canHandle should accept help action only")
    void canHandle() {
        assertThat(module.canHandle("help")).isTrue();
        assertThat(module.canHandle("create")).isFalse();
        assertThat(module.canHandle("get")).isFalse();
        assertThat(module.canHandle("list")).isFalse();
        assertThat(module.canHandle("update")).isFalse();
        assertThat(module.canHandle("delete")).isFalse();
    }

    // ==================== execute ====================

    @Test
    @DisplayName("Should return empty for non-help action")
    void shouldReturnEmptyForNonHelp() {
        Optional<ToolExecutionResult> result = module.execute("create", Map.of(), TENANT, ctx());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return help content successfully")
    void shouldReturnHelpContent() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
    }

    @Test
    @DisplayName("Help should contain all expected sections")
    @SuppressWarnings("unchecked")
    void helpShouldContainAllSections() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        assertThat(result).isPresent();

        Map<String, Object> data = (Map<String, Object>) result.get().data();
        assertThat(data).containsKeys(
            "1_concept", "2_create", "3_template_syntax",
            "3b_images_and_files",
            "4_interactive_html", "5_mistakes", "6_workflow", "7_actions"
        );
    }

    @Test
    @DisplayName("3b_images_and_files surfaces FileStorageHelp.get() verbatim (anti-drift)")
    @SuppressWarnings("unchecked")
    void filesSectionMatchesSharedHelp() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get().data();

        // Pin the verbatim equality between the interface help section and the
        // shared agent-common helper. If the help maps drift, this fails - and
        // the same assertion in CatalogHelpModuleTest fails too.
        Object section = data.get("3b_images_and_files");
        assertThat(section)
            .isEqualTo(com.apimarketplace.agent.tools.help.FileStorageHelp.get());
    }

    @Test
    @DisplayName("Help concept should explain standalone and workflow modes")
    @SuppressWarnings("unchecked")
    void helpConceptShouldExplainModes() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get().data();

        String concept = (String) data.get("1_concept");
        assertThat(concept).contains("STANDALONE");
        assertThat(concept).contains("WORKFLOW");
        assertThat(concept).contains("WEB PAGE");
    }

    @Test
    @DisplayName("Help should list all CRUD actions")
    @SuppressWarnings("unchecked")
    void helpShouldListAllActions() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        Map<String, String> actions = (Map<String, String>) data.get("7_actions");

        assertThat(actions).containsKeys("create", "get", "list", "update", "patch", "delete");
        // The patch help must let a context-less agent act: name the mechanism, scope one
        // target per call, show a worked example, and state the unique/replace_all rule.
        assertThat((String) actions.get("patch"))
            .contains("search/replace")
            .contains("target=")
            .contains("ONE template per call")
            .contains("Example")
            .contains("replace_all=true")
            .contains("EDITS existing text")          // patch edits, not adds
            .contains("interface(action='update')");  // how to ADD new content
    }

    @Test
    @DisplayName("Help should document template syntax")
    @SuppressWarnings("unchecked")
    void helpShouldDocumentTemplateSyntax() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        Map<String, Object> syntax = (Map<String, Object>) data.get("3_template_syntax");

        assertThat(syntax).containsKeys("variables", "mapping_rule", "only_supported", "default_no_braces", "dynamic_logic");
        assertThat((String) syntax.get("only_supported")).contains("{{variable}}");
        assertThat((String) syntax.get("default_no_braces"))
            .contains("CANNOT contain '}'")
            .contains("{{obj|{}}}");
    }

    @Test
    @DisplayName("Help should document common mistakes")
    @SuppressWarnings("unchecked")
    void helpShouldDocumentMistakes() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        List<String> mistakes = (List<String>) data.get("5_mistakes");

        // The mistakes list grew from 3 to 6 when the action_mapping
        // anti-patterns were added (commit 9e3666854, audit 9.4/10).
        // Bumped to 7 when the {{var|{}}} regex-collision warning was added.
        // Bumped to 9 when the two form-binding traps were added (input with
        // id-but-no-name, and a non-submit button inside the form) - the exact
        // pair that silently drops to/from/body on form submit.
        assertThat(mistakes).hasSize(9);
        assertThat(mistakes.get(0)).contains("WRONG");
        assertThat(mistakes).anySatisfy(m -> assertThat(m).contains("{{obj|{}}}"));
    }

    @Test
    @DisplayName("Help should work with null tenantId")
    void helpShouldWorkWithNullTenantId() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), null, ToolExecutionContext.empty());
        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
    }

    @Test
    @DisplayName("getToolDefinitions should return empty list")
    void getToolDefinitions() {
        assertThat(module.getToolDefinitions()).isEmpty();
    }
}
