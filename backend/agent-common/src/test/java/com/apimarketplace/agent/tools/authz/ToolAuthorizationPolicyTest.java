package com.apimarketplace.agent.tools.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolAuthorizationPolicy - the hand-curated sensitive-action list")
class ToolAuthorizationPolicyTest {

    @ParameterizedTest(name = "requires({0}, {1}) == true")
    @CsvSource({
            "application,acquire",
            "application,execute",
            "agent,execute",
            "catalog,execute",
            "catalog,call",
            "workflow,execute",
            "workflow,continue_interface",   // advancing a paused interface mutates run state
            "workflow,resolve_approval",     // resolving a user approval mutates run state
            // run_node executes a node immediately with the user's credentials and real side
            // effects (mail sent, SQL run), from a config the agent wrote in the call itself.
            "workflow,run_node",
            // pin hands a version every trigger its plan declares; unpin takes them all off
            // the air. Both outlive the conversation, which is why unpin is gated even though
            // it ENDS something (stop_run, just below, deliberately is not).
            "workflow,pin",
            "workflow,unpin",
    })
    @DisplayName("Listed (tool, action) pairs require authorization")
    void listedPairsRequireAuthorization(String tool, String action) {
        assertThat(ToolAuthorizationPolicy.requires(tool, action)).isTrue();
    }

    @ParameterizedTest(name = "requires({0}, {1}) == false")
    @CsvSource({
            "application,search",   // benign application action
            "agent,list",           // benign agent action
            "agent,get",
            "catalog,search",       // benign catalog action
            "files,list",           // files has no sensitive actions
            "web_search,search",
            "workflow,get_run",     // inspecting a run is read-only
            "workflow,modify",      // editing a draft does not advance a run
            "workflow,validate",
            // Deliberate: every gated action STARTS work or lets it continue; stop_run only
            // ends work already running, and the user's own Stop button is ungated. Making
            // an agent wait for an approval card before it can stop a runaway execution
            // would defeat the action. Pinned so the decision is explicit, not an oversight.
            "workflow,stop_run",
            "application,stop_run",
    })
    @DisplayName("Benign actions and non-sensitive tools do not require authorization")
    void benignPairsDoNotRequireAuthorization(String tool, String action) {
        assertThat(ToolAuthorizationPolicy.requires(tool, action)).isFalse();
    }

    @Test
    @DisplayName("Matching is case-insensitive on both tool and action")
    void matchingIsCaseInsensitive() {
        assertThat(ToolAuthorizationPolicy.requires("APPLICATION", "ACQUIRE")).isTrue();
        assertThat(ToolAuthorizationPolicy.requires("Catalog", "Call")).isTrue();
    }

    @Test
    @DisplayName("Null tool or action never requires authorization")
    void nullsAreSafe() {
        assertThat(ToolAuthorizationPolicy.requires(null, "acquire")).isFalse();
        assertThat(ToolAuthorizationPolicy.requires("application", null)).isFalse();
    }

    @Test
    @DisplayName("isSensitiveTool reflects whether a tool exposes any gated action")
    void isSensitiveTool() {
        assertThat(ToolAuthorizationPolicy.isSensitiveTool("application")).isTrue();
        assertThat(ToolAuthorizationPolicy.isSensitiveTool("agent")).isTrue();
        assertThat(ToolAuthorizationPolicy.isSensitiveTool("files")).isFalse();
        assertThat(ToolAuthorizationPolicy.isSensitiveTool(null)).isFalse();
    }

    @Test
    @DisplayName("ruleKey returns canonical lowercase tool:action, or null when not gated")
    void ruleKeyIsCanonical() {
        assertThat(ToolAuthorizationPolicy.ruleKey("Application", "Acquire")).isEqualTo("application:acquire");
        assertThat(ToolAuthorizationPolicy.ruleKey("catalog", "call")).isEqualTo("catalog:call");
        assertThat(ToolAuthorizationPolicy.ruleKey("agent", "list")).isNull();
    }

    @Test
    @DisplayName("Guard gates workflow continue_interface / resolve_approval from the action arg, not get_run")
    void guardGatesNewWorkflowSignalActions() {
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                java.util.Map.of("action", "resolve_approval", "run_id", "r")))
                .isEqualTo("workflow:resolve_approval");
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                java.util.Map.of("action", "continue_interface", "run_id", "r")))
                .isEqualTo("workflow:continue_interface");
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                java.util.Map.of("action", "get_run", "run_id", "r")))
                .isNull();
    }

    @Test
    @DisplayName("Both catalog execute spellings can raise a Connect card, and nothing else can")
    void connectCardCapableCallsAreTheCatalogExecutePair() {
        // This predicate is what keeps the connect hold alive for a user who already granted
        // the rule, so each spelling has to be named. 'call' is an alias of 'execute' on the
        // very same pre-flight, and it is the one an assertion about "execute" silently
        // leaves out: drop it and those users go back to the two-turn flow with no test red.
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("catalog", "execute")).isTrue();
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("catalog", "call")).isTrue();
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("CATALOG", "Execute")).isTrue();

        // The card comes from the catalog credential pre-flight, so no other tool produces
        // one. Anything added here buys a longer ceiling on a hung backend for a card it can
        // never raise - and would have to be added to the park guard in the same pass.
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("catalog", "search")).isFalse();
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("workflow", "execute")).isFalse();
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("agent", "execute")).isFalse();
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("application", "acquire")).isFalse();
        // The fail-closed wildcard resolves no action; it must not throw either.
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard("catalog", null)).isFalse();
        assertThat(ToolAuthorizationPolicy.canRaiseConnectCard(null, "execute")).isFalse();
    }

    @Test
    @DisplayName("All listed actions are lowercase non-blank - keeps the list clean for matching")
    void listedActionsAreCleanLowercase() {
        for (Set<String> actions : ToolAuthorizationPolicy.SENSITIVE_ACTIONS.values()) {
            for (String action : actions) {
                assertThat(action).isNotBlank();
                assertThat(action).isEqualTo(action.toLowerCase());
            }
        }
        for (String tool : ToolAuthorizationPolicy.SENSITIVE_ACTIONS.keySet()) {
            assertThat(tool).isEqualTo(tool.toLowerCase());
        }
    }

    // ---- Argument-conditional rules: the same action is sensitive or not depending on
    // what the call carries. See ToolAuthorizationPolicy.CONDITIONAL_RULES.

    @ParameterizedTest(name = "agent:{0} carrying a cron raises agent:schedule")
    @CsvSource({"create", "update", "CREATE", "Update"})
    @DisplayName("A cron on create or update raises the one agent:schedule rule")
    void cronOnCreateOrUpdateRaisesTheScheduleRule(String action) {
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", action,
                Map.of("action", action, "schedule_cron", "0 9 * * *")))
                .isEqualTo(ToolAuthorizationPolicy.RULE_AGENT_SCHEDULE);
    }

    @Test
    @DisplayName("ONE rule key for both actions, so a standing grant given on create covers update")
    void createAndUpdateShareOneRuleKey() {
        // A per-action key would ask again the first time the agent CHANGES the cron it just
        // got permission to set, which reads as the platform forgetting the answer.
        String onCreate = ToolAuthorizationPolicy.conditionalRuleKey("agent", "create",
                Map.of("schedule_cron", "0 9 * * *"));
        String onUpdate = ToolAuthorizationPolicy.conditionalRuleKey("agent", "update",
                Map.of("schedule_cron", "*/10 * * * *"));
        assertThat(onCreate).isEqualTo(onUpdate).isEqualTo("agent:schedule");
    }

    @Test
    @DisplayName("An agent created with no cron is ordinary and raises nothing")
    void creatingAnUnscheduledAgentIsNotGated() {
        // The whole point of the conditional registry: gating create wholesale would put a
        // card in front of every agent anyone ever writes.
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", "create",
                Map.of("name", "Researcher", "system_prompt", "You research things")))
                .isNull();
    }

    @ParameterizedTest(name = "schedule_cron=[{0}] is a removal, not an arming")
    @ValueSource(strings = {"", "   ", "	"})
    @DisplayName("A blank cron REMOVES a schedule, so it is not gated")
    void blankCronDisarmsAndIsNotGated(String cron) {
        // Same posture as stop_run: what disarms is not held up on a click.
        Map<String, Object> args = new HashMap<>();
        args.put("agent_id", "a-1");
        args.put("schedule_cron", cron);
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", "update", args)).isNull();
    }

    @Test
    @DisplayName("A cron on an action with no conditional rule raises nothing")
    void cronOnAnUnrelatedActionRaisesNothing() {
        // get/list/delete never arm anything, whatever the call happens to carry.
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", "get",
                Map.of("schedule_cron", "0 9 * * *"))).isNull();
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("workflow", "create",
                Map.of("schedule_cron", "0 9 * * *"))).isNull();
    }

    @Test
    @DisplayName("conditionalRuleKey survives null tool, action and arguments")
    void conditionalRuleKeyNullsAreSafe() {
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey(null, "create", Map.of())).isNull();
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", null, Map.of())).isNull();
        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", "create", null)).isNull();
    }

    @Test
    @DisplayName("hasConditionalRules answers for the tool, not for a pair")
    void hasConditionalRulesIsPerTool() {
        // The guard reads this BEFORE it has an action, to decide whether the tool is worth
        // resolving one for at all.
        assertThat(ToolAuthorizationPolicy.hasConditionalRules("agent")).isTrue();
        assertThat(ToolAuthorizationPolicy.hasConditionalRules("AGENT")).isTrue();
        assertThat(ToolAuthorizationPolicy.hasConditionalRules("workflow")).isFalse();
        assertThat(ToolAuthorizationPolicy.hasConditionalRules(null)).isFalse();
    }

    @Test
    @DisplayName("A cron nested under params gates the call, because that is where the tool reads it")
    void cronNestedUnderParamsIsGated() {
        // THE bug this rule shipped with. AgentCrudModule opens create and update with
        // ToolParamUtils.mergeParams, which flattens a nested `params` object into the top
        // level - and the agent tool's help gives that nested form in EVERY scheduled-agent
        // example it publishes. A condition reading only the top level therefore answered "no
        // cron" for the shape models actually send: the schedule was armed, the call reported
        // success, and no card was ever raised. Gate installed, tests green, nothing asked.
        Map<String, Object> nested = Map.of(
                "action", "create",
                "params", Map.of("name", "Daily Reporter", "schedule_cron", "0 9 * * *"));

        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", "create", nested))
                .isEqualTo(ToolAuthorizationPolicy.RULE_AGENT_SCHEDULE);
    }

    @Test
    @DisplayName("A blank cron nested under params is still a removal, not an arming")
    void blankCronNestedUnderParamsIsNotGated() {
        Map<String, Object> nested = Map.of(
                "action", "update",
                "params", Map.of("agent_id", "a-1", "schedule_cron", "  "));

        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", "update", nested)).isNull();
    }

    @Test
    @DisplayName("A nested params object with no cron at all gates nothing")
    void nestedParamsWithoutCronIsNotGated() {
        // The merge must not turn every nested call into a gated one.
        Map<String, Object> nested = Map.of(
                "action", "create",
                "params", Map.of("name", "Researcher", "system_prompt", "You research things"));

        assertThat(ToolAuthorizationPolicy.conditionalRuleKey("agent", "create", nested)).isNull();
    }
}
