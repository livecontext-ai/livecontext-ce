package com.apimarketplace.agent.prompt;

import com.apimarketplace.agent.prompt.DefaultSystemPrompts.ModularPromptResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DefaultSystemPrompts modular architecture.
 */
class DefaultSystemPromptsTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    // FOUNDATION BLOCKS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CORE_RULES contains essential behavioral rules")
    void coreRulesContainsEssentialRules() {
        String coreRules = DefaultSystemPrompts.getCoreRules();

        assertThat(coreRules)
            .contains("# Rules")
            .contains("fails twice");
    }

    @Test
    @DisplayName("RESPONSE_STYLE contains formatting guidelines")
    void responseStyleContainsFormattingGuidelines() {
        String responseStyle = DefaultSystemPrompts.getResponseStyle();

        assertThat(responseStyle)
            .contains("Markdown")
            .contains("marker");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // VERSATILE_AGENT (default = all modules + conversation mode)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("VERSATILE_AGENT includes foundation and help first")
    void versatileAgentIncludesFoundation() {
        String prompt = DefaultSystemPrompts.VERSATILE_AGENT;

        assertThat(prompt)
            .contains("# Rules")
            .contains("fails twice")
            .contains("Response Style")
            .contains("Help First")
            .contains("action='help'");
    }

    @Test
    @DisplayName("VERSATILE_AGENT includes help first rule")
    void versatileAgentIncludesHelpFirst() {
        assertThat(DefaultSystemPrompts.VERSATILE_AGENT)
            .contains("Help First")
            .contains("action='help'");
    }

    @Test
    @DisplayName("VERSATILE_AGENT does not include removed conversation modules")
    void versatileAgentDoesNotIncludeRemovedModules() {
        String prompt = DefaultSystemPrompts.VERSATILE_AGENT;

        assertThat(prompt)
            .doesNotContain("Task Management")
            .doesNotContain("When to Suggest Workflows");
    }

    @Test
    @DisplayName("VERSATILE_AGENT structurally matches buildDefault result (ignoring timestamp)")
    void versatileAgentEqualsBuildDefault() {
        // VERSATILE_AGENT is initialized at class-load time with a frozen timestamp.
        // buildDefault() generates a fresh timestamp on each call.
        // Strip the entire line containing the dynamic timestamp before comparing.
        String versatile = stripTimestamp(DefaultSystemPrompts.VERSATILE_AGENT);
        String buildDefault = stripTimestamp(DefaultSystemPrompts.buildDefault().systemPrompt());
        assertThat(versatile).isEqualTo(buildDefault);
    }

    @Test
    @DisplayName("date line is date-only (no minute clock) to keep the cached prompt prefix stable within a day")
    void dateLineIsDateOnlyForCacheStability() {
        // A minute-precision timestamp at the top of the system prompt lives in the
        // provider's cached prefix and busts it on every new minute. The line must
        // carry only today's UTC date (yyyy-MM-dd) so the prefix stays stable for 24h.
        for (String prompt : new String[] {
                DefaultSystemPrompts.build(Set.of("table"), false).systemPrompt(), // chat intro()
                DefaultSystemPrompts.buildAgentDefault().systemPrompt()            // agent agentIntro()
        }) {
            // date present in yyyy-MM-dd form, with the label space restored
            assertThat(prompt).containsPattern("Current date: \\d{4}-\\d{2}-\\d{2}");
            // old minute-precision label gone
            assertThat(prompt).doesNotContain("Current date and time:");
            // forward-guard: never re-add a clock to the date line (a per-minute value here
            // would re-bust the cached prefix). #1 and #2 above are what fail on the pre-fix code.
            assertThat(prompt).doesNotContainPattern("Current date:.*\\d{2}:\\d{2}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MODULAR BUILDER
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("build()")
    class BuildTests {

        @Test
        @DisplayName("null enabledModuleKeys = all modules enabled (incl. image_generation)")
        void nullKeysEnablesAllModules() {
            ModularPromptResult result = DefaultSystemPrompts.build(null, false);

            assertThat(result.coreToolNames())
                .contains("catalog",
                    "table", "interface", "agent", "skill",
                    "workflow",
                    "application", "web_search", "image_generation");
        }

        @Test
        @DisplayName("empty enabledModuleKeys = no resource modules")
        void emptyKeysDisablesAllModules() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of(), false);

            assertThat(result.coreToolNames()).isEmpty();
            assertThat(result.systemPrompt())
                .doesNotContain("Available Tools")
                .doesNotContain("Help First");
        }

        @Test
        @DisplayName("foundation always included even with no modules")
        void foundationAlwaysIncluded() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of(), false);

            assertThat(result.systemPrompt())
                .contains("# Rules")
                .contains("Response Style");
        }

        @Test
        @DisplayName("single module includes only its tools")
        void singleModuleIncludesOnlyItsTools() {
            ModularPromptResult result = DefaultSystemPrompts.build(Set.of("table"), false);

            assertThat(result.coreToolNames())
                .containsExactly("table");
            assertThat(result.systemPrompt())
                .contains("table")
                .doesNotContain("STATEFUL builder")
                .doesNotContain("catalog(action=");
        }

        @Test
        @DisplayName("multiple modules combine their tools")
        void multipleModulesCombineTools() {
            ModularPromptResult result = DefaultSystemPrompts.build(
                Set.of("catalog", "workflow"), false);

            assertThat(result.coreToolNames())
                .contains("catalog", "workflow")
                .doesNotContain("table", "interface", "agent");
        }

        @Test
        @DisplayName("conversationMode parameter has no effect on prompt content")
        void conversationModeHasNoEffect() {
            ModularPromptResult withConv = DefaultSystemPrompts.build(Set.of("table"), true);
            ModularPromptResult withoutConv = DefaultSystemPrompts.build(Set.of("table"), false);

            assertThat(withConv.systemPrompt()).doesNotContain("Task Management");
            assertThat(withoutConv.systemPrompt()).doesNotContain("Task Management");
            assertThat(withConv.systemPrompt()).doesNotContain("When to Suggest Workflows");
        }

        @Test
        @DisplayName("help first included when any module is active")
        void helpFirstIncludedWhenModulesActive() {
            ModularPromptResult withModule = DefaultSystemPrompts.build(Set.of("agent"), false);
            assertThat(withModule.systemPrompt()).contains("Help First");

            ModularPromptResult noModules = DefaultSystemPrompts.build(Set.of(), false);
            assertThat(noModules.systemPrompt()).doesNotContain("Help First");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MODULE DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ALL_RESOURCE_MODULES contains 10 modules (incl. image_generation, files)")
    void allResourceModulesContains10Modules() {
        assertThat(DefaultSystemPrompts.ALL_RESOURCE_MODULES).hasSize(10);
    }

    @Test
    @DisplayName("FILES module is registered with key 'files' and the files tool")
    void filesModuleRegistered() {
        assertThat(DefaultSystemPrompts.FILES.key()).isEqualTo("files");
        assertThat(DefaultSystemPrompts.FILES.toolNames()).containsExactly("files");
        assertThat(DefaultSystemPrompts.FILES.promptSection())
                .contains("files", "list", "view", "visualize", "help");
    }

    @Test
    @DisplayName("IMAGE_GENERATION module is registered with key 'image_generation'")
    void imageGenerationModuleRegistered() {
        assertThat(DefaultSystemPrompts.IMAGE_GENERATION.key()).isEqualTo("image_generation");
        assertThat(DefaultSystemPrompts.IMAGE_GENERATION.toolNames()).containsExactly("image_generation");
        assertThat(DefaultSystemPrompts.IMAGE_GENERATION.promptSection())
                .contains("image_generation", "credits", "help");
    }

    @Test
    @DisplayName("getAllCoreToolNames returns all tool names")
    void getAllCoreToolNamesReturnsAllToolNames() {
        Set<String> allNames = DefaultSystemPrompts.getAllCoreToolNames();

        assertThat(allNames)
            .contains("catalog",
                "table", "interface", "agent", "skill",
                "workflow",
                "application", "web_search", "image_generation", "files");
    }

    @Test
    @DisplayName("each module has unique key")
    void eachModuleHasUniqueKey() {
        long distinctKeys = DefaultSystemPrompts.ALL_RESOURCE_MODULES.stream()
            .map(DefaultSystemPrompts.PromptModule::key)
            .distinct()
            .count();
        assertThat(distinctKeys).isEqualTo(DefaultSystemPrompts.ALL_RESOURCE_MODULES.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // WORKFLOW CONVERSATION PROMPT
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buildWorkflowPrompt includes foundation + workflow context")
    void buildWorkflowPromptIncludesFoundationAndContext() {
        String prompt = DefaultSystemPrompts.buildWorkflowPrompt(
            "My Workflow", "wf-abc", "RUNNING",
            "trigger → step", "ds-789", "Last run: OK"
        );

        assertThat(prompt)
            .contains("# Rules")
            .contains("Response Style")
            .contains("My Workflow")
            .contains("wf-abc")
            .contains("RUNNING")
            .contains("workflow(action='load', id='wf-abc')")
            .doesNotContain("Task Management");
    }

    @Test
    @DisplayName("buildWorkflowPrompt handles null values")
    void buildWorkflowPromptHandlesNullValues() {
        String prompt = DefaultSystemPrompts.buildWorkflowPrompt(
            null, null, null, null, null, null
        );

        assertThat(prompt)
            .contains("Unknown")
            .contains("unknown")
            .contains("UNKNOWN")
            .contains("(no flow available)")
            .contains("No execution history");
    }

    @Test
    @DisplayName("buildWorkflowPrompt read-only mode")
    void buildWorkflowPromptReadOnly() {
        String prompt = DefaultSystemPrompts.buildWorkflowPrompt(
            "Test", "wf-1", "COMPLETED", "trigger → step", null, null, true
        );

        assertThat(prompt).contains("READ-ONLY MODE");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getDefault returns prompt structurally matching VERSATILE_AGENT")
    void getDefaultReturnsVersatileAgent() {
        // getDefault() generates a fresh timestamp; VERSATILE_AGENT has a frozen one.
        // Strip the entire timestamp line before comparing.
        String getDefault = stripTimestamp(DefaultSystemPrompts.getDefault());
        String versatile = stripTimestamp(DefaultSystemPrompts.VERSATILE_AGENT);
        assertThat(getDefault).isEqualTo(versatile);
    }

    @Test
    @DisplayName("getByType returns same prompt as getDefault for all known types")
    void getByTypeReturnsVersatileAgent() {
        // All types resolve to getDefault(), so they should be equal to each other
        // (called within the same test = same minute = same timestamp).
        String defaultPrompt = DefaultSystemPrompts.getDefault();
        assertThat(DefaultSystemPrompts.getByType("versatile")).isEqualTo(defaultPrompt);
        assertThat(DefaultSystemPrompts.getByType("default")).isEqualTo(defaultPrompt);
        assertThat(DefaultSystemPrompts.getByType(null)).isEqualTo(defaultPrompt);
        assertThat(DefaultSystemPrompts.getByType("unknown")).isEqualTo(defaultPrompt);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // NO DUPLICATION
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Rules section appears exactly once in VERSATILE_AGENT")
    void criticalRulesAppearsOnceInVersatileAgent() {
        assertThat(countOccurrences(DefaultSystemPrompts.VERSATILE_AGENT, "# Rules"))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("Rules section appears exactly once in buildWorkflowPrompt")
    void criticalRulesAppearsOnceInWorkflowPrompt() {
        String prompt = DefaultSystemPrompts.buildWorkflowPrompt(
            "Test", "wf-123", "COMPLETED", "trigger → step", "ds-456", null
        );
        assertThat(countOccurrences(prompt, "# Rules")).isEqualTo(1);
    }

    @Test
    @DisplayName("Response Style appears exactly once in each prompt")
    void responseStyleAppearsOnceInEachPrompt() {
        assertThat(countOccurrences(DefaultSystemPrompts.VERSATILE_AGENT, "# Response Style"))
            .isEqualTo(1);

        String workflowPrompt = DefaultSystemPrompts.buildWorkflowPrompt(
            "Test", "wf-123", "COMPLETED", "trigger → step", "ds-456", null
        );
        assertThat(countOccurrences(workflowPrompt, "# Response Style"))
            .isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // APPLICATION DISCOVERY - folded into the application module line
    //
    // The marketplace-discovery cue used to be a separate chat-only block appended via
    // getDefaultWithDiscoveryReflex(). It now lives ON the APPLICATION module line, so it
    // ships wherever tools are listed (chat AND scoped agents) instead of a standalone
    // section. The load-bearing elements below were each empirically required: slim
    // variants missing the concrete example or the explicit precedence words were IGNORED
    // by the LLM at E2E (prod bug 9fdc4ab0, convs b412730a / ebb25d65 - 24-40 web_search
    // calls, zero application calls). Assert on the module line directly so the guards
    // survive future prompt restructuring.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Application discovery cue (on the application module line)")
    class ApplicationDiscovery {

        private String appLine() {
            return DefaultSystemPrompts.APPLICATION.promptSection();
        }

        @Test
        @DisplayName("application line carries the discovery cue - my is the primary path, help is the fallback")
        void carriesDiscoveryCue() {
            String line = appLine();
            assertThat(line)
                .contains("your toolbox")
                .contains("application(action='my')")
                .contains("application(action='help')")
                .contains("domain task");
            // my is "what the user owns" - must lead. help is "what the tool does" - only when unsure.
            // Swapping them silently would erode the prod-bug fix because help returns docs, not owned apps.
            assertThat(line.indexOf("action='my'")).isLessThan(line.indexOf("action='help'"));
        }

        @Test
        @DisplayName("default chat prompt carries the cue (folded into the tool list)")
        void defaultPromptContainsCue() {
            assertThat(DefaultSystemPrompts.getDefault()).contains("your toolbox");
        }

        @Test
        @DisplayName("agent default prompt ALSO carries the cue now - it lives on the shared application module line")
        void agentDefaultContainsCue() {
            assertThat(DefaultSystemPrompts.getAgentDefault()).contains("your toolbox");
        }

        @Test
        @DisplayName("workflow-page prompt lists no tools, so it carries no discovery cue")
        void workflowPromptHasNoCue() {
            String workflowPrompt = DefaultSystemPrompts.buildWorkflowPrompt(
                "Test", "wf-123", "COMPLETED", "trigger → step", "ds-456", null
            );
            assertThat(workflowPrompt).doesNotContain("your toolbox");
        }

        @Test
        @DisplayName("cue wording stays soft (no MUST/REQUIRED/ALWAYS) to keep the agent's judgment in the loop")
        void cueWordingIsSoft() {
            assertThat(appLine())
                .doesNotContain("MUST")
                .doesNotContain("REQUIRED")
                .doesNotContain("ALWAYS")
                // soft modal "may" keeps the agent's judgment instead of forcing
                .contains("may");
        }

        @Test
        @DisplayName("application line stays terse (≤120 words) to avoid bloating the cached prompt prefix")
        void lineStaysConcise() {
            // Prod bug 9fdc4ab0: agent skipped application(my) on "fais une recherche airbnb".
            // Iterations showed the minimum viable cue needs ALL of: 1 concrete example (airbnb),
            // explicit precedence ("before web_search/catalog/workflow(init)"), and the fall-back
            // escape hatch. Folded onto the module line it must still stay terse - this guard caps
            // regrowth at 120 words so the (now always-shipped) line never bloats the cached prefix.
            long wordCount = java.util.Arrays.stream(appLine().split("\\s+"))
                .filter(w -> !w.isBlank())
                .count();
            assertThat(wordCount).isLessThanOrEqualTo(120L);
        }

        @Test
        @DisplayName("cue names the precedence signal - before web_search/catalog/workflow(init) - without which the agent ignores it (E2E-validated)")
        void cueNamesPrecedenceSignal() {
            // Empirically verified: a slim "On a domain task, try application(my)..." version
            // without explicit "Before web_search/catalog" was IGNORED by deepseek-chat on the
            // exact prod-bug prompt (conv b412730a ran 24 web_search calls, zero application calls).
            // The precedence words are the load-bearing signal that overrides the LLM's natural
            // "recherche → web_search" mapping. workflow(init) is part of the precedence set so the
            // agent doesn't spin a fresh workflow build for a task an existing app may cover.
            assertThat(appLine())
                .containsIgnoringCase("before")
                .contains("web_search")
                .contains("catalog")
                .contains("workflow(action='init')");
        }

        @Test
        @DisplayName("cue carries a concrete domain example (airbnb) - empirically required to anchor the LLM's pattern match")
        void cueCarriesConcreteExample() {
            // The variants without an inline example were ignored by deepseek-chat at E2E
            // (convs b412730a, ebb25d65). The version with "fais une recherche airbnb" inline
            // fired reliably. The literal example is the load-bearing anchor; deleting it would
            // silently reintroduce the prod bug.
            assertThat(appLine()).contains("airbnb");
        }
    }

    /**
     * Remove the dynamic "Current date: ..." line from a prompt.
     * This line carries today's UTC date, which can change between class-load and
     * test execution (across a day boundary), making exact equality assertions flaky.
     */
    private String stripTimestamp(String prompt) {
        return prompt.replaceAll("(?m)^.*Current date:.*$\\n?", "");
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
