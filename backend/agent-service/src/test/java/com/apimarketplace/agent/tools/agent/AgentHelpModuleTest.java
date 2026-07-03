package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code agent(action='help')} embeds the live model catalog
 * exactly once, grouped by provider, so the LLM has a single authoritative
 * list of valid (model_provider, model_name) pairs.
 *
 * <p>The catalog must stay in sync with {@link ModelCatalogService#listAvailableModels}
 * - both the help response and the {@code AgentCrudModule} validation path
 * read from the same method, so this test uses the same stub as that module.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentHelpModule - available_models catalog")
class AgentHelpModuleTest {

    @Mock private ModelCatalogService modelCatalogService;
    @Mock private BridgeAccessGuard bridgeAccessGuard;

    private AgentHelpModule module;

    @BeforeEach
    void setUp() {
        module = new AgentHelpModule(new AgentDefaultsConfig(), modelCatalogService);
    }

    private ToolExecutionContext contextWithRoles(String userId, String roles) {
        Map<String, Object> credentials = roles == null ? Map.of() : Map.of("__userRoles__", roles);
        return new ToolExecutionContext(userId, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private List<String> pairsOf(Optional<ToolExecutionResult> result) {
        Map<String, Object> data = (Map<String, Object>) result.orElseThrow().data();
        return (List<String>) data.get("pairs");
    }

    @Test
    @DisplayName("canHandle accepts 'help' and 'help_models', rejects everything else")
    void canHandle() {
        assertThat(module.canHandle("help")).isTrue();
        assertThat(module.canHandle("help_models")).isTrue();
        assertThat(module.canHandle("create")).isFalse();
        assertThat(module.canHandle("execute")).isFalse();
    }

    @Test
    @DisplayName("help_models groups enabled models by provider and includes flat pairs")
    @SuppressWarnings("unchecked")
    void helpModelsGroupsByProvider() {
        // Catalog moved from help to a dedicated help_models action. The
        // assertions stay the same - the LLM still needs the grouped + flat
        // shape - they just live behind the explicit action now.
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("anthropic", "claude-opus-4-6", "top", 1),
                new AvailableModel("anthropic", "claude-sonnet-4-6", "top", 1),
                new AvailableModel("openai", "gpt-5", "top", 1),
                new AvailableModel("google", "gemini-3-flash-preview", "mid", 1)
        ));

        Optional<ToolExecutionResult> result = module.execute("help_models", Map.of(), "tenant-x", null);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) result.get().data();

        Map<String, List<String>> providers = (Map<String, List<String>>) data.get("providers");
        assertThat(providers).containsOnlyKeys("anthropic", "openai", "google");
        assertThat(providers.get("anthropic")).containsExactly("claude-opus-4-6 (#1)", "claude-sonnet-4-6 (#1)");
        assertThat(providers.get("openai")).containsExactly("gpt-5 (#1)");
        assertThat(providers.get("google")).containsExactly("gemini-3-flash-preview (#1)");

        // Flat pairs list is provided for LLMs that struggle with nested maps
        // (some cheaper models miss the structure if we only ship the grouped
        // form - the flat list is a cheap safety net).
        List<String> pairs = (List<String>) data.get("pairs");
        assertThat(pairs).containsExactly(
                "anthropic/claude-opus-4-6 (#1)",
                "anthropic/claude-sonnet-4-6 (#1)",
                "openai/gpt-5 (#1)",
                "google/gemini-3-flash-preview (#1)"
        );

        assertThat((Integer) data.get("total_enabled")).isEqualTo(4);
        assertThat((Integer) data.get("returned")).isEqualTo(4);
        assertThat((String) data.get("note"))
                .contains("priority")
                .contains("OPTIONAL")
                .contains("model_substituted");
    }

    @Test
    @DisplayName("help_models returns empty catalog when no models are enabled")
    @SuppressWarnings("unchecked")
    void helpModelsEmptyCatalog() {
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of());

        Optional<ToolExecutionResult> result = module.execute("help_models", Map.of(), "tenant-x", null);

        assertThat(result).isPresent();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        Map<String, List<String>> providers = (Map<String, List<String>>) data.get("providers");
        assertThat(providers).isEmpty();
        assertThat((List<String>) data.get("pairs")).isEmpty();
        assertThat((Integer) data.get("total_enabled")).isEqualTo(0);
    }

    @Test
    @DisplayName("help_models truncates to top 30 globally by displayOrder (priority preserved)")
    @SuppressWarnings("unchecked")
    void helpModelsTruncatesToTop30() {
        // Build 40 models with strictly increasing displayOrder. listAvailableModels
        // is contracted to return them sorted by displayOrder ASC, so the slice
        // [0,30) must be ranks 1..30 - the top by priority - NOT a per-provider
        // cap. This is the regression guard requested by Audit A.
        List<AvailableModel> seeded = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            seeded.add(new AvailableModel("p" + (i % 3), "m" + i, "mid", i));
        }
        when(modelCatalogService.listAvailableModels()).thenReturn(seeded);

        Optional<ToolExecutionResult> result = module.execute("help_models", Map.of(), "tenant-x", null);

        Map<String, Object> data = (Map<String, Object>) result.get().data();
        assertThat((Integer) data.get("total_enabled")).isEqualTo(40);
        assertThat((Integer) data.get("returned")).isEqualTo(30);
        // Pairs are flattened in displayOrder order - assert the FIRST entry is
        // rank #1 and the LAST kept entry is rank #30. If a future change ever
        // accidentally truncates per-provider instead of globally, ranks 4..30
        // would disappear and this would catch it.
        List<String> pairs = (List<String>) data.get("pairs");
        assertThat(pairs).hasSize(30);
        assertThat(pairs.get(0)).contains("(#1)");
        assertThat(pairs.get(29)).contains("(#30)");
    }

    @Test
    @DisplayName("help advertises model_provider/model_name as OPTIONAL with substitution behaviour")
    @SuppressWarnings("unchecked")
    void helpFlagsModelFieldsAsOptional() {
        // Regression guard - the LLM was previously told these fields "MUST" be
        // valid pairs, which led it to always send them (and often send the
        // wrong one). Both fields are now optional and unknown values are
        // silently substituted, so the doc must say so explicitly - otherwise
        // the LLM keeps the old over-cautious behaviour.
        when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("openai");
        when(modelCatalogService.getEffectiveDefaultModel()).thenReturn("gpt-5");

        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), "tenant-x", null);
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        Map<String, Object> params = (Map<String, Object>) data.get("parameters");

        String providerDoc = (String) params.get("model_provider");
        String modelDoc = (String) params.get("model_name");
        assertThat(providerDoc).contains("OPTIONAL");
        assertThat(providerDoc).contains("model_substituted");
        assertThat(modelDoc).contains("OPTIONAL");
        assertThat(modelDoc).contains("model_substituted");

        // available_models is now a slim Map (full catalog moved to help_models).
        // Keep the redirect note + OPTIONAL + model_substituted so an LLM that
        // only reads available_models still gets the message AND knows where
        // to look for the live catalog.
        Map<String, Object> available = (Map<String, Object>) data.get("available_models");
        assertThat(available).isNotNull();
        assertThat((String) available.get("see_also")).contains("help_models");
        String note = (String) available.get("note");
        assertThat(note).contains("OPTIONAL");
        assertThat(note).contains("model_substituted");
        assertThat(note).contains("help_models");
        // The full catalog must NOT be embedded in default help - that's the
        // whole point of moving it to help_models. Regression guard against an
        // accidental re-inlining that would re-bloat every prompt.
        assertThat(available).doesNotContainKey("providers");
        assertThat(available).doesNotContainKey("pairs");
    }

    @Test
    @DisplayName("help still exposes actions / parameters / examples alongside the slim available_models")
    @SuppressWarnings("unchecked")
    void helpPreservesExistingSections() {
        // No listAvailableModels stub - executeHelp no longer touches it.
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), "tenant-x", null);

        assertThat(result).isPresent();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        // Regression guard - moving the catalog to help_models must not have
        // displaced any of the pre-existing help sections.
        assertThat(data).containsKeys("description", "actions", "concepts", "available_models",
                "parameters", "response_shape", "examples", "tips");
    }

    /**
     * Regression guard - the help's {@code actions} map is the LLM's table of
     * contents. Every action advertised by {@link AgentToolsProvider#VALID_ACTIONS}
     * (minus {@code help} itself, which is the action being called) MUST have
     * a documentation entry. Missing an entry means the LLM can see the action
     * in the enum but will find no help text for it.
     */
    @Test
    @DisplayName("help documents every action in VALID_ACTIONS")
    @SuppressWarnings("unchecked")
    void helpDocumentsEveryValidAction() {
        // No listAvailableModels stub - executeHelp no longer touches it (Mockito strict mode).
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), "tenant-x", null);

        assertThat(result).isPresent();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        Map<String, String> actions = (Map<String, String>) data.get("actions");
        assertThat(actions).isNotNull();

        // Every VALID_ACTIONS entry (except 'help' itself) must have a documentation
        // entry. Reflection is intentional - it keeps this test coupled to the
        // live source of truth instead of a hardcoded duplicate.
        List<String> validActions;
        try {
            java.lang.reflect.Field f = AgentToolsProvider.class.getDeclaredField("VALID_ACTIONS");
            f.setAccessible(true);
            validActions = (List<String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("VALID_ACTIONS field missing from AgentToolsProvider", e);
        }

        for (String action : validActions) {
            if ("help".equals(action)) continue; // self-reference - not documented
            assertThat(actions)
                    .as("action '%s' is in VALID_ACTIONS but has no help entry", action)
                    .containsKey(action);
            assertThat(actions.get(action))
                    .as("help entry for '%s' must not be blank", action)
                    .isNotBlank();
        }
    }

    /**
     * The concepts section is the mental model for the LLM. If any of these
     * sub-sections goes missing the help becomes dangerously incomplete:
     * an LLM that doesn't understand activation paths will assign tasks to
     * agents that never wake up and drain their inbox.
     */
    @Test
    @DisplayName("concepts section exposes all required mental-model keys")
    @SuppressWarnings("unchecked")
    void helpConceptsSectionIsComplete() {
        // Note: executeHelp() no longer touches listAvailableModels (catalog
        // moved to help_models). No stub here on purpose - Mockito strict mode
        // would flag any leftover stub as UnnecessaryStubbing.

        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), "tenant-x", null);

        assertThat(result).isPresent();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        Map<String, Object> concepts = (Map<String, Object>) data.get("concepts");
        assertThat(concepts).containsKeys(
                "what_an_agent_does",
                "activation_triggers",
                "task_delegation_model",
                "when_to_use_what",
                "worker_pattern",
                "budget_hierarchy");

        // activation_triggers must explain all four paths the LLM needs to know about
        Map<String, String> triggers = (Map<String, String>) concepts.get("activation_triggers");
        assertThat(triggers).containsKeys("user_chat", "webhook", "schedule_cron", "parent_execute");
    }

    /**
     * Regression guard - when reviewer_agent_id is null the task's terminal
     * state is 'in_review' awaiting the human user (tenant owner). This is
     * correct by-design, not a bug. The help must make that explicit on
     * every action involved (assign, task_complete, task_reject), on the
     * task_delegation_model concept, and on the task_lifecycle example -
     * otherwise callers interpret 'in_review' as stuck and file BUG-7-style
     * false positives (see testing/agent-subagent/FINDINGS.md).
     */
    @Test
    @DisplayName("Help makes the human-user-as-implicit-reviewer rule explicit across actions, concepts, and examples")
    @SuppressWarnings("unchecked")
    void helpDocumentsImplicitUserReviewer() {
        // executeHelp no longer reads listAvailableModels - stub removed (Mockito strict mode).
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), "tenant-x", null);
        assertThat(result).isPresent();
        Map<String, Object> data = (Map<String, Object>) result.get().data();

        // 1. assign action must warn that without reviewer_agent_id the user is
        //    the implicit reviewer so callers don't treat 'in_review' as stuck.
        Map<String, String> actions = (Map<String, String>) data.get("actions");
        assertThat(actions.get("assign"))
                .contains("HUMAN USER")
                .contains("in_review")
                .containsIgnoringCase("implicit");

        // 2. task_complete must explain the same on the assignee side.
        assertThat(actions.get("task_complete"))
                .contains("HUMAN USER")
                .contains("in_review");

        // 3. task_reject (assignee path) must mention the user fallback reviewer.
        assertThat(actions.get("task_reject"))
                .containsIgnoringCase("user");

        // 4. task_delegation_model concept must state the mandatory review gate
        //    and the two reviewer variants (agent vs human user).
        Map<String, Object> concepts = (Map<String, Object>) data.get("concepts");
        String delegation = (String) concepts.get("task_delegation_model");
        assertThat(delegation)
                .contains("in_review")
                .contains("HUMAN USER")
                .containsIgnoringCase("never skipped")
                .containsIgnoringCase("not mean the task is stuck");

        // 5. task_lifecycle example must show the user-reviewer branch.
        Map<String, Object> examples = (Map<String, Object>) data.get("examples");
        Map<String, Object> lifecycle = (Map<String, Object>) examples.get("task_lifecycle");
        List<String> steps = (List<String>) lifecycle.get("steps");
        assertThat(steps)
                .as("task_lifecycle must include a step describing user-as-implicit-reviewer")
                .anyMatch(s -> s.contains("human user") || s.contains("implicit reviewer"));

        // 6. The DELEGATION tip in the top-level tips list must flag that
        //    in_review without a reviewer agent is the expected resting state,
        //    not a stuck task. Tips are a distinct surface (LLMs that skim
        //    tips skip actions/concepts/examples) so we guard it separately.
        List<String> tips = (List<String>) data.get("tips");
        assertThat(tips)
                .as("tips must contain a DELEGATION lifecycle entry that explicitly marks 'in_review' as non-stuck")
                .anyMatch(t -> t.contains("task lifecycle")
                        && t.contains("not a stuck task"));
    }

    /**
     * Regression guard - the assign default is start_mode='execute' (sync,
     * BLOCKS until terminal). Two examples (worker_agent, task_lifecycle)
     * previously still described the pre-start_mode behavior ("assign is
     * async - it returns immediately", "returns {task_id, status:'in_progress'}"),
     * so an LLM copying the recipe verbatim got a blocking call instead of the
     * promised fire-and-forget, and a wrong status expectation (the non-blocking
     * modes return status='pending', never 'in_progress'). The examples must
     * pass start_mode explicitly for the async flow and never claim assign is
     * async by default.
     */
    @Test
    @DisplayName("Examples match the documented assign default: execute blocks, async requires explicit start_mode")
    @SuppressWarnings("unchecked")
    void examplesMatchAssignExecuteDefault() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), "tenant-x", null);
        assertThat(result).isPresent();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        Map<String, Object> examples = (Map<String, Object>) data.get("examples");

        // worker_agent: the fire-and-forget recipe must pass start_mode explicitly
        // and must not present async as the default.
        Map<String, Object> worker = (Map<String, Object>) examples.get("worker_agent");
        String workerText = worker.get("description") + " " + worker.get("usage");
        assertThat(workerText)
                .contains("start_mode='in_progress'")
                .containsIgnoringCase("BLOCKS")
                .doesNotContain("assign is async");

        // task_lifecycle: same rule, and the immediate-return status is 'pending',
        // never 'in_progress' (workers promote it on pickup).
        Map<String, Object> lifecycle = (Map<String, Object>) examples.get("task_lifecycle");
        String lifecycleDescription = (String) lifecycle.get("description");
        assertThat(lifecycleDescription)
                .contains("start_mode='execute'")
                .containsIgnoringCase("BLOCKS")
                .doesNotContain("assign is ASYNC");
        List<String> steps = (List<String>) lifecycle.get("steps");
        assertThat(steps.get(0))
                .contains("start_mode='in_progress'")
                .contains("status:'pending'")
                .doesNotContain("status:'in_progress'");
    }

    // ===================================================================
    // help_models - CLI-bridge admin-only gating (claude-code / codex /
    // gemini-cli / mistral-vibe). The LLM must never be shown a model the
    // dispatch path would later deny, so the listing mirrors the same
    // BridgeAccessGuard decision the runtime enforces.
    // ===================================================================

    @Test
    @DisplayName("Non-admin caller: bridge models are removed, direct-API models stay")
    void nonAdminCallerSeesNoBridgeModels() {
        module.setBridgeAccessGuard(bridgeAccessGuard);
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("openai", "gpt-5.4", "high", 1),
                new AvailableModel("claude-code", "claude-opus-4-7", "high", 2),
                new AvailableModel("anthropic", "claude-opus-4-6", "high", 3)
        ));
        when(bridgeAccessGuard.check(eq("user-1"), eq("USER"), anyString()))
                .thenReturn(BridgeAccessDecision.deny("claude-code", BridgeAccessDecision.REASON_NOT_ADMIN));

        List<String> pairs = pairsOf(module.execute("help_models", Map.of(), "user-1",
                contextWithRoles("user-1", "USER")));

        assertThat(pairs).anyMatch(p -> p.startsWith("openai/gpt-5.4"));
        assertThat(pairs).anyMatch(p -> p.startsWith("anthropic/claude-opus-4-6"));
        assertThat(pairs).noneMatch(p -> p.startsWith("claude-code/"));
    }

    @Test
    @DisplayName("Admin caller: bridge models remain visible")
    void adminCallerSeesBridgeModels() {
        module.setBridgeAccessGuard(bridgeAccessGuard);
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("openai", "gpt-5.4", "high", 1),
                new AvailableModel("claude-code", "claude-opus-4-7", "high", 2)
        ));
        when(bridgeAccessGuard.check(eq("admin-1"), eq("ADMIN,USER"), anyString()))
                .thenReturn(BridgeAccessDecision.allow("claude-code", 100));

        List<String> pairs = pairsOf(module.execute("help_models", Map.of(), "admin-1",
                contextWithRoles("admin-1", "ADMIN,USER")));

        assertThat(pairs).anyMatch(p -> p.startsWith("claude-code/claude-opus-4-7"));
        assertThat(pairs).anyMatch(p -> p.startsWith("openai/gpt-5.4"));
    }

    @Test
    @DisplayName("Catalog without bridges never consults the access guard")
    void nonBridgeCatalogNeverConsultsGuard() {
        module.setBridgeAccessGuard(bridgeAccessGuard);
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("openai", "gpt-5.4", "high", 1),
                new AvailableModel("anthropic", "claude-opus-4-6", "high", 2)
        ));

        List<String> pairs = pairsOf(module.execute("help_models", Map.of(), "user-1",
                contextWithRoles("user-1", "USER")));

        assertThat(pairs).hasSize(2);
        verifyNoInteractions(bridgeAccessGuard);
    }

    @Test
    @DisplayName("The per-provider decision is cached: two claude-code models = one guard round-trip")
    void decisionIsCachedPerProvider() {
        module.setBridgeAccessGuard(bridgeAccessGuard);
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("claude-code", "claude-opus-4-7", "high", 1),
                new AvailableModel("claude-code", "claude-sonnet-4-6", "mid", 2)
        ));
        when(bridgeAccessGuard.check(eq("admin-1"), eq("ADMIN"), eq("claude-code")))
                .thenReturn(BridgeAccessDecision.allow("claude-code", 100));

        module.execute("help_models", Map.of(), "admin-1", contextWithRoles("admin-1", "ADMIN"));

        verify(bridgeAccessGuard, times(1)).check(eq("admin-1"), eq("ADMIN"), eq("claude-code"));
    }

    @Test
    @DisplayName("Guard bean absent: falls back to admin-role check (non-admin hidden, admin shown)")
    void fallsBackToAdminRoleWhenGuardAbsent() {
        // module is constructed in setUp() WITHOUT a guard (bean unwired) - exercise the fallback.
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("openai", "gpt-5.4", "high", 1),
                new AvailableModel("codex", "gpt-5.4-codex", "high", 2)
        ));

        List<String> nonAdmin = pairsOf(module.execute("help_models", Map.of(), "user-1",
                contextWithRoles("user-1", "USER")));
        assertThat(nonAdmin).noneMatch(p -> p.startsWith("codex/"));

        List<String> admin = pairsOf(module.execute("help_models", Map.of(), "admin-1",
                contextWithRoles("admin-1", "ADMIN")));
        assertThat(admin).anyMatch(p -> p.startsWith("codex/gpt-5.4-codex"));
    }

    @Test
    @DisplayName("Unknown roles (null credentials) hide bridges - safe default")
    void unknownRolesHideBridges() {
        module.setBridgeAccessGuard(bridgeAccessGuard);
        when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("openai", "gpt-5.4", "high", 1),
                new AvailableModel("gemini-cli", "gemini-3.1-pro-preview", "high", 2)
        ));
        // No roles in context → guard is invoked with null roles and denies (admin_only).
        when(bridgeAccessGuard.check(any(), any(), eq("gemini-cli")))
                .thenReturn(BridgeAccessDecision.deny("gemini-cli", BridgeAccessDecision.REASON_NOT_ADMIN));

        List<String> pairs = pairsOf(module.execute("help_models", Map.of(), "user-1",
                contextWithRoles("user-1", null)));

        assertThat(pairs).noneMatch(p -> p.startsWith("gemini-cli/"));
        assertThat(pairs).anyMatch(p -> p.startsWith("openai/gpt-5.4"));
    }
}
