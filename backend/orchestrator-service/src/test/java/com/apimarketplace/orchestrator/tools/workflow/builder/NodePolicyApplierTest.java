package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent's side of the per-node execution policy.
 *
 * <p>Three failures shape these tests, and all three are silent ones.
 *
 * <ol>
 *   <li><b>A policy left in the call reaches the provider.</b> On an mcp node every key that is
 *       not lifted out IS an endpoint argument, so a {@code nodePolicy} the tool forgot to remove
 *       would be sent to the provider as a parameter it never declared. The node would be created,
 *       the run would be green, and the policy would do nothing.</li>
 *   <li><b>A policy stored on a node the engine ignores.</b> The engine collects policies from
 *       executable entries only, so one written on a trigger is accepted and dropped, which reads
 *       to the agent exactly like a policy that works.</li>
 *   <li><b>A policy the engine will refuse at parse time.</b> Accepted here, it makes the workflow
 *       unrunnable, and the error surfaces on a later, unrelated call.</li>
 * </ol>
 */
@DisplayName("NodePolicyApplier - the policy an agent sends with add_node / modify")
class NodePolicyApplierTest {

    private static Map<String, Object> mutable(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    @Nested
    @DisplayName("lifting the policy out of the call")
    class Extract {

        @Test
        @DisplayName("nothing sent means nothing found, and the call is untouched")
        void absentIsNull() {
            Map<String, Object> call = mutable(Map.of("label", "Publish"));

            assertThat(NodePolicyApplier.strip(call)).isNull();
            assertThat(call).containsOnlyKeys("label");
        }

        @Test
        @DisplayName("a policy at the root is returned AND removed, so it cannot reach the provider")
        void takenFromTheRoot() {
            Map<String, Object> call = mutable(Map.of(
                    "label", "Publish", "nodePolicy", Map.of("retryCount", 2)));

            Object raw = NodePolicyApplier.strip(call);

            assertThat(raw).isEqualTo(Map.of("retryCount", 2));
            assertThat(call)
                    .as("on an mcp node the remaining keys ARE the endpoint's arguments")
                    .containsOnlyKeys("label");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> container(Map<String, Object> call, String key) {
            return (Map<String, Object>) call.get(key);
        }

        @Test
        @DisplayName("a policy nested inside params is found there too")
        void takenFromTheParamsContainer() {
            // Every creator accepts a field in either place, so a caller that nested it one level
            // deeper than we looked would have it dropped without a word.
            Map<String, Object> call = mutable(Map.of("label", "Publish"));
            call.put("params", mutable(Map.of("caption", "hi", "nodePolicy", Map.of("retryCount", 1))));

            Object raw = NodePolicyApplier.strip(call);

            assertThat(raw).isEqualTo(Map.of("retryCount", 1));
            assertThat(container(call, "params")).containsOnlyKeys("caption");
        }

        @Test
        @DisplayName("the 'parameters' spelling of that container is searched as well")
        void takenFromTheParametersContainer() {
            Map<String, Object> call = mutable(Map.of("label", "Publish"));
            call.put("parameters", mutable(Map.of("node_policy", Map.of("retryCount", 4))));

            assertThat(NodePolicyApplier.strip(call)).isEqualTo(Map.of("retryCount", 4));
            assertThat(container(call, "parameters")).isEmpty();
        }

        @Test
        @DisplayName("sent in BOTH places, neither copy is left to leak")
        void bothCopiesAreRemoved() {
            Map<String, Object> call = mutable(Map.of("nodePolicy", Map.of("retryCount", 2)));
            call.put("params", mutable(Map.of("nodePolicy", Map.of("retryCount", 9))));

            Object raw = NodePolicyApplier.strip(call);

            assertThat(raw).as("the root is the canonical place, so it wins").isEqualTo(Map.of("retryCount", 2));
            assertThat(call).containsOnlyKeys("params");
            assertThat(container(call, "params"))
                    .as("the losing copy must not stay behind as a tool argument")
                    .isEmpty();
        }

        @Test
        @DisplayName("the caller's own container is never mutated, only replaced inside our map")
        void theCallersContainerIsNotMutated() {
            // The tool arguments belong to the caller. Mutating them is a side effect nobody asked
            // for, and it THREW on the callers that pass an immutable map, which is most tests and
            // any future caller building its arguments with Map.of.
            Map<String, Object> callersParams = mutable(Map.of("nodePolicy", Map.of("retryCount", 2)));
            Map<String, Object> ourCopy = mutable(Map.of("params", callersParams));

            NodePolicyApplier.strip(ourCopy);

            assertThat(callersParams).containsKey("nodePolicy");
            assertThat(container(ourCopy, "params")).isEmpty();
        }

        @Test
        @DisplayName("an IMMUTABLE nested container is handled instead of throwing")
        void anImmutableContainerIsHandled() {
            Map<String, Object> call = mutable(Map.of(
                    "params", Map.of("nodePolicy", Map.of("retryCount", 2), "caption", "hi")));

            Object raw = NodePolicyApplier.strip(call);

            assertThat(raw).isEqualTo(Map.of("retryCount", 2));
            assertThat(container(call, "params")).containsOnlyKeys("caption");
        }

        @Test
        @DisplayName("a container carrying no policy is left exactly as it was, same instance")
        void aContainerWithoutAPolicyIsUntouched() {
            // No copy, no reallocation, no reordering: a call that says nothing about a policy must
            // reach the creator byte-identical to before this code existed.
            Map<String, Object> callersParams = Map.of("caption", "hi");
            Map<String, Object> call = mutable(Map.of("params", callersParams));

            assertThat(NodePolicyApplier.strip(call)).isNull();
            assertThat(call.get("params")).isSameAs(callersParams);
        }

        @Test
        @DisplayName("peekRoot reads the root without changing it, for a map we do not own")
        void peekRootReadsWithoutChanging() {
            Map<String, Object> callersArgs = Map.of("nodePolicy", Map.of("retryCount", 2));

            assertThat(NodePolicyApplier.peekRoot(callersArgs)).isEqualTo(Map.of("retryCount", 2));
            assertThat(callersArgs).containsKey("nodePolicy");
            assertThat(NodePolicyApplier.peekRoot(Map.of("label", "x"))).isNull();
            assertThat(NodePolicyApplier.peekRoot(null)).isNull();
        }

        @Test
        @DisplayName("the snake_case and 'execution' spellings a model reaches for are accepted")
        void acceptsTheSpellingsAModelUses() {
            for (String key : NodePolicyApplier.PARAM_KEYS) {
                Map<String, Object> call = mutable(Map.of(key, Map.of("retryCount", 3)));

                assertThat(NodePolicyApplier.strip(call))
                        .as("spelling '%s'", key)
                        .isEqualTo(Map.of("retryCount", 3));
                assertThat(call).isEmpty();
            }
        }

        @Test
        @DisplayName("the bare word 'policy' is NOT claimed, because a provider may have a "
                + "parameter called that")
        void doesNotClaimTheBareWordPolicy() {
            Map<String, Object> call = mutable(Map.of("policy", "strict"));

            assertThat(NodePolicyApplier.strip(call)).isNull();
            assertThat(call)
                    .as("swallowing an endpoint's own argument would break the call it belongs to")
                    .containsEntry("policy", "strict");
        }
    }

    @Nested
    @DisplayName("checking the shape")
    class Validate {

        @Test
        @DisplayName("nothing sent is not an error")
        void absentIsFine() {
            assertThat(NodePolicyApplier.validate(null)).isNull();
        }

        @Test
        @DisplayName("a well-formed policy passes")
        void wellFormedPasses() {
            assertThat(NodePolicyApplier.validate(Map.of(
                    "retryCount", 2, "retryBackoffMs", 1000, "providerRetryMaxWaitSec", 0)))
                    .isNull();
        }

        @Test
        @DisplayName("a negative value is refused, in the engine's own words")
        void negativeIsRefused() {
            // Refused by NodePolicy.fromMap, deliberately: a second, laxer check here is how a
            // tool comes to accept a shape the runtime later rejects.
            assertThat(NodePolicyApplier.validate(Map.of("retryCount", -1)))
                    .contains("retryCount");
        }

        @Test
        @DisplayName("a scalar where an object belongs is refused")
        void scalarIsRefused() {
            assertThat(NodePolicyApplier.validate("retryCount=2"))
                    .contains("expected an object");
        }

        @Test
        @DisplayName("a non-boolean flag is refused rather than coerced")
        void nonBooleanFlagIsRefused() {
            assertThat(NodePolicyApplier.validate(Map.of("continueOnFailure", 1)))
                    .contains("continueOnFailure");
        }
    }

    @Nested
    @DisplayName("checking it against the node")
    class RejectionForNode {

        private Map<String, Object> core(String type) {
            return mutable(Map.of("id", "core:check", "type", type, "label", "Check"));
        }

        @Test
        @DisplayName("a trigger is refused: the engine collects no policy for one, so storing it "
                + "would look like it worked")
        void refusedOnATrigger() {
            assertThat(NodePolicyApplier.rejectionForNode(
                    "trigger:my_webhook", mutable(Map.of("type", "webhook")), Map.of("retryCount", 2)))
                    .contains("not available on trigger or note nodes");
        }

        @Test
        @DisplayName("a note is refused for the same reason")
        void refusedOnANote() {
            assertThat(NodePolicyApplier.rejectionForNode(
                    "note:reminder", mutable(Map.of("type", "note")), Map.of("retryCount", 2)))
                    .contains("not available on trigger or note nodes");
        }

        @Test
        @DisplayName("continueOnFailure on a decision is refused HERE, in the engine's own wording")
        void continueOnFailureOnADecision() {
            // The engine refuses this at plan-parse time. Without this check the agent gets a node
            // it can add and can never run, and the error arrives on some later call.
            String rejection = NodePolicyApplier.rejectionForNode(
                    "core:check", core("decision"), Map.of("continueOnFailure", true));

            assertThat(rejection)
                    .contains("continueOnFailure")
                    .contains("ALL its ports");
        }

        @Test
        @DisplayName("executeOnce on a loop is refused, with the reason that names the confusion")
        void executeOnceOnALoop() {
            String rejection = NodePolicyApplier.rejectionForNode(
                    "core:check", core("loop"), Map.of("executeOnce", true));

            assertThat(rejection)
                    .contains("executeOnce")
                    .contains("never loop iterations");
        }

        @Test
        @DisplayName("a retry on that same decision is allowed, so the refusal is narrow")
        void retryOnADecisionIsAllowed() {
            assertThat(NodePolicyApplier.rejectionForNode(
                    "core:check", core("decision"), Map.of("retryCount", 2)))
                    .isNull();
        }

        @Test
        @DisplayName("the provider-retry budget is refused on every node type that makes no "
                + "catalog tool call")
        void providerBudgetIsRefusedOffAToolStep() {
            // StepNode is the only node that carries this budget to the catalog. Stored anywhere
            // else it is read by nothing: the call succeeds, the plan shows the field, the platform
            // goes on retrying underneath an author who asked it not to, and nothing says so. An
            // AI node and a core http_request node are the two that look most like exceptions -
            // both do call a provider, neither goes through StepNode.
            for (String nodeId : List.of("agent:analyst", "core:fetch_page", "table:save_row",
                    "interface:review")) {
                assertThat(NodePolicyApplier.rejectionForNode(
                        nodeId, mutable(Map.of("type", "x")), Map.of("providerRetryMaxWaitSec", 0)))
                        .as("node '%s'", nodeId)
                        .contains("catalog tool step only");
            }
        }

        @Test
        @DisplayName("the other policy fields stay allowed on those same nodes")
        void theOtherFieldsStayAllowedOffAToolStep() {
            assertThat(NodePolicyApplier.rejectionForNode(
                    "agent:analyst", mutable(Map.of("type", "agent")),
                    Map.of("retryCount", 2, "retryBackoffMs", 1000, "timeoutMs", 30000)))
                    .isNull();
        }

        @Test
        @DisplayName("an ordinary mcp node takes every field")
        void ordinaryNodeTakesEverything() {
            assertThat(NodePolicyApplier.rejectionForNode(
                    "mcp:publish", mutable(Map.of("type", "mcp")),
                    Map.of("retryCount", 2, "continueOnFailure", true, "executeOnce", true,
                            "timeoutMs", 30000, "providerRetryMaxWaitSec", 0)))
                    .isNull();
        }

        @Test
        @DisplayName("nothing sent is never a rejection")
        void absentIsNeverRejected() {
            assertThat(NodePolicyApplier.rejectionForNode("mcp:publish", mutable(Map.of()), null)).isNull();
        }
    }

    @Nested
    @DisplayName("reporting it back to the caller")
    class DescribeInResult {

        private final WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("s").tenantId("t").build();

        @org.junit.jupiter.api.BeforeEach
        void addNode() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish", "label", "Publish"));
            node.put(NodePolicy.JSON_KEY, Map.of("retryCount", 2));
            session.getMcps().add(node);
        }

        @Test
        @DisplayName("a successful result gains the stored policy and a hint")
        @SuppressWarnings("unchecked")
        void addsThePolicyToASuccess() {
            var enriched = NodePolicyApplier.describeInResult(
                    ToolsProvider.ToolExecutionResult.success(Map.of("status", "OK")),
                    session, "mcp:publish");

            Map<String, Object> data = (Map<String, Object>) enriched.data();
            assertThat(data).containsEntry("status", "OK");
            assertThat(data.get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 2));
            assertThat(data).containsKey("node_policy_hint");
        }

        @Test
        @DisplayName("a FAILED result is handed back untouched, so a refusal keeps its message")
        void leavesAFailureAlone() {
            var failure = ToolsProvider.ToolExecutionResult.failure(
                    com.apimarketplace.agent.tools.ToolErrorCode.EXECUTION_FAILED, "nope");

            assertThat(NodePolicyApplier.describeInResult(failure, session, "mcp:publish"))
                    .isSameAs(failure);
        }

        @Test
        @DisplayName("a result whose payload is not a map is handed back untouched")
        void leavesANonMapPayloadAlone() {
            // Not every action answers with a map, and rebuilding one would replace the payload the
            // caller was given with something else entirely.
            var listResult = ToolsProvider.ToolExecutionResult.success(java.util.List.of("a", "b"));

            assertThat(NodePolicyApplier.describeInResult(listResult, session, "mcp:publish"))
                    .isSameAs(listResult);
        }

        @Test
        @DisplayName("a node with no policy adds nothing, so an ordinary reply does not grow a field")
        void addsNothingWithoutAPolicy() {
            session.getMcps().get(0).remove(NodePolicy.JSON_KEY);
            var success = ToolsProvider.ToolExecutionResult.success(Map.of("status", "OK"));

            assertThat(NodePolicyApplier.describeInResult(success, session, "mcp:publish"))
                    .isSameAs(success);
        }

        @Test
        @DisplayName("an unresolvable node adds nothing rather than throwing")
        void addsNothingForAnUnknownNode() {
            var success = ToolsProvider.ToolExecutionResult.success(Map.of("status", "OK"));

            assertThat(NodePolicyApplier.describeInResult(success, session, "mcp:nope"))
                    .isSameAs(success);
        }
    }

    @Nested
    @DisplayName("writing it onto the node")
    class ApplyToNode {

        @Test
        @DisplayName("only the fields that carry a decision are stored")
        void storesOnlyWhatWasDecided() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));

            assertThat(NodePolicyApplier.applyToNode(node, Map.of("retryCount", 2), "mcp:publish")).isTrue();

            assertThat(node.get(NodePolicy.JSON_KEY))
                    .as("writing every default would leave noise on every node the agent touches")
                    .isEqualTo(Map.of("retryCount", 2));
        }

        @Test
        @DisplayName("a zero provider budget IS stored, because absent means something else")
        void storesAZeroProviderBudget() {
            // The only field where 0 is a statement rather than a default: absent leaves the
            // platform's retry in place, 0 turns it off. Dropping it as "just a zero" would make
            // "the author owns the pacing" unexpressible through the tool.
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));

            NodePolicyApplier.applyToNode(node, Map.of("providerRetryMaxWaitSec", 0), "mcp:publish");

            assertThat(node.get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("providerRetryMaxWaitSec", 0));
        }

        @Test
        @DisplayName("the block is REPLACED, not merged, so a setting can be unsaid")
        void replacesRatherThanMerges() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));
            NodePolicyApplier.applyToNode(node, Map.of("retryCount", 2, "timeoutMs", 30000), "mcp:publish");

            NodePolicyApplier.applyToNode(node, Map.of("retryCount", 1), "mcp:publish");

            assertThat(node.get(NodePolicy.JSON_KEY))
                    .as("a deep merge would leave the timeout behind forever")
                    .isEqualTo(Map.of("retryCount", 1));
        }

        @Test
        @DisplayName("an empty block removes the policy, which is how a caller clears one")
        void emptyBlockRemovesIt() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));
            NodePolicyApplier.applyToNode(node, Map.of("retryCount", 2), "mcp:publish");

            assertThat(NodePolicyApplier.applyToNode(node, Map.of(), "mcp:publish")).isTrue();

            assertThat(node).doesNotContainKey(NodePolicy.JSON_KEY);
        }

        @Test
        @DisplayName("a block whose fields are all defaults removes it too, since it says nothing")
        void allDefaultsRemovesIt() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));
            NodePolicyApplier.applyToNode(node, Map.of("retryCount", 2), "mcp:publish");

            NodePolicyApplier.applyToNode(node, Map.of("retryCount", 0, "continueOnFailure", false), "mcp:publish");

            assertThat(node).doesNotContainKey(NodePolicy.JSON_KEY);
        }

        @Test
        @DisplayName("a session node that does not exist is reported as no change, not written blind")
        void anUnknownNodeIsNoChange() {
            WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                    .sessionId("s").tenantId("t").build();

            assertThat(NodePolicyApplier.apply(session, Map.of("retryCount", 2), "mcp:nope"))
                    .as("the caller turns this into a loud failure rather than a silent success")
                    .isFalse();
        }

        @Test
        @DisplayName("clearing a policy that was never there changes nothing, and says so")
        void clearingNothingIsNotAChange() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));

            assertThat(NodePolicyApplier.applyToNode(node, Map.of(), "mcp:publish"))
                    .as("reporting a change that did not happen would put a phantom field in the "
                            + "modify report")
                    .isFalse();
        }

        @Test
        @DisplayName("re-sending the same policy is not a change")
        void reSendingTheSamePolicyIsNotAChange() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));
            NodePolicyApplier.applyToNode(node, Map.of("retryCount", 2), "mcp:publish");

            assertThat(NodePolicyApplier.applyToNode(node, Map.of("retryCount", 2), "mcp:publish")).isFalse();
        }

        @Test
        @DisplayName("a numeric string is normalised to a number in the stored block")
        void coercesNumericStrings() {
            Map<String, Object> node = mutable(Map.of("id", "mcp:publish"));

            NodePolicyApplier.applyToNode(node, Map.of("retryCount", "3"), "mcp:publish");

            assertThat(node.get(NodePolicy.JSON_KEY))
                    .as("the plan is read back by the engine's parser, so store what it stores")
                    .isEqualTo(Map.of("retryCount", 3));
        }
    }
}
