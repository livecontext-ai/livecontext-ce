package com.apimarketplace.agent.tools.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthorizationSubject - what the authorization card is allowed to name")
class AuthorizationSubjectTest {

    @Nested
    @DisplayName("workflow:pin")
    class Pin {

        @Test
        @DisplayName("Carries the workflow id and the version being promoted")
        void carriesIdAndVersion() {
            Map<String, Object> subject = AuthorizationSubject.of("workflow:pin",
                    Map.of("action", "pin", "workflow_id", "w-1", "version", 12));

            assertThat(subject).containsEntry("kind", "workflow")
                    .containsEntry("id", "w-1")
                    .containsEntry("version", 12);
        }

        @Test
        @DisplayName("Accepts the 'id' spelling, which the tool itself falls back to")
        void acceptsTheIdSpelling() {
            // WorkflowCrudModule.executePin reads workflow_id and then id. A card that only
            // knew the first spelling would silently lose its name on half the calls.
            assertThat(AuthorizationSubject.of("workflow:pin", Map.of("action", "pin", "id", "w-2")))
                    .containsEntry("id", "w-2");
        }

        @Test
        @DisplayName("A version written as a plain integer string names that version")
        void integerStringNamesTheVersion() {
            Map<String, Object> args = new HashMap<>();
            args.put("workflow_id", "w-1");
            args.put("version", "12");

            assertThat(AuthorizationSubject.of("workflow:pin", args)).containsEntry("version", 12);
        }

        @ParameterizedTest(name = "version [{0}] names nothing, because the pin would refuse it")
        // 0 and -1 parse perfectly well and are refused by executePin's own bound, which is
        // the half a "same parser" claim does not cover on its own.
        @ValueSource(strings = {"12.0", " 12 ", "latest", "v12", "", "0", "-1"})
        @DisplayName("A version the pin itself would refuse is never named on the card")
        void versionsThePinWouldRefuseAreNotNamed(String written) {
            // The card resolves the version through the executor's own parser, so these are
            // not "unparseable" in the abstract: they are exactly the values that make
            // executePin fail with "version is required". A card naming "version 12" for
            // " 12 " would describe a promotion that never happens, and the user would have
            // authorized it.
            Map<String, Object> args = new HashMap<>();
            args.put("workflow_id", "w-1");
            args.put("version", written);

            assertThat(AuthorizationSubject.of("workflow:pin", args))
                    .containsEntry("id", "w-1")
                    .doesNotContainKey("version");
        }

        @Test
        @DisplayName("No id means no subject, so the card falls back instead of promising a name")
        void noIdYieldsNoSubject() {
            assertThat(AuthorizationSubject.of("workflow:pin", Map.of("action", "pin"))).isNull();
        }
    }

    @Nested
    @DisplayName("workflow:unpin")
    class Unpin {

        @Test
        @DisplayName("Carries the id but never a version - unpin promotes nothing")
        void carriesIdWithoutVersion() {
            // A version on an unpin card would read as "version 12 is being taken down", which
            // is not what unpin does: it takes production down entirely.
            Map<String, Object> subject = AuthorizationSubject.of("workflow:unpin",
                    Map.of("action", "unpin", "workflow_id", "w-1", "version", 12));

            assertThat(subject).containsEntry("kind", "workflow")
                    .containsEntry("id", "w-1")
                    .doesNotContainKey("version");
        }
    }

    @Nested
    @DisplayName("agent:schedule")
    class AgentSchedule {

        @Test
        @DisplayName("Carries the cron, its zone and the name the create call supplied")
        void carriesCronZoneAndName() {
            Map<String, Object> subject = AuthorizationSubject.of("agent:schedule",
                    Map.of("action", "create", "name", "Inbox Watcher",
                            "schedule_cron", "0 9 * * *", "schedule_timezone", "Europe/Paris"));

            assertThat(subject).containsEntry("kind", "agent")
                    .containsEntry("cron", "0 9 * * *")
                    .containsEntry("timezone", "Europe/Paris")
                    .containsEntry("name", "Inbox Watcher");
        }

        @Test
        @DisplayName("Defaults the zone to UTC, which is what the schedule will actually fire in")
        void defaultsTheZoneToUtc() {
            // AgentCrudModule stores "UTC" when the call omits it. A card that left the zone
            // blank would let the user read the cron in their own zone and be wrong by hours.
            assertThat(AuthorizationSubject.of("agent:schedule",
                    Map.of("action", "create", "schedule_cron", "0 9 * * *")))
                    .containsEntry("timezone", "UTC");
        }

        @Test
        @DisplayName("On an update it carries the agent id instead of a name")
        void updateCarriesTheAgentId() {
            Map<String, Object> subject = AuthorizationSubject.of("agent:schedule",
                    Map.of("action", "update", "agent_id", "a-1", "schedule_cron", "*/10 * * * *"));

            assertThat(subject).containsEntry("id", "a-1")
                    .containsEntry("cron", "*/10 * * * *")
                    .doesNotContainKey("name");
        }

        @Test
        @DisplayName("Reads a cron nested under params, which is where the tool reads it")
        void readsTheNestedShape() {
            // Without this the gate fires on the nested shape (it merges too) and the card it
            // raises names nothing: "It will start itself on  (UTC)".
            Map<String, Object> subject = AuthorizationSubject.of("agent:schedule",
                    Map.of("action", "create",
                            "params", Map.of("name", "Daily Reporter",
                                    "schedule_cron", "0 9 * * *",
                                    "schedule_timezone", "Europe/Paris")));

            assertThat(subject).containsEntry("cron", "0 9 * * *")
                    .containsEntry("timezone", "Europe/Paris")
                    .containsEntry("name", "Daily Reporter");
        }

        @Test
        @DisplayName("No cron means no subject - the cron IS what the card is about")
        void noCronYieldsNoSubject() {
            assertThat(AuthorizationSubject.of("agent:schedule", Map.of("action", "create", "name", "X")))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Run rules - the card names what is about to run (was the bare 'Run this action?')")
    class RunRules {

        @Test
        @DisplayName("workflow:execute names the workflow, under either id spelling")
        void workflowExecuteNamesTheWorkflow() {
            assertThat(AuthorizationSubject.of("workflow:execute", Map.of("action", "execute", "id", "w-1")))
                    .isEqualTo(Map.of("kind", "workflow", "id", "w-1"));
            assertThat(AuthorizationSubject.of("workflow:execute", Map.of("workflow_id", "w-2")))
                    .containsEntry("id", "w-2")
                    .doesNotContainKey("version");
        }

        @Test
        @DisplayName("workflow:execute with both spellings names `id`, the one execute actually runs")
        void workflowExecutePrefersIdLikeTheExecutor() {
            // WorkflowBuilderProvider.resolveWorkflowId reads id before workflow_id (pin is the
            // reverse). Naming workflow_id here would show "Run B?" and then run A.
            assertThat(AuthorizationSubject.of("workflow:execute",
                    Map.of("id", "runs-this", "workflow_id", "not-this")))
                    .containsEntry("id", "runs-this");
        }

        @Test
        @DisplayName("workflow:execute on the loaded workflow (no id) names nothing")
        void workflowExecuteWithoutIdNamesNothing() {
            assertThat(AuthorizationSubject.of("workflow:execute", Map.of("action", "execute"))).isNull();
        }

        @Test
        @DisplayName("restart_from_node names the node and the run, accepting the node_id spelling")
        void restartNamesTheNode() {
            assertThat(AuthorizationSubject.of("workflow:restart_from_node",
                    Map.of("run_id", "r-1", "node", "mcp:fetch_data")))
                    .containsEntry("kind", "run_node")
                    .containsEntry("node", "mcp:fetch_data")
                    .containsEntry("run_id", "r-1");
            assertThat(AuthorizationSubject.of("workflow:restart_from_node", Map.of("node_id", "core:x")))
                    .containsEntry("node", "core:x")
                    .doesNotContainKey("run_id");
            assertThat(AuthorizationSubject.of("workflow:restart_from_node", Map.of("run_id", "r-1"))).isNull();
        }

        @Test
        @DisplayName("run_node names the node type")
        void runNodeNamesTheType() {
            assertThat(AuthorizationSubject.of("workflow:run_node", Map.of("type", "send_email")))
                    .isEqualTo(Map.of("kind", "node", "type", "send_email"));
        }

        @Test
        @DisplayName("application:execute, agent:execute and both catalog spellings carry the target id")
        void idOnlyRulesCarryTheirId() {
            assertThat(AuthorizationSubject.of("application:execute", Map.of("application_id", "p-1")))
                    .isEqualTo(Map.of("kind", "application", "id", "p-1"));
            assertThat(AuthorizationSubject.of("agent:execute", Map.of("agent_id", "a-1", "prompt", "go")))
                    .isEqualTo(Map.of("kind", "agent", "id", "a-1"));
            assertThat(AuthorizationSubject.of("catalog:execute", Map.of("tool_id", "t-1")))
                    .isEqualTo(Map.of("kind", "api_tool", "id", "t-1"));
            assertThat(AuthorizationSubject.of("catalog:call", Map.of("tool_id", "t-1")))
                    .containsEntry("id", "t-1");
        }

        @Test
        @DisplayName("mailbox:send names the recipient and the subject line")
        void mailNamesTheRecipient() {
            assertThat(AuthorizationSubject.of("mailbox:send", Map.of("to", " bob@x.io ", "subject", "Hi")))
                    .containsEntry("kind", "mail")
                    .containsEntry("to", "bob@x.io")
                    .containsEntry("subject", "Hi");
            assertThat(AuthorizationSubject.of("mailbox:send", Map.of("subject", "Hi"))).isNull();
        }

        @Test
        @DisplayName("agent:disarm_tool_authorization names the agent, in the flat and the nested shape")
        void disarmNamesTheAgent() {
            assertThat(AuthorizationSubject.of("agent:disarm_tool_authorization",
                    Map.of("agent_id", "a-1", "require_tool_authorization", false)))
                    .isEqualTo(Map.of("kind", "agent", "id", "a-1"));
            assertThat(AuthorizationSubject.of("agent:disarm_tool_authorization",
                    Map.of("params", Map.of("agent_id", "a-2", "require_tool_authorization", false))))
                    .containsEntry("id", "a-2");
        }

        @Test
        @DisplayName("mailbox:send carries cc and bcc, which receive the mail too")
        void mailCarriesCopies() {
            assertThat(AuthorizationSubject.of("mailbox:send",
                    Map.of("to", "bob@x.io", "cc", "eve@x.io", "bcc", "joe@x.io")))
                    .containsEntry("cc", "eve@x.io")
                    .containsEntry("bcc", "joe@x.io");
            assertThat(AuthorizationSubject.of("mailbox:send", Map.of("to", "bob@x.io")))
                    .doesNotContainKeys("cc", "bcc");
        }

        @Test
        @DisplayName("Run rules read top-level only, like their executors: a nested target is not named")
        void runRulesIgnoreNestedParams() {
            assertThat(AuthorizationSubject.of("agent:execute",
                    Map.of("params", Map.of("agent_id", "a-1")))).isNull();
            assertThat(AuthorizationSubject.of("mailbox:send",
                    Map.of("params", Map.of("to", "bob@x.io")))).isNull();
        }
    }

    @Nested
    @DisplayName("Everything else")
    class Fallbacks {

        @Test
        @DisplayName("A rule that names nothing yields no subject, leaving the old copy in place")
        void rulesWithNothingToNameYieldNoSubject() {
            // acquire previews its publication through applicationId, not the subject; a call
            // missing the argument a rule names by yields nothing rather than an empty subject.
            assertThat(AuthorizationSubject.of("application:acquire", Map.of("application_id", "p-1"))).isNull();
            assertThat(AuthorizationSubject.of("catalog:execute", Map.of("tool", "t"))).isNull();
            assertThat(AuthorizationSubject.of("mailbox:delete", Map.of("uid", "9"))).isNull();
        }

        @Test
        @DisplayName("A workflow subject is NOT merged, because the workflow tool does not merge")
        void workflowSubjectIgnoresNestedParams() {
            // WorkflowCrudModule.executePin reads its parameters directly, so a nested id never
            // reaches the pin - it fails with "workflow_id is required". Naming a workflow the
            // pin would not touch would be worse than naming none.
            assertThat(AuthorizationSubject.of("workflow:pin",
                    Map.of("action", "pin", "params", Map.of("workflow_id", "w-1", "version", 12))))
                    .isNull();
        }

        @Test
        @DisplayName("Matching is case-insensitive on the rule")
        void ruleMatchingIsCaseInsensitive() {
            assertThat(AuthorizationSubject.of("WORKFLOW:PIN", Map.of("workflow_id", "w-1")))
                    .containsEntry("kind", "workflow");
        }

        @Test
        @DisplayName("Null rule or null arguments yield no subject rather than throwing")
        void nullsAreSafe() {
            // This runs on the path that builds a gate result. Throwing here would turn a
            // cosmetic field into a failed tool call.
            assertThat(AuthorizationSubject.of(null, Map.of("workflow_id", "w-1"))).isNull();
            assertThat(AuthorizationSubject.of("workflow:pin", null)).isNull();
        }

        @Test
        @DisplayName("Blank argument values are treated as absent, never as an empty name")
        void blankValuesAreTreatedAsAbsent() {
            Map<String, Object> args = new HashMap<>();
            args.put("workflow_id", "   ");
            args.put("id", "w-3");

            // The blank first spelling must not win, or the card fetches a name for "   ".
            assertThat(AuthorizationSubject.of("workflow:pin", args)).containsEntry("id", "w-3");
        }
    }
}
