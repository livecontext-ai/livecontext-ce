package com.apimarketplace.agent.prompt;

import com.apimarketplace.agent.prompt.DefaultSystemPrompts.ModularPromptResult;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts.PromptModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for the modular prompt builder system.
 * Validates that modules correctly register tool names and that the prompt
 * contains foundation sections (no inline module descriptions since help is used).
 */
@DisplayName("DefaultSystemPrompts - Modular Builder")
class DefaultSystemPromptsModularTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE ISOLATION - each module maps to its tools only
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Module Isolation")
    class ModuleIsolationTests {

        @Test
        @DisplayName("CATALOG module provides catalog tool")
        void catalogModuleProvidesCatalogTools() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("catalog"), false);

            assertThat(result.coreToolNames())
                .containsExactlyInAnyOrder("catalog");
            // Prompt has foundation + help first, no inline module descriptions
            assertThat(result.systemPrompt())
                .contains("Help First")
                .contains("# Rules");
        }

        @Test
        @DisplayName("TABLE module provides table tool only")
        void tableModuleProvidesSingleTool() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("table"), false);

            assertThat(result.coreToolNames()).containsExactly("table");
            assertThat(result.systemPrompt()).contains("Help First");
        }

        @Test
        @DisplayName("INTERFACE module provides interface tool only")
        void interfaceModuleProvidesSingleTool() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("interface"), false);

            assertThat(result.coreToolNames()).containsExactly("interface");
        }

        @Test
        @DisplayName("INTERFACE module hints the native screenshot/pdf output (discoverability)")
        void interfaceModuleMentionsNativeScreenshotAndPdf() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("interface"), false);

            assertThat(result.systemPrompt())
                .as("agents must be able to discover from the base prompt that an interface node can natively emit a screenshot/pdf")
                .contains("generateScreenshot")
                .contains("generatePdf");
        }

        @Test
        @DisplayName("AGENT module provides agent tool only")
        void agentModuleProvidesSingleTool() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("agent"), false);

            assertThat(result.coreToolNames()).containsExactly("agent");
        }

        @Test
        @DisplayName("SKILL module provides skill tool only")
        void skillModuleProvidesSingleTool() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("skill"), false);

            assertThat(result.coreToolNames()).containsExactly("skill");
        }

        @Test
        @DisplayName("WORKFLOW module provides workflow tool")
        void workflowModuleProvidesSingleTool() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("workflow"), false);

            assertThat(result.coreToolNames())
                .containsExactlyInAnyOrder("workflow");
        }

        @Test
        @DisplayName("APPLICATION module provides application tool only")
        void applicationModuleProvidesSingleTool() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("application"), false);

            assertThat(result.coreToolNames()).containsExactly("application");
        }

        @Test
        @DisplayName("WEB_SEARCH module provides web_search tool only")
        void webSearchModuleProvidesSingleTool() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("web_search"), false);

            assertThat(result.coreToolNames()).containsExactly("web_search");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE COMBINATIONS - verify correct aggregation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Module Combinations")
    class ModuleCombinationTests {

        @Test
        @DisplayName("table + interface includes both tool sets")
        void tableAndInterfaceCombined() {
            ModularPromptResult result = DefaultSystemPrompts.build(
                Set.of("table", "interface"), false);

            assertThat(result.coreToolNames())
                .containsExactlyInAnyOrder("table", "interface");
        }

        @Test
        @DisplayName("catalog + table + agent = 3 tools total")
        void threeModulesCombined() {
            ModularPromptResult result = DefaultSystemPrompts.build(
                Set.of("catalog", "table", "agent"), false);

            assertThat(result.coreToolNames())
                .containsExactlyInAnyOrder("catalog", "table", "agent");
        }

        @Test
        @DisplayName("all modules combined equals buildDefault tool names")
        void allModulesCombinedEqualsBuildDefault() {
            Set<String> allKeys = new HashSet<>();
            for (PromptModule m : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
                allKeys.add(m.key());
            }

            ModularPromptResult explicit = DefaultSystemPrompts.build(allKeys, true);
            ModularPromptResult defaults = DefaultSystemPrompts.buildDefault();

            assertThat(explicit.coreToolNames()).isEqualTo(defaults.coreToolNames());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELP FIRST - conditional inclusion
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Help First Rule")
    class HelpFirstTests {

        @Test
        @DisplayName("help first included when any single module is active")
        void helpFirstWithSingleModule() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("skill"), false);
            assertThat(result.systemPrompt()).contains("Help First");
            assertThat(result.systemPrompt()).contains("action='help'");
        }

        @Test
        @DisplayName("help first NOT included when no modules active")
        void helpFirstNotIncludedWithNoModules() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of(), false);
            assertThat(result.systemPrompt()).doesNotContain("Help First");
        }

        @Test
        @DisplayName("help first applies to all tools")
        void helpFirstAppliesToAllTools() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("catalog", "table"), false);
            assertThat(result.systemPrompt())
                .contains("Help First")
                .contains("action='help'");
        }

        @ParameterizedTest
        @ValueSource(strings = {"catalog", "table", "interface", "agent", "skill", "workflow", "application"})
        @DisplayName("help first included for each individual module")
        void helpFirstIncludedForEachModule(String moduleKey) {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of(moduleKey), false);
            assertThat(result.systemPrompt())
                .as("HELP_FIRST should be present for module '%s'", moduleKey)
                .contains("Help First");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVERSATION MODE - removed sections stay removed
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Conversation Mode (removed sections)")
    class ConversationModeTests {

        @Test
        @DisplayName("Task Management and Automation Hint are never included regardless of conversationMode")
        void taskManagementAndAutomationHintNeverIncluded() {
            ModularPromptResult withConv = DefaultSystemPrompts.build(Set.of("workflow"), true);
            ModularPromptResult withoutConv = DefaultSystemPrompts.build(null, false);
            ModularPromptResult defaultResult = DefaultSystemPrompts.buildDefault();

            assertThat(withConv.systemPrompt()).doesNotContain("Task Management");
            assertThat(withConv.systemPrompt()).doesNotContain("When to Suggest Workflows");
            assertThat(withoutConv.systemPrompt()).doesNotContain("Task Management");
            assertThat(defaultResult.systemPrompt()).doesNotContain("Task Management");
            assertThat(defaultResult.systemPrompt()).doesNotContain("When to Suggest Workflows");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT/TOOL SYNC - tool names registered correctly
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Prompt/Tool Name Synchronization")
    class PromptToolSyncTests {

        @Test
        @DisplayName("tool names correctly filtered by module selection")
        void toolNamesFilteredByModuleSelection() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("table"), false);

            assertThat(result.coreToolNames()).contains("table");
            assertThat(result.coreToolNames()).doesNotContain("interface", "catalog", "workflow");
        }

        @Test
        @DisplayName("ModularPromptResult is immutable (coreToolNames is unmodifiable)")
        void coreToolNamesIsUnmodifiable() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("table"), false);

            assertThat(result.coreToolNames()).isNotNull();
            org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> result.coreToolNames().add("hacked_tool")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UNKNOWN MODULE KEYS - gracefully ignored
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("unknown module key is gracefully ignored")
    void unknownModuleKeyIgnored() {
        ModularPromptResult result = DefaultSystemPrompts.build(
            Set.of("nonexistent_module"), false);

        assertThat(result.coreToolNames()).isEmpty();
        assertThat(result.systemPrompt())
            .contains("# Rules")
            .doesNotContain("Help First");
    }

    @Test
    @DisplayName("mix of valid and unknown keys: only valid modules included")
    void mixValidAndUnknownKeys() {
        ModularPromptResult result = DefaultSystemPrompts.build(
            Set.of("table", "unknown_module", "agent"), false);

        assertThat(result.coreToolNames())
            .containsExactlyInAnyOrder("table", "agent");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE DEFINITION INTEGRITY
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Module Definition Integrity")
    class ModuleDefinitionTests {

        @Test
        @DisplayName("no module has empty tool names set")
        void noModuleHasEmptyToolNames() {
            for (PromptModule module : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
                assertThat(module.toolNames())
                    .as("Module '%s' should have at least one tool", module.key())
                    .isNotEmpty();
            }
        }

        @Test
        @DisplayName("no module has null or blank key")
        void noModuleHasBlankKey() {
            for (PromptModule module : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
                assertThat(module.key())
                    .as("Module key should not be blank")
                    .isNotBlank();
            }
        }

        @Test
        @DisplayName("no module has empty prompt section")
        void noModuleHasEmptyPromptSection() {
            for (PromptModule module : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
                assertThat(module.promptSection())
                    .as("Module '%s' should have a prompt section", module.key())
                    .isNotBlank();
            }
        }

        @Test
        @DisplayName("no tool name appears in multiple modules")
        void noToolNameDuplicatedAcrossModules() {
            Set<String> seen = new HashSet<>();
            for (PromptModule module : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
                for (String toolName : module.toolNames()) {
                    assertThat(seen.add(toolName))
                        .as("Tool '%s' should not appear in multiple modules", toolName)
                        .isTrue();
                }
            }
        }

        @Test
        @DisplayName("getAllCoreToolNames matches union of all modules")
        void getAllCoreToolNamesMatchesUnion() {
            Set<String> union = new HashSet<>();
            for (PromptModule module : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
                union.addAll(module.toolNames());
            }

            assertThat(DefaultSystemPrompts.getAllCoreToolNames()).isEqualTo(union);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WORKFLOW AGENT SCENARIO - realistic config combos
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Workflow Agent Scenarios")
    class WorkflowAgentScenarioTests {

        @Test
        @DisplayName("Workflow agent with only table+catalog: no interface/workflow/agent tools")
        void workflowAgentWithTableAndCatalogOnly() {
            ModularPromptResult result = DefaultSystemPrompts.build(
                Set.of("catalog", "table"), false);

            assertThat(result.coreToolNames())
                .containsExactlyInAnyOrder("catalog", "table")
                .doesNotContain("interface", "agent", "workflow", "application");
        }

        @Test
        @DisplayName("Workflow agent never includes Task Management or Automation Hint")
        void workflowAgentNeverIncludesRemovedSections() {
            ModularPromptResult withMemory = DefaultSystemPrompts.build(
                Set.of("catalog", "table", "skill"), true);
            ModularPromptResult withoutMemory = DefaultSystemPrompts.build(
                Set.of("catalog", "table", "skill"), false);

            assertThat(withMemory.systemPrompt())
                .doesNotContain("Task Management")
                .doesNotContain("When to Suggest Workflows");
            assertThat(withoutMemory.systemPrompt())
                .doesNotContain("Task Management")
                .doesNotContain("When to Suggest Workflows");
        }

        @Test
        @DisplayName("Workflow agent with all resources blocked: only foundation")
        void workflowAgentWithAllResourcesBlocked() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of(), false);

            assertThat(result.coreToolNames()).isEmpty();
            assertThat(result.systemPrompt())
                .contains("# Rules")
                .doesNotContain("Help First");
        }
    }
}
