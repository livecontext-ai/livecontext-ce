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
            .contains("stop repeating that approach");
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
            .contains("stop repeating that approach")
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
                DefaultSystemPrompts.buildAgentDefault(null).systemPrompt()        // agent agentIntro()
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
        @DisplayName("null enabledModuleKeys = all modules enabled")
        void nullKeysEnablesAllModules() {
            ModularPromptResult result = DefaultSystemPrompts.build(null, false);

            assertThat(result.coreToolNames())
                .contains("catalog",
                    "table", "interface", "agent", "skill",
                    "workflow",
                    "application", "web_search", "generation");
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

    /**
     * The keys, not the count.
     *
     * <p>A size assertion fails for the right reason exactly once and is then edited to the new
     * number, which is what a number invites. What this list actually decides is whether a tool
     * is visible AT ALL: {@code build()} collects the core tool names from here, and every
     * consumer filters strictly against that set, so a module missing from it is a tool that is
     * registered, routed, and then silently absent from every agent's tool list. Naming the keys
     * makes a removal fail as loudly as an addition.
     */
    @Test
    @DisplayName("ALL_RESOURCE_MODULES lists exactly the resources an agent can be given")
    void allResourceModulesAreDeclared() {
        assertThat(DefaultSystemPrompts.ALL_RESOURCE_MODULES)
                .extracting(DefaultSystemPrompts.PromptModule::key)
                .containsExactly(
                        "catalog", "table", "interface", "agent", "skill", "memory", "workflow",
                        "application", "web_search", "generation", "files", "mailbox", "wait",
                        "ask_user");
    }

    /**
     * Memory is declarative knowledge; SKILL next to it is procedural. The routing
     * line has to carry that distinction and the declarative-phrasing rule itself,
     * because the failure mode is not the model ignoring the tool: it is the model
     * storing "always do X" as a memory, which is then re-read as a standing
     * directive in every later conversation in the workspace.
     */
    @Test
    @DisplayName("MEMORY module is registered with key 'memory' and teaches declarative-not-imperative")
    void memoryModuleRegistered() {
        assertThat(DefaultSystemPrompts.MEMORY.key()).isEqualTo("memory");
        assertThat(DefaultSystemPrompts.MEMORY.toolNames()).containsExactly("memory");
        assertThat(DefaultSystemPrompts.MEMORY.promptSection())
                .contains("Declarative facts")
                .contains("never instructions")
                .contains("memory(action='get'")
                .contains("slug AND scope")
                .contains("current user's request takes priority")
                .contains("must not interrupt the task");
    }

    @Test
    @DisplayName("granting only the memory module resolves to exactly the memory tool")
    void memoryModuleResolvesToItsToolAlone() {
        ModularPromptResult result = DefaultSystemPrompts.build(Set.of("memory"), false);
        assertThat(result.coreToolNames()).containsExactly("memory");
    }

    /**
     * A module is the ONLY way a tool can be granted to a restricted agent: the
     * enabled module set is what resolves to the core tool names the agent is handed.
     * Without this entry the generation tool would be unreachable for every agent
     * that has a toolsConfig, while still being handed to those that have none.
     */
    @Test
    @DisplayName("GENERATION module is registered with key 'generation' and the generation tool")
    void generationModuleRegistered() {
        assertThat(DefaultSystemPrompts.GENERATION.key()).isEqualTo("generation");
        assertThat(DefaultSystemPrompts.GENERATION.toolNames()).containsExactly("generation");
        assertThat(DefaultSystemPrompts.GENERATION.promptSection())
                .contains("generation", "models", "credits");
    }

    @Test
    @DisplayName("granting only the generation module resolves to exactly the generation tool")
    void generationModuleResolvesToItsToolAlone() {
        ModularPromptResult result = DefaultSystemPrompts.build(Set.of("generation"), false);

        assertThat(result.coreToolNames()).containsExactly("generation");
        assertThat(result.systemPrompt()).contains("generation(action='models')");
    }

    @Test
    @DisplayName("FILES module is registered with key 'files' and the files tool")
    void filesModuleRegistered() {
        assertThat(DefaultSystemPrompts.FILES.key()).isEqualTo("files");
        assertThat(DefaultSystemPrompts.FILES.toolNames()).containsExactly("files");
        assertThat(DefaultSystemPrompts.FILES.promptSection())
                .contains("files", "list", "view", "visualize", "help");
    }

    /**
     * The legacy image-generation module was retired with its tool. No module may
     * advertise it again: the routing table an agent reads is built from these
     * modules, so a re-added entry would teach the model to call a tool that no
     * provider serves.
     */
    @Test
    @DisplayName("no module advertises the retired image_generation tool")
    void retiredImageGenerationModuleIsGone() {
        assertThat(DefaultSystemPrompts.ALL_RESOURCE_MODULES)
                .extracting(DefaultSystemPrompts.PromptModule::key)
                .doesNotContain("image_generation");
        assertThat(DefaultSystemPrompts.getAllCoreToolNames())
                .doesNotContain("image_generation");
    }

    @Test
    @DisplayName("ASK_USER module is registered with key 'ask_user' and tells the agent how to end a pending turn")
    void askUserModuleRegistered() {
        assertThat(DefaultSystemPrompts.ASK_USER.key()).isEqualTo("ask_user");
        assertThat(DefaultSystemPrompts.ASK_USER.toolNames()).containsExactly("ask_user");
        assertThat(DefaultSystemPrompts.ASK_USER.promptSection())
                .contains("ask_user(action='ask'", "pending_user", "Other");
    }

    @Test
    @DisplayName("WAIT module is registered with key 'wait' and points at workflow wait_run for runs")
    void waitModuleRegistered() {
        assertThat(DefaultSystemPrompts.WAIT.key()).isEqualTo("wait");
        assertThat(DefaultSystemPrompts.WAIT.toolNames()).containsExactly("wait");
        assertThat(DefaultSystemPrompts.WAIT.promptSection())
                .contains("wait", "sleep", "wait_run");
    }

    /**
     * An agent only learns an action exists from the module line of the tools it was
     * granted. stop_run is the counterpart of execute, so BOTH tools that can execute
     * must advertise it: an agent granted only `application` never reads the workflow line.
     */
    @Test
    @DisplayName("WORKFLOW and APPLICATION modules both advertise stop_run, the counterpart of execute")
    void executeCapableModulesAdvertiseStopRun() {
        assertThat(DefaultSystemPrompts.WORKFLOW.promptSection())
                .contains("stop_run");
        assertThat(DefaultSystemPrompts.APPLICATION.promptSection())
                .contains("stop_run");
    }

    @Test
    @DisplayName("getAllCoreToolNames returns all tool names")
    void getAllCoreToolNamesReturnsAllToolNames() {
        Set<String> allNames = DefaultSystemPrompts.getAllCoreToolNames();

        assertThat(allNames)
            .contains("catalog",
                "table", "interface", "agent", "skill",
                "workflow",
                "application", "web_search", "generation", "files", "wait", "ask_user");
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

    // ═══════════════════════════════════════════════════════════════════════════════
    // buildAgentDefault(Set) - the routing table must not advertise what it will not grant
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("buildAgentDefault(enabledModuleKeys)")
    class BuildAgentDefaultScopedTests {

        /** The module set a caller with no toolsConfig gets: everything but the opt-ins. */
        private final Set<String> noConfigModules =
            com.apimarketplace.agent.config.AgentModuleResolver.NO_CONFIG_MODULES;

        @Test
        @DisplayName("scoped build lists ONLY the given modules and returns ONLY their tool names")
        void scopedBuildFiltersBothHalves() {
            ModularPromptResult result = DefaultSystemPrompts.buildAgentDefault(noConfigModules);

            assertThat(result.coreToolNames())
                .doesNotContain("generation", "image_generation")
                .contains("catalog", "table", "workflow", "files", "wait");
            assertThat(result.systemPrompt())
                .doesNotContain("- generation -")
                .doesNotContain("- image_generation -")
                .contains("- workflow -");
        }

        @Test
        @DisplayName("the routing table and the tool names agree for EVERY module - the prompt can never advertise an ungranted tool")
        void promptAndToolNamesAgreeForEveryModule() {
            // The concrete defect this guards: the no-arg build listed all twelve modules while
            // its caller handed over ten, so the prompt taught the model to call a tool it did
            // not have. Checked per module rather than on one example so a new module cannot
            // reintroduce the split.
            for (DefaultSystemPrompts.PromptModule module : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
                ModularPromptResult result = DefaultSystemPrompts.buildAgentDefault(noConfigModules);
                boolean listed = result.systemPrompt().contains(module.promptSection());
                boolean granted = result.coreToolNames().containsAll(module.toolNames());
                assertThat(listed)
                    .as("module '%s': prompt listing must match tool granting", module.key())
                    .isEqualTo(granted);
            }
        }

        @Test
        @DisplayName("an explicitly granted generation module IS listed and IS returned")
        void explicitGrantIsListedAndReturned() {
            Set<String> withGeneration = new java.util.LinkedHashSet<>(noConfigModules);
            withGeneration.add("generation");

            ModularPromptResult result = DefaultSystemPrompts.buildAgentDefault(withGeneration);

            assertThat(result.coreToolNames()).contains("generation").doesNotContain("image_generation");
            assertThat(result.systemPrompt()).contains("- generation -");
        }

        @Test
        @DisplayName("null keeps the legacy every-module behaviour (back-compat for the no-arg overload)")
        void nullKeysMeansAllModules() {
            ModularPromptResult result = DefaultSystemPrompts.buildAgentDefault(null);

            assertThat(result.coreToolNames()).containsAll(DefaultSystemPrompts.getAllCoreToolNames());
        }

        @Test
        @DisplayName("an EMPTY module set yields no routing table and no tools (mode=off)")
        void emptyKeysMeansNoModules() {
            ModularPromptResult result = DefaultSystemPrompts.buildAgentDefault(Set.of());

            assertThat(result.coreToolNames()).isEmpty();
            assertThat(result.systemPrompt()).doesNotContain("# Available Tools");
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
    @Test
    @DisplayName("Chat, scoped bridge and agent defaults pursue verified outcomes without retry loops")
    void completionRulesReachEveryPromptRoute() {
        for (String prompt : java.util.List.of(DefaultSystemPrompts.getDefault(),
                DefaultSystemPrompts.getAgentDefault(),
                DefaultSystemPrompts.build(Set.of("application"), false).systemPrompt())) {
            assertThat(prompt).contains("verified outcome", "stop repeating that approach",
                    "Respect permissions, user stops and budgets", "original task",
                    "data_inputs_schema", "get_run/get_node_output")
                    .doesNotContain("Errors: retry once, then report.", "fails twice → stop.");
        }
    }

}
