package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and applies the per-node execution policy an agent sends with {@code add_node} /
 * {@code modify}.
 *
 * <p><b>Why this exists.</b> Until now {@code nodePolicy} could only be set by writing a whole
 * plan through {@code set_plan}, or by a person in the builder. So an agent could compose a
 * workflow that paces its own calls around a rate-limited provider and had no way to say
 * "and do not retry underneath me", the one instruction that keeps the two layers from
 * multiplying each other's requests.
 *
 * <p><b>Why the policy is LIFTED OUT of the call before anything else reads it.</b> It is not a
 * parameter of the node's own type. Left in place it would be refused as an unknown parameter by
 * the schema validator on a core node, and, worse, sent to the provider as a tool argument on an
 * mcp node, where the whole map is the endpoint's arguments.
 *
 * <p><b>Why it validates through {@link NodePolicy#fromMap}.</b> That is the same parser the
 * execution engine runs on the plan. Writing a second, laxer check here is how a tool comes to
 * accept a shape the runtime later rejects, or worse, silently drops. One parser, one verdict.
 *
 * <p><b>Why it runs at the dispatch point rather than in each creator.</b> A policy applies to
 * every executable node type, and there are fifty places where a creator finalises a node. Doing
 * it once, where every {@code add_node} passes, means a node type added next year is covered
 * without anyone remembering to wire it.
 */
public final class NodePolicyApplier {

    /**
     * The canonical key, plus the spellings a model reaches for. Deliberately NOT the bare word
     * {@code policy}: on an mcp node every other key is the provider endpoint's own argument, and
     * a provider is free to have a parameter called {@code policy}. Claiming that word would
     * silently swallow it.
     */
    static final List<String> PARAM_KEYS =
            List.of("nodePolicy", "node_policy", "executionPolicy", "execution_policy");

    /** Key the plan carries, and the key the exporter reads off the session node. */
    static final String NODE_KEY = NodePolicy.JSON_KEY;

    private NodePolicyApplier() {
    }

    /** Containers a creator reads a field out of, besides the root of the call. */
    private static final List<String> NESTED_KEYS = List.of("params", "parameters");

    /**
     * Reads the policy off the root of a call WITHOUT changing anything.
     *
     * <p>For a map this code does not own: the tool arguments belong to the caller, and may be
     * immutable.
     *
     * @return the raw policy value, or {@code null} when there is none.
     */
    public static Object peekRoot(Map<String, Object> call) {
        if (call == null) {
            return null;
        }
        for (String key : PARAM_KEYS) {
            Object value = call.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Removes the policy from a map this code OWNS, and returns it raw.
     *
     * <p>The root and its {@code params} / {@code parameters} containers are both cleared, because
     * every creator accepts a field in either place: one left behind would reach an mcp node's
     * provider as an argument it never declared. Both are cleared even when only one carried a
     * value, so a caller that sent it twice leaves no copy.
     *
     * <p>A nested container is REPLACED by a stripped copy rather than mutated. It usually belongs
     * to the caller, who is free to pass an immutable map, and mutating an argument map is a side
     * effect nobody asked for. Callers holding their own reference to that container must re-read
     * it from the map afterwards.
     *
     * @return the raw policy value, or {@code null} when the caller sent none.
     */
    public static Object strip(Map<String, Object> owned) {
        if (owned == null) {
            return null;
        }
        Object found = null;
        for (String key : PARAM_KEYS) {
            Object removed = owned.remove(key);
            if (removed != null && found == null) {
                found = removed;
            }
        }
        for (String nestedKey : NESTED_KEYS) {
            Object nested = owned.get(nestedKey);
            if (!(nested instanceof Map<?, ?> map) || PARAM_KEYS.stream().noneMatch(map::containsKey)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            for (String key : PARAM_KEYS) {
                Object removed = copy.remove(key);
                if (removed != null && found == null) {
                    found = removed;
                }
            }
            owned.put(nestedKey, copy);
        }
        return found;
    }

    /**
     * Checks a policy WITHOUT touching the session.
     *
     * <p>Called before the node is created, deliberately: refusing after creation would leave a
     * node behind that reads as configured and behaves as if it were not, which is the worst
     * outcome for a caller that cannot look at the canvas.
     *
     * @return an error message to hand back to the caller, or {@code null} when the policy is
     *         valid or absent.
     */
    public static String validate(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            NodePolicy.fromMap(raw, "node");
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * The one rule the {@code add_node} TYPE settles on its own: a trigger or a note is not an
     * executed step, so no policy belongs on it whatever else the call says.
     *
     * <p>Checked before the node is created, because a caller that reads a failure and sends
     * {@code add_node} again would otherwise end up with two nodes.
     *
     * <p>Deliberately NOT extended to "does this type make a provider call". That question cannot be
     * answered from the type: {@code add_node} accepts a tool as a UUID, as a prefixed UUID and as
     * an {@code apiSlug/toolSlug} pair, and its switch treats every UNRECOGNISED type as a tool. A
     * second predicate guessing at that shape refused the primary use of the feature through the
     * primary door. The stored node id answers it exactly, so {@link #rejectionForNode} does.
     *
     * @param isTriggerOrNote decided by the caller from the same list its own switch dispatches on.
     * @return an error message, or {@code null} when the type raises no objection.
     */
    public static String rejectionForType(Object raw, boolean isTriggerOrNote) {
        if (raw == null || !isTriggerOrNote) {
            return null;
        }
        return TRIGGER_OR_NOTE_REFUSAL;
    }

    /** Said the same way wherever a policy is refused for landing on a non-executed node. */
    static final String TRIGGER_OR_NOTE_REFUSAL =
            "An execution policy is not available on trigger or note nodes. A policy governs how an "
            + "executed step behaves on failure, and a trigger starts the run while a note annotates "
            + "the canvas. Put the policy on the node that does the work.";

    /**
     * The rules that need the node, not just the policy: which node types the engine will accept
     * this policy on.
     *
     * <p>Delegates to {@link WorkflowPlanParser}, which owns them, so the tool cannot come to
     * accept what the engine refuses. Checking here matters because the engine's refusal arrives
     * at plan-parse time: without this the agent would get a node it can add and then never run,
     * with the error surfacing on a later, unrelated call.
     *
     * @return an error message, or {@code null} when the policy is allowed on this node.
     */
    public static String rejectionForNode(String nodeId, Map<String, Object> node, Object raw) {
        if (raw == null || nodeId == null) {
            return null;
        }
        // Triggers and notes are not executed steps, so the engine collects no policy for them:
        // storing one would be accepted in silence and do nothing, which is worse than a refusal.
        if (LabelNormalizer.isTriggerKey(nodeId) || LabelNormalizer.isNoteKey(nodeId)) {
            return TRIGGER_OR_NOTE_REFUSAL;
        }
        NodePolicy parsed;
        try {
            parsed = NodePolicy.fromMap(raw, nodeId);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        // Only a catalog tool step carries this budget to the provider (StepNode is the one node
        // that sends it). Accepting it anywhere else would store a setting nothing ever reads: the
        // call succeeds, the plan carries the field, the platform keeps retrying underneath an
        // author who asked it not to, and nothing anywhere says so.
        String budgetRejection = WorkflowPlanParser.providerRetryRejection(
                nodeId, parsed, LabelNormalizer.isMcpKey(nodeId));
        if (budgetRejection != null) {
            return budgetRejection;
        }
        String coreType = node == null ? null : String.valueOf(node.get("type"));
        String rejection = WorkflowPlanParser.continueOnFailureRejection(coreType, parsed, nodeId);
        if (rejection != null) {
            return rejection;
        }
        return WorkflowPlanParser.executeOnceRejection(coreType, parsed, nodeId);
    }

    /**
     * Writes a policy onto a node that already exists. Call {@link #validate} first; an invalid
     * policy here is not written rather than half-applied.
     *
     * @return true when the node's policy block changed.
     */
    public static boolean apply(WorkflowBuilderSession session, Object raw, String nodeId) {
        if (session == null || nodeId == null || raw == null) {
            return false;
        }
        Optional<Map<String, Object>> node = session.findNode(nodeId);
        if (node.isEmpty()) {
            return false;
        }
        return applyToNode(node.get(), raw, nodeId);
    }

    /** Same, on a node map the caller already holds. */
    public static boolean applyToNode(Map<String, Object> node, Object raw, String nodeId) {
        if (node == null || raw == null) {
            return false;
        }
        NodePolicy parsed;
        try {
            parsed = NodePolicy.fromMap(raw, nodeId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (parsed.isDefault()) {
            // A policy that says nothing is the absence of a policy. Removing the block rather
            // than storing an empty one is what lets a caller CLEAR a policy it set earlier,
            // with the same call shape it used to set it.
            return node.remove(NODE_KEY) != null;
        }
        Map<String, Object> canonical = toPlanMap(parsed);
        return !canonical.equals(node.put(NODE_KEY, canonical));
    }

    /**
     * Adds the policy now on the node to a successful result, so the caller reads back what was
     * stored rather than what it sent.
     *
     * <p>Mirrors what {@code modify} reports. A caller that cannot see the canvas has no other way
     * to tell a policy that was applied from one that was quietly coerced or dropped.
     *
     * @return the same result when there is nothing to add or its payload is not a map.
     */
    public static ToolsProvider.ToolExecutionResult describeInResult(
            ToolsProvider.ToolExecutionResult result, WorkflowBuilderSession session, String nodeId) {
        if (result == null || session == null || nodeId == null || !result.success()) {
            return result;
        }
        Object stored = session.findNode(nodeId).map(node -> node.get(NODE_KEY)).orElse(null);
        if (!(stored instanceof Map<?, ?> policy) || policy.isEmpty()
                || !(result.data() instanceof Map<?, ?> data)) {
            return result;
        }
        Map<String, Object> enriched = new LinkedHashMap<>();
        data.forEach((k, v) -> enriched.put(String.valueOf(k), v));
        enriched.put(NODE_KEY, stored);
        enriched.put("node_policy_hint", "Governs this node on every run, editor and production "
                + "alike. Replace the whole block with workflow(action='modify', node='<label>', "
                + "nodePolicy={...}), or nodePolicy={} to remove it.");
        return ToolsProvider.ToolExecutionResult.success(enriched);
    }

    /**
     * The canonical block, with only the fields that carry a decision. Absent is not the same as
     * zero for {@code providerRetryMaxWaitSec}: absent leaves the platform's retry in place, zero
     * turns it off, so that one is written whenever it was set, including when it is 0.
     */
    static Map<String, Object> toPlanMap(NodePolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (policy.retryCount() > 0) {
            map.put("retryCount", policy.retryCount());
        }
        if (policy.retryBackoffMs() > 0) {
            map.put("retryBackoffMs", policy.retryBackoffMs());
        }
        if (policy.continueOnFailure()) {
            map.put("continueOnFailure", true);
        }
        if (policy.timeoutMs() > 0) {
            map.put("timeoutMs", policy.timeoutMs());
        }
        if (policy.executeOnce()) {
            map.put("executeOnce", true);
        }
        if (policy.providerRetryMaxWaitSec() != null) {
            map.put("providerRetryMaxWaitSec", policy.providerRetryMaxWaitSec());
        }
        return map;
    }
}
