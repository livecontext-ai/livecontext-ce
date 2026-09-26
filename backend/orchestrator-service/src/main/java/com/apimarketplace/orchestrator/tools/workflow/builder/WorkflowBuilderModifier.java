package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.NodeMock;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.CreatorBase;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Handles modification actions for workflow builder.
 * Actions: modify, remove, undo
 *
 * IMPORTANT: remove does NOT auto-reconnect nodes. It shows what was disconnected
 * and provides hints for manual reconnection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderModifier {

    private final WorkflowBuilderSessionStore sessionStore;

    /**
     * Remove a node WITHOUT auto-reconnecting.
     * Shows disconnection info and hints.
     */
    public ToolExecutionResult executeRemove(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String nodeRef = (String) parameters.get("node");
        if (nodeRef == null) nodeRef = (String) parameters.get("node_id");

        if (nodeRef == null || nodeRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'node' parameter is required. Use the node label (e.g., 'My Step') or full nodeId.");
        }

        String nodeId = session.resolveNodeReference(nodeRef);

        // Find the node first
        Optional<Map<String, Object>> nodeOpt = session.findNode(nodeId);
        if (nodeOpt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Node not found: " + nodeRef +
                ". Available: " + formatAvailableNodes(session));
        }

        Map<String, Object> nodeData = new LinkedHashMap<>(nodeOpt.get());
        String logicalId = session.getLogicalIdOrFail(nodeId);  // Get before removal - never null
        String label = (String) nodeData.get("label");

        // Get incoming and outgoing connections BEFORE removal
        List<Map<String, Object>> incomingEdges = session.getIncomingConnections(nodeId);
        List<Map<String, Object>> outgoingEdges = session.getOutgoingConnections(nodeId);

        // Store full state for undo
        Map<String, Object> previousState = new LinkedHashMap<>();
        previousState.put("node", nodeData);
        previousState.put("incomingEdges", new ArrayList<>(incomingEdges));
        previousState.put("outgoingEdges", new ArrayList<>(outgoingEdges));
        previousState.put("logicalId", logicalId);

        // Remove the node
        boolean removed = session.removeNode(nodeId);
        if (!removed) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to remove node: " + nodeRef);
        }

        // Remove edges (NO auto-reconnect!)
        WorkflowBuilderSession.DisconnectionInfo disconnections = session.removeEdgesForNode(nodeId);

        // Remove logical mapping and linked interfaces
        List<String> unlinkedInterfaces = session.unlinkAllInterfaces(nodeId);

        // Record action for undo (with full state to restore)
        session.recordAction("remove", nodeId, getNodeType(nodeId), previousState);
        session.clearRedoStack();

        sessionStore.save(session);

        // Build detailed response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        // When logicalId == label (the common case) the message used to render as
        // `Node "X" "X" removed.` which the LLM systematically interpreted as two
        // separate instances and re-issued the same remove call. Collapse to a
        // single quoted form so the model sees an unambiguous "the node is gone".
        String displayName = (label != null && label.equals(logicalId))
                ? "\"" + label + "\""
                : logicalId + " \"" + label + "\"";
        result.put("message", "Node " + displayName + " removed.");

        // Show what was disconnected
        if (disconnections.hasDisconnections()) {
            Map<String, Object> affected = new LinkedHashMap<>();

            if (!disconnections.sourcesThatLostTarget().isEmpty()) {
                List<Map<String, Object>> brokenIncoming = new ArrayList<>();
                for (String sourceId : disconnections.sourcesThatLostTarget()) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("node", formatNodeRef(session, sourceId));
                    info.put("was_connected_to", logicalId);
                    info.put("now", "no outgoing connection");
                    brokenIncoming.add(info);
                }
                affected.put("nodes_that_lost_their_target", brokenIncoming);
            }

            if (!disconnections.targetsThatLostSource().isEmpty()) {
                List<Map<String, Object>> brokenOutgoing = new ArrayList<>();
                for (String targetId : disconnections.targetsThatLostSource()) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("node", formatNodeRef(session, targetId));
                    info.put("was_connected_from", logicalId);
                    info.put("now", "orphan (no incoming connection)");
                    brokenOutgoing.add(info);
                }
                affected.put("nodes_that_became_orphans", brokenOutgoing);
            }

            result.put("disconnections", affected);
        }

        // Provide reconnection hints
        List<String> hints = new ArrayList<>();

        if (!disconnections.sourcesThatLostTarget().isEmpty() && !disconnections.targetsThatLostSource().isEmpty()) {
            // There were both incoming and outgoing - suggest reconnecting them
            String firstSource = disconnections.sourcesThatLostTarget().get(0);
            String firstTarget = disconnections.targetsThatLostSource().get(0);
            hints.add("To reconnect: workflow(action='connect', from='" +
                session.getLogicalId(firstSource) + "', to='" +
                session.getLogicalId(firstTarget) + "')");
        }

        if (!disconnections.targetsThatLostSource().isEmpty()) {
            for (String orphan : disconnections.targetsThatLostSource()) {
                String orphanRef = session.getLogicalId(orphan);
                hints.add("Node " + orphanRef + " needs incoming connection OR workflow(action='remove', node='" + orphanRef + "')");
            }
        }

        if (!hints.isEmpty()) {
            result.put("suggested_actions", hints);
        }

        // Show unlinked interfaces
        if (!unlinkedInterfaces.isEmpty()) {
            result.put("unlinked_interfaces", unlinkedInterfaces);
        }

        result.put("tip", "Use workflow(action='undo') to restore this node and all its connections.");

        return ToolExecutionResult.success(result);
    }

    /**
     * Modify a specific node.
     *
     * HARMONIZED SYNTAX: Same as ADD - uses params={} with same keys.
     * Example: workflow(action='modify', node='Check Status', params={conditions: [...]})
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeModifyNode(WorkflowBuilderSession session, Map<String, Object> parameters) {
        String nodeRef = (String) parameters.get("node");
        if (nodeRef == null) nodeRef = (String) parameters.get("node_id");

        if (nodeRef == null || nodeRef.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'node' parameter is required. Available: " + formatAvailableNodes(session));
        }

        String nodeId = session.resolveNodeReference(nodeRef);

        // HARMONIZED: Accept both params={} (like ADD) and changes={} (legacy)
        Object paramsObj = parameters.get("params");
        Object changesObj = parameters.get("changes");

        Map<String, Object> rawChanges;
        if (paramsObj != null) {
            rawChanges = new LinkedHashMap<>((Map<String, Object>) paramsObj);
        } else if (changesObj != null) {
            rawChanges = new LinkedHashMap<>((Map<String, Object>) changesObj);
        } else {
            // Allow modify with only connect_after, only mock, or only an execution policy
            // (no params needed)
            String topLevelConnectAfter = (String) parameters.get("connect_after");
            if ((topLevelConnectAfter != null && !topLevelConnectAfter.isBlank())
                    || parameters.get("mock") != null
                    || NodePolicyApplier.peekRoot(parameters) != null) {
                rawChanges = new LinkedHashMap<>();
            } else {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'params' is required. Use same syntax as ADD:\n\n" +
                    "DECISION:\n" +
                    "  workflow(action='modify', node='Check Status',\n" +
                    "    params={conditions: [{condition: '{{mcp:step.output.status}} == \"ok\"', label: 'Success'}, {condition: 'default', label: 'Other'}]})\n\n" +
                    "AGENT:\n" +
                    "  workflow(action='modify', node='Analyzer',\n" +
                    "    params={prompt: 'New prompt {{mcp:step.output}}', temperature: 0.7})\n\n" +
                    "MCP:\n" +
                    "  workflow(action='modify', node='Send Email',\n" +
                    "    params={to: '{{trigger:form.output.email}}', subject: 'Hello'})");
            }
        }

        // Resolve connect_after: accept from top-level parameters OR inside params/changes
        String connectAfterRef = (String) parameters.get("connect_after");
        if (connectAfterRef == null || connectAfterRef.isBlank()) {
            connectAfterRef = (String) rawChanges.remove("connect_after");
        } else {
            rawChanges.remove("connect_after"); // Remove from changes if also present
        }

        // Resolve mock: top-level (canonical, like connect_after) with a rescue for
        // the LLM nesting it inside params - node.params.mock would otherwise be
        // sent to the real API as a tool argument (same trap as tool_id).
        Object mockObj = parameters.get("mock");
        if (mockObj == null && rawChanges.containsKey("mock")) {
            mockObj = rawChanges.remove("mock");
        } else if (mockObj != null) {
            rawChanges.remove("mock");
        }

        // Resolve the execution policy the same way, and LIFT IT OUT of the patch: on an mcp node
        // every remaining key is an endpoint argument, so a nodePolicy left in `changes` would be
        // merged into the node as a parameter and sent to the provider. The caller's `parameters`
        // is only READ - it belongs to the caller and may be immutable; `rawChanges` is our own
        // copy, and is the one that must come out clean.
        Object rootPolicy = NodePolicyApplier.peekRoot(parameters);
        Object nestedPolicy = NodePolicyApplier.strip(rawChanges);
        Object policyObj = rootPolicy != null ? rootPolicy : nestedPolicy;
        String policyError = NodePolicyApplier.validate(policyObj);
        if (policyError != null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                policyError + " The node was left unchanged. See "
                    + "workflow(action='help', topics=['node_policy']).");
        }

        if (rawChanges.isEmpty() && (connectAfterRef == null || connectAfterRef.isBlank())
                && mockObj == null && policyObj == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'params' object cannot be empty.");
        }

        // Find the node first to determine its type
        Map<String, Object> node = findNodeById(session, nodeId);
        if (node == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Node not found: " + nodeRef + ". Available: " + formatAvailableNodes(session));
        }

        // Two spellings of the SAME field in one patch have no defensible winner, so say so
        // instead of picking one: a params map carries no order, and silently keeping either
        // value would make the same patch produce two different nodes on two calls.
        String aliasConflict = findAmbiguousAliasPatch(rawChanges, nodeId, node);
        if (aliasConflict != null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, aliasConflict);
        }

        // The execution-policy rules that need the node's TYPE, checked here: before the first
        // write below, so refusing really does leave the node untouched.
        String policyRejection = NodePolicyApplier.rejectionForNode(nodeId, node, policyObj);
        if (policyRejection != null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                policyRejection + " The node was left unchanged.");
        }

        // HARMONIZE: Convert ADD-style params to internal storage format
        // Snapshot before harmonization: the report is written in these words.
        Set<String> reportedKeys = new LinkedHashSet<>(rawChanges.keySet());
        Map<String, Object> requestedByCaller = new LinkedHashMap<>(rawChanges);
        Map<String, Object> changes = rawChanges.isEmpty() ? new LinkedHashMap<>() : harmonizeParams(rawChanges, nodeId, node);

        // Refuse before anything reads the value: the interface branch below casts
        // actionMapping to a Map, so a scalar there would throw a raw ClassCastException
        // at the agent instead of an actionable message.
        String clobbered = blockReplacedByScalar(node, nodeId, changes);
        if (clobbered != null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_TYPE,
                "'" + clobbered + "' is a configuration block on " + formatNodeRef(session, nodeId)
                + " and must stay a map of fields, not " + formatExample(changes.get(clobbered))
                + ". Send the field you mean, params={<field>: <value>}, or the block as a "
                + "map. Replacing it with a scalar leaves the node unreadable on the next "
                + "load, and the workflow cannot then be opened to repair it.");
        }

        // Validate action_mapping references for interface nodes
        List<String> actionMappingWarnings = new ArrayList<>();
        if (LabelNormalizer.isInterfaceKey(nodeId) && changes.containsKey("actionMapping")) {
            // Reject non-string values BEFORE merging - same contract as the add_node path.
            // Prevents {trigger:..., mapping:{...}} objects (and similar agent inventions)
            // from being silently coerced via NodeFieldMerger / Map.toString().
            try {
                com.apimarketplace.orchestrator.tools.interface_.InterfaceNodeConfig
                    .assertActionMappingValuesAreStrings(changes.get("actionMapping"));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
            }
            actionMappingWarnings.addAll(validateActionMappingReferences(changes.get("actionMapping"), session));
            // Also check cross-DAG references (using shared utility from CreatorBase)
            @SuppressWarnings("unchecked")
            Map<String, String> am = (Map<String, String>) changes.get("actionMapping");
            if (am != null) {
                Set<String> alreadyFlagged = extractFlaggedTriggerLabels(actionMappingWarnings);
                actionMappingWarnings.addAll(
                    CreatorBase.checkCrossDagReferences(am, nodeId, session, alreadyFlagged));
            }
        }

        // Apply the mock block BEFORE the generic merge so its old value rides the
        // same undo payload. The block is validated against the node's real type
        // and ports (single source of truth: WorkflowPlanParser) and REPLACED
        // whole - deep-merging an old mock output into a new one would resurrect
        // stale keys. mock={} (empty object) removes the mock.
        boolean mockChanged = false;
        Object oldMockValue = node.get(NodeMock.JSON_KEY);
        String mockKind = null;
        if (mockObj != null) {
            if (LabelNormalizer.isTriggerKey(nodeId) || LabelNormalizer.isNoteKey(nodeId)) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Mocking is not available on trigger or note nodes. A mock replaces an executed step's "
                        + "output, and triggers/notes are not executed steps. Use data_inputs on execute to fake "
                        + "a trigger payload, or set the mock on a downstream node instead.");
            }
            if (!(mockObj instanceof Map)) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "'mock' must be an object: {output: {...}} | {source: 'catalog_example'} | "
                        + "{error: {message: '...'}} | {port: '...'} - or {} to remove the mock. "
                        + "Any form also takes durationMs (simulated execution time in milliseconds, "
                        + "max 600000). See workflow(action='help', topics=['mocking']).");
            }
            Map<String, Object> mockMap = (Map<String, Object>) mockObj;
            if (mockMap.isEmpty()) {
                mockChanged = node.remove(NodeMock.JSON_KEY) != null || oldMockValue != null;
            } else {
                String validationError = validateMockAgainstNode(session, nodeId, node, mockMap);
                if (validationError != null) {
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, validationError);
                }
                node.put(NodeMock.JSON_KEY, new LinkedHashMap<>(mockMap));
                mockChanged = true;
                mockKind = describeMockKind(mockMap);
            }
        }

        // Apply the execution policy on the same footing as the mock: whole-block replacement,
        // and an empty block ({} or one whose fields are all defaults) REMOVES it. Deep-merging
        // would make "turn the retry off" unexpressible, because the caller could never unsay a
        // field it had set.
        Object oldPolicyValue = node.get(NodePolicy.JSON_KEY);
        boolean policyChanged = policyObj != null
                && NodePolicyApplier.applyToNode(node, policyObj, nodeId);

        // Store old values for undo
        Map<String, Object> oldValues = new LinkedHashMap<>();
        for (String key : changes.keySet()) {
            oldValues.put(key, node.get(key));
        }
        // Separate from the undo snapshot above, which must stay top-level so undo can
        // restore the node verbatim. This one is for the REPORT: what the engine was
        // reading before the patch, which is not always what sat at the top level.
        // Keyed by what the CALLER asked for, not by what harmonization renamed it to.
        // Routing params={limit: 100} into the crud block is correct, but reporting
        // "crud" back would hide the field the caller is actually asking about.
        Map<String, Object> effectiveBefore = new LinkedHashMap<>();
        for (String key : reportedKeys) {
            effectiveBefore.put(key, effectiveValue(node, nodeId, key));
        }
        for (String key : changes.keySet()) {
            effectiveBefore.put(key, effectiveValue(node, nodeId, key));
        }
        if (mockChanged) {
            oldValues.put(NodeMock.JSON_KEY, oldMockValue);
        }
        if (policyChanged) {
            oldValues.put(NodePolicy.JSON_KEY, oldPolicyValue);
        }

        // Check if label is changing - we need to update logical mappings
        String oldLabel = (String) node.get("label");
        String newLabel = (String) changes.get("label");
        boolean labelChanging = newLabel != null && !newLabel.equals(oldLabel);

        // A scalar `position` is an overlay anchor and must have been routed into the
        // node's config by harmonizeParams. If one still reaches here the node type has
        // no config slot to hold it, and writing it would replace the canvas
        // coordinates with a string: the plan then fails to parse on the next load,
        // AFTER being persisted, so the workflow cannot be re-opened to repair it.
        // Refuse instead of persisting something unreadable.
        if (changes.containsKey("position") && !(changes.get("position") instanceof Map)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_TYPE,
                "'position' on a node is its canvas placement and must be a map like "
                + "{x: 100, y: 200}. Node " + formatNodeRef(session, nodeId) + " has no "
                + "parameter named 'position' to hold "
                + formatExample(changes.get("position")) + ".");
        }

        // Apply changes via the centralized merger so map fields (params,
        // actionMapping, …) and list fields (decisionConditions, switchCases,
        // classifyCategories) are MERGED with existing data instead of
        // blindly overwritten. The previous dumb apply loop wiped untouched
        // sub-fields and broke "modify one item" workflows for many node
        // types - see NodeFieldMerger javadoc for the full strategy.
        // A node edited before this rewrite existed still carries the old spelling in its nested
        // config, and nothing removes it (scrubTopLevelOrphansFromNestedConfig only scrubs the
        // top level). Left alone, get_plan would show the agent two values for one field, with
        // the stale one under the spelling the node documentation recommends. Same self-heal, one
        // level in - and here rather than at harmonise time, because this file forbids mutating
        // the node during harmonisation, where a later gate can still fail the call. Placed with
        // the merge, so the two land together: the connect_after resolution further down can
        // still fail, but by then the merge has applied anyway, which is pre-existing behaviour.
        dropSupersededSpellings(rawChanges, nodeId, node, changes);

        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            Object value = LabelNormalizer.normalizeValueDeep(entry.getValue());
            NodeFieldMerger.merge(node, entry.getKey(), value);
        }

        // Self-heal pass: drop top-level orphan keys that duplicate inner fields
        // of the node's nested config. Past versions of this code missed a few
        // node types in NESTED_CONFIG_KEYS, so patches like {assignments: [...]}
        // for a `set` node landed at top level (where the engine never reads)
        // instead of inside `set.assignments` (where the engine does). Even
        // after fixing the routing, persisted node JSON still carries those
        // zombie keys forever - modify only changes what the LLM touched.
        // Whenever modify runs on a nested-config node, mirror the nested
        // ownership at top level by removing duplicate inner-field keys.
        scrubTopLevelOrphansFromNestedConfig(node);
        scrubCrudDuplicates(node, nodeId);
        scrubStaleDataSourceIds(node, nodeId);

        // If label changed, update nodeId and ALL references
        String newNodeId = nodeId;
        if (labelChanging) {
            newNodeId = computeNewNodeId(nodeId, newLabel);
            node.put("id", newNodeId);  // Update node.id
            session.updateAllReferences(nodeId, newNodeId);  // Update edges, pendingLoopExits, logicalMapping
        }

        // Single-entry invariant: setting is_entry_interface=true here demotes any other
        // flagged interface, exactly like the canvas builder. Without this, agent-written
        // plans could carry several "entry" pages and the author's intent would silently
        // lose to the resolver's findFirst().
        List<String> demotedEntries = List.of();
        if (changes.containsKey("isEntryInterface")
                && Boolean.TRUE.equals(node.get("isEntryInterface"))
                && session.getInterfaces().stream().anyMatch(i -> i == node)) {
            demotedEntries = session.enforceSingleEntryInterface(node);
        }

        // Handle connect_after: rewire incoming edges
        String effectiveNodeId = newNodeId; // Use new nodeId if label changed
        List<Map<String, Object>> oldIncomingEdges = null;
        String newConnectAfterNodeId = null;
        if (connectAfterRef != null && !connectAfterRef.isBlank()) {
            newConnectAfterNodeId = session.resolveNodeReference(connectAfterRef);

            // Validate the connect_after node exists
            if (!session.nodeExists(newConnectAfterNodeId)) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "connect_after node not found: " + connectAfterRef +
                    ". Available: " + formatAvailableNodes(session));
            }

            // Store old incoming edges for undo
            oldIncomingEdges = new ArrayList<>(session.getIncomingConnections(effectiveNodeId));

            // Remove ALL old incoming edges to this node
            session.getEdges().removeIf(edge -> effectiveNodeId.equals(edge.get("to")));

            // Create new edge from connect_after node
            session.addConnection(newConnectAfterNodeId, effectiveNodeId, null);
            log.info("[MODIFY] Rewired connect_after: {} → {}", newConnectAfterNodeId, effectiveNodeId);
        }

        // Record action for undo (store both old and new nodeId for label changes)
        Map<String, Object> actionData = new LinkedHashMap<>();
        actionData.put("old_values", oldValues);
        actionData.put("new_values", new LinkedHashMap<>(changes));
        if (labelChanging) {
            actionData.put("old_node_id", nodeId);
            actionData.put("new_node_id", newNodeId);
        }
        if (oldIncomingEdges != null) {
            actionData.put("old_incoming_edges", oldIncomingEdges);
            actionData.put("new_connect_after", newConnectAfterNodeId);
        }
        if (!demotedEntries.isEmpty()) {
            // Undo must restore the demoted sibling's entry flag too, or "revert this
            // change" leaves the plan with ZERO entry pages.
            actionData.put("demoted_entry_labels", demotedEntries);
        }
        session.recordAction("modify", newNodeId, getNodeType(newNodeId), actionData);
        session.clearRedoStack();

        sessionStore.save(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", "Node " + formatNodeRef(session, nodeId) + " modified.");

        // Build modified fields set including connect_after
        Set<String> modifiedFields = new LinkedHashSet<>(changes.keySet());
        if (newConnectAfterNodeId != null) {
            modifiedFields.add("connect_after");
        }
        if (mockChanged) {
            modifiedFields.add(NodeMock.JSON_KEY);
            boolean configured = node.containsKey(NodeMock.JSON_KEY);
            Map<String, Object> mockReport = new LinkedHashMap<>();
            mockReport.put("configured", configured);
            if (configured && mockKind != null) {
                mockReport.put("kind", mockKind);
            }
            if (configured && node.get(NodeMock.JSON_KEY) instanceof Map<?, ?> committedMock) {
                Object duration = committedMock.containsKey("durationMs")
                        ? committedMock.get("durationMs") : committedMock.get("duration_ms");
                if (duration != null) {
                    mockReport.put("duration_ms", duration);
                }
            }
            result.put("mock", mockReport);
            result.put("mock_hint", configured
                ? "Applies to editor runs of this workflow (execute without version='pinned'). Pass "
                    + "mock_mode='off' on execute to run everything real once; production/pinned fires always ignore mocks."
                : "Mock removed - the node executes for real again.");
        }
        if (policyChanged) {
            modifiedFields.add(NodePolicy.JSON_KEY);
            Object committed = node.get(NodePolicy.JSON_KEY);
            result.put(NodePolicy.JSON_KEY, committed != null ? committed : Map.of());
            result.put("node_policy_hint", committed != null
                ? "Applies to every execution of this node, in editor and production runs alike."
                : "Execution policy removed - the node runs with the platform defaults again.");
        }
        result.put("modified_fields", modifiedFields);

        // Show before/after for key fields
        // The caller's own words are added only when they still name something the
        // engine reads. Adding them unconditionally put a phantom
        // "(not set)" -> "(not set)" row next to the canonical key for every alias the
        // harmonizer renames (action_mapping beside actionMapping, and so on), which
        // reads like the field was ignored.
        Map<String, Object> diff = new LinkedHashMap<>();
        Set<String> diffKeys = new LinkedHashSet<>(changes.keySet());
        for (String key : reportedKeys) {
            // Present under that very name, not merely resolvable through it. The
            // read-back answers an alias with the canonical value on purpose, so keying
            // the diff on "resolves to something" would print the same row twice under
            // two spellings.
            // The parsed view names a field ONCE, so asking it is also what keeps an
            // alias from being printed beside the canonical key it resolved to.
            Map<String, Object> view = engineView(node, nodeId);
            boolean carried = view.isEmpty()
                ? (effectiveConfig(node, nodeId).containsKey(key) || node.containsKey(key))
                : view.containsKey(key);
            if (carried) diffKeys.add(key);
        }
        for (String key : diffKeys) {
            Map<String, Object> fieldDiff = new LinkedHashMap<>();
            Object before = effectiveBefore.get(key);
            // Show the ACTUAL post-merge value (node.get(key)), not the raw patch
            // the caller sent (changes.get(key)). For merge-strategy fields like
            // params / actionMapping the patch is only a partial overlay - echoing
            // it back made a key the caller OMITTED look removed when the merge had
            // in fact kept it (e.g. trying to drop AccountSid by re-sending the
            // other params). The diff must reflect the node's real state, otherwise
            // the agent believes a deletion happened and stops, leaving the stale
            // key in place. To actually delete a key, send params={key: null}.
            Object after = effectiveValue(node, nodeId, key);
            fieldDiff.put("before", before != null ? before : "(not set)");
            fieldDiff.put("after", after != null ? after : "(not set)");
            diff.put(key, fieldDiff);
        }
        if (newConnectAfterNodeId != null) {
            Map<String, Object> connectDiff = new LinkedHashMap<>();
            if (oldIncomingEdges != null && !oldIncomingEdges.isEmpty()) {
                List<String> oldSources = oldIncomingEdges.stream()
                    .map(e -> (String) e.get("from"))
                    .toList();
                connectDiff.put("before", oldSources);
            } else {
                connectDiff.put("before", "(no incoming edges)");
            }
            connectDiff.put("after", newConnectAfterNodeId);
            diff.put("connect_after", connectDiff);
        }
        result.put("changes", diff);

        Map<String, Object> notApplied = detectNotApplied(node, nodeId, requestedByCaller);
        if (!notApplied.isEmpty()) {
            result.put("NOT_APPLIED", notApplied);
            result.put("NOT_APPLIED_HINT", "The engine reads a different value under these "
                + "names than the one requested. Confirm with workflow(action='get_plan') "
                + "before relying on them. If the field is one this node type genuinely "
                + "owns, it is a builder defect worth reporting; a node carrying a field "
                + "from an older plan shape can also read this way.");
        }

        // Include action_mapping warnings (non-blocking) so LLM can fix references
        if (!actionMappingWarnings.isEmpty()) {
            result.put("ACTION_MAPPING_WARNING",
                "Some action_mapping references point to nodes that do not exist: " +
                actionMappingWarnings + ". These bindings won't work until the referenced triggers/interfaces exist.");
        }

        if (!demotedEntries.isEmpty()) {
            result.put("entry_interface_moved", "This interface is now the app's entry page; "
                + "is_entry_interface was cleared on: " + demotedEntries + " (an app has ONE entry page).");
        }

        result.put("tip", "Use workflow(action='undo') to revert this change.");

        return ToolExecutionResult.success(result);
    }

    /**
     * Undo the last action.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeUndo(WorkflowBuilderSession session) {
        Optional<WorkflowBuilderSession.SessionAction> lastActionOpt = session.popLastAction();
        if (lastActionOpt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Nothing to undo. History is empty.");
        }

        WorkflowBuilderSession.SessionAction action = lastActionOpt.get();
        String actionType = action.getActionType();
        String nodeId = action.getNodeId();
        Map<String, Object> data = action.getNodeData();

        String description;

        switch (actionType) {
            case "add_trigger", "add_mcp", "add_agent", "add_decision",
                 "add_interface", "add_table", "add_note", "add_fork" -> {
                // Remove the added node
                if (nodeId != null && session.removeNode(nodeId)) {
                    session.removeEdgesForNode(nodeId);
                                description = "Removed " + actionType.replace("add_", "") + " \"" + nodeId + "\"";
                } else {
                    description = "Could not find node to remove";
                }
            }
            case "remove" -> {
                // Restore the removed node
                if (data != null) {
                    Map<String, Object> nodeData = (Map<String, Object>) data.get("node");
                    List<Map<String, Object>> incomingEdges = (List<Map<String, Object>>) data.get("incomingEdges");
                    List<Map<String, Object>> outgoingEdges = (List<Map<String, Object>>) data.get("outgoingEdges");

                    // Restore node
                    restoreNode(session, nodeId, nodeData);

                    // Restore edges
                    if (incomingEdges != null) {
                        session.getEdges().addAll(incomingEdges);
                    }
                    if (outgoingEdges != null) {
                        session.getEdges().addAll(outgoingEdges);
                    }

                    description = "Restored node \"" + nodeId + "\" with all connections";
                } else {
                    description = "Could not restore node - no data available";
                }
            }
            case "connect" -> {
                // Remove the added edge
                if (data != null) {
                    String from = (String) data.get("from");
                    String to = (String) data.get("to");
                    session.removeConnection(from, to);
                    description = "Removed connection " + from + " → " + to;
                } else {
                    description = "Could not remove connection - no data available";
                }
            }
            case "disconnect" -> {
                // Restore the removed edge
                if (data != null) {
                    session.getEdges().add(new LinkedHashMap<>(data));
                    description = "Restored connection " + data.get("from") + " → " + data.get("to");
                } else {
                    description = "Could not restore connection - no data available";
                }
            }
            case "modify" -> {
                // Restore old values
                if (data != null && nodeId != null) {
                    Map<String, Object> oldValues = (Map<String, Object>) data.get("old_values");
                    Map<String, Object> node = findNodeById(session, nodeId);
                    if (node != null && oldValues != null) {
                        for (Map.Entry<String, Object> entry : oldValues.entrySet()) {
                            if (entry.getValue() == null) {
                                // The field did not exist before the modify, so undo must leave
                                // it absent, not present-and-null. A null block still answers
                                // containsKey, which is how the mock and policy reports decide
                                // whether one is configured - undo would report a mock the node
                                // no longer has.
                                node.remove(entry.getKey());
                            } else {
                                node.put(entry.getKey(), entry.getValue());
                            }
                        }
                        description = "Reverted changes to \"" + nodeId + "\"";
                    } else {
                        description = "Could not revert changes";
                    }
                    // Restore old incoming edges if connect_after was changed
                    List<Map<String, Object>> oldIncomingEdges = (List<Map<String, Object>>) data.get("old_incoming_edges");
                    String newConnectAfter = (String) data.get("new_connect_after");
                    if (oldIncomingEdges != null && newConnectAfter != null) {
                        // Remove the edge created by connect_after
                        session.removeConnection(newConnectAfter, nodeId);
                        // Restore old incoming edges
                        for (Map<String, Object> edge : oldIncomingEdges) {
                            session.getEdges().add(new LinkedHashMap<>(edge));
                        }
                    }
                    // Re-flag the sibling(s) this modify demoted via the single-entry
                    // invariant - restoring only the modified node would leave the plan
                    // with no entry page at all.
                    List<String> demotedLabels = (List<String>) data.get("demoted_entry_labels");
                    if (demotedLabels != null) {
                        for (Map<String, Object> iface : session.getInterfaces()) {
                            if (demotedLabels.contains(String.valueOf(iface.get("label")))) {
                                iface.put("isEntryInterface", true);
                            }
                        }
                    }
                } else {
                    description = "Could not revert changes - no data available";
                }
            }
            default -> {
                description = "Undone action: " + actionType;
            }
        }

        // Push to redo stack
        session.pushToRedoStack(action);
        sessionStore.save(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", description);
        result.put("undone_action", actionType);

        return ToolExecutionResult.success(result);
    }

    /**
     * Redo the last undone action.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeRedo(WorkflowBuilderSession session) {
        Optional<WorkflowBuilderSession.SessionAction> redoActionOpt = session.popRedoStack();
        if (redoActionOpt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Nothing to redo.");
        }

        WorkflowBuilderSession.SessionAction action = redoActionOpt.get();
        String actionType = action.getActionType();
        String nodeId = action.getNodeId();
        Map<String, Object> data = action.getNodeData();

        String description;

        switch (actionType) {
            case "add_trigger", "add_mcp", "add_agent", "add_decision",
                 "add_interface", "add_table", "add_note", "add_fork" -> {
                // Re-add the node
                if (data != null) {
                    restoreNode(session, nodeId, data);
                    description = "Re-added " + actionType.replace("add_", "") + " \"" + nodeId + "\"";
                } else {
                    description = "Could not re-add node - no data available";
                }
            }
            case "remove" -> {
                // Re-remove the node
                if (nodeId != null && session.removeNode(nodeId)) {
                    session.removeEdgesForNode(nodeId);
                                description = "Re-removed node \"" + nodeId + "\"";
                } else {
                    description = "Could not re-remove node";
                }
            }
            case "connect" -> {
                // Re-add the connection
                if (data != null) {
                    session.getEdges().add(new LinkedHashMap<>(data));
                    description = "Re-added connection " + data.get("from") + " → " + data.get("to");
                } else {
                    description = "Could not re-add connection";
                }
            }
            case "disconnect" -> {
                // Re-remove the connection
                if (data != null) {
                    String from = (String) data.get("from");
                    String to = (String) data.get("to");
                    session.removeConnection(from, to);
                    description = "Re-removed connection " + from + " → " + to;
                } else {
                    description = "Could not re-remove connection";
                }
            }
            case "modify" -> {
                // Re-apply new values
                if (data != null && nodeId != null) {
                    Map<String, Object> newValues = (Map<String, Object>) data.get("new_values");
                    Map<String, Object> node = findNodeById(session, nodeId);
                    if (node != null && newValues != null) {
                        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
                            node.put(entry.getKey(), entry.getValue());
                        }
                        description = "Re-applied changes to \"" + nodeId + "\"";
                    } else {
                        description = "Could not re-apply changes";
                    }
                    // Re-apply connect_after edge changes
                    List<Map<String, Object>> oldIncomingEdges = (List<Map<String, Object>>) data.get("old_incoming_edges");
                    String newConnectAfter = (String) data.get("new_connect_after");
                    if (oldIncomingEdges != null && newConnectAfter != null) {
                        // Remove old incoming edges again
                        session.getEdges().removeIf(edge -> nodeId.equals(edge.get("to")));
                        // Re-create the connect_after edge
                        session.addConnection(newConnectAfter, nodeId, null);
                    }
                } else {
                    description = "Could not re-apply changes";
                }
            }
            default -> {
                description = "Redone action: " + actionType;
            }
        }

        // Push back to action history
        session.getActionHistory().add(action);
        sessionStore.save(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", description);
        result.put("redone_action", actionType);
        result.put("can_redo", session.canRedo());

        return ToolExecutionResult.success(result);
    }

    // ==================== Helper Methods ====================

    private String formatNodeRef(WorkflowBuilderSession session, String nodeId) {
        // Use session's formatNodeRefWithLabel which uses getLogicalIdOrFail
        return session.formatNodeRefWithLabel(nodeId);
    }

    private String formatAvailableNodes(WorkflowBuilderSession session) {
        List<String> formatted = new ArrayList<>();
        for (String nodeId : session.getAllNodeIds()) {
            // Always use formatNodeRef to ensure consistent format: "Label" (type)
            formatted.add(session.formatNodeRef(nodeId, true));
        }
        return String.join(", ", formatted);
    }

    private String getNodeType(String nodeId) {
        if (LabelNormalizer.isTriggerKey(nodeId)) return LabelNormalizer.PREFIX_TRIGGER;
        if (LabelNormalizer.isMcpKey(nodeId)) return LabelNormalizer.PREFIX_MCP;
        if (LabelNormalizer.isAgentKey(nodeId)) return LabelNormalizer.PREFIX_AGENT;
        if (LabelNormalizer.isCoreKey(nodeId)) return LabelNormalizer.PREFIX_CORE;
        if (LabelNormalizer.isInterfaceKey(nodeId)) return LabelNormalizer.PREFIX_INTERFACE;
        if (LabelNormalizer.isTableKey(nodeId)) return LabelNormalizer.PREFIX_TABLE;
        if (LabelNormalizer.isNoteKey(nodeId)) return LabelNormalizer.PREFIX_NOTE;
        return "node";
    }

    private String computeNewNodeId(String oldNodeId, String newLabel) {
        String prefix = getNodeType(oldNodeId);
        String normalized = normalizeLabel(newLabel);
        return prefix + ":" + normalized;
    }

    private String normalizeLabel(String label) {
        if (label == null) return "";
        return label.toLowerCase()
                .replace(" ", "_")
                .replaceAll("[^a-z0-9_]", "");
    }

    private Map<String, Object> findNodeById(WorkflowBuilderSession session, String nodeId) {
        return session.findNode(nodeId).orElse(null);
    }

    /**
     * Validates a mock block against the node's REAL type and ports by parsing a
     * single-node mini plan through {@link com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser}
     * - the exact validator the engine uses at run time, so modify-time acceptance
     * and execution-time parsing can never disagree.
     *
     * @return a caller-facing error message, or null when the block is valid
     */
    private String validateMockAgainstNode(WorkflowBuilderSession session, String nodeId,
                                            Map<String, Object> node, Map<String, Object> mockMap) {
        String section;
        if (LabelNormalizer.isMcpKey(nodeId)) {
            section = "mcps";
        } else if (LabelNormalizer.isAgentKey(nodeId)) {
            section = "agents";
        } else if (LabelNormalizer.isCoreKey(nodeId)) {
            section = "cores";
        } else if (LabelNormalizer.isTableKey(nodeId)) {
            section = "tables";
        } else if (LabelNormalizer.isInterfaceKey(nodeId)) {
            section = "interfaces";
        } else {
            return "Mocking is not available on this node kind (" + nodeId + ").";
        }
        Map<String, Object> nodeCopy = new LinkedHashMap<>(node);
        nodeCopy.put(com.apimarketplace.orchestrator.domain.workflow.NodeMock.JSON_KEY, mockMap);
        Map<String, Object> miniPlan = new LinkedHashMap<>();
        miniPlan.put(section, List.of(nodeCopy));
        try {
            com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser
                    .parse(miniPlan, session.getTenantId());
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage() + " See workflow(action='help', topics=['mocking']).";
        }
    }

    /** One-word mock kind for the modify report: output | catalog_example | error | port. */
    private static String describeMockKind(Map<String, Object> mockMap) {
        Object source = mockMap.get("source");
        if ("catalog_example".equals(source)) return "catalog_example";
        if ("error".equals(source) || mockMap.containsKey("error")) return "error";
        if (mockMap.containsKey("output")) return "output";
        if (mockMap.containsKey("port")) return "port";
        return "output";
    }

    private void restoreNode(WorkflowBuilderSession session, String nodeId, Map<String, Object> nodeData) {
        if (LabelNormalizer.isTriggerKey(nodeId)) {
            session.getTriggers().add(new LinkedHashMap<>(nodeData));
        } else if (LabelNormalizer.isMcpKey(nodeId) || LabelNormalizer.isAgentKey(nodeId)) {
            session.getMcps().add(new LinkedHashMap<>(nodeData));
        } else if (LabelNormalizer.isCoreKey(nodeId)) {
            session.getCores().add(new LinkedHashMap<>(nodeData));
        } else if (LabelNormalizer.isInterfaceKey(nodeId)) {
            session.getInterfaces().add(new LinkedHashMap<>(nodeData));
        } else if (LabelNormalizer.isTableKey(nodeId)) {
            session.getTables().add(new LinkedHashMap<>(nodeData));
        } else if (LabelNormalizer.isNoteKey(nodeId)) {
            session.getNotes().add(new LinkedHashMap<>(nodeData));
        }
    }

    // ─── Nested config key mapping ───
    // Nodes with nested config: execution engine reads from node.get(configKey).get(paramKey),
    // NOT from node.get(paramKey). The modifier MUST route params into the nested sub-object.
    // Kept exhaustive against WorkflowPlanParser.parseConfigSafe(...) call sites - any node
    // type the parser reads from a nested key MUST appear here, otherwise modify deposits
    // patches at the top level of the node JSON, where the engine never reads them.
    public static final Map<String, String> NESTED_CONFIG_KEYS = Map.ofEntries(
        Map.entry("transform", "transform"),
        Map.entry("wait", "wait"),
        Map.entry("download_file", "download"),
        Map.entry("public_link", "params"),
        Map.entry("media", "params"),
        Map.entry("generate", "params"),
        Map.entry("http_request", "httpRequest"),
        Map.entry("response", "response"),
        Map.entry("aggregate", "aggregate"),
        Map.entry("filter", "filter"),
        Map.entry("sort", "sort"),
        Map.entry("limit", "limit"),
        Map.entry("remove_duplicates", "removeDuplicates"),
        Map.entry("summarize", "summarize"),
        Map.entry("date_time", "dateTime"),
        Map.entry("crypto_jwt", "cryptoJwt"),
        Map.entry("xml", "xml"),
        Map.entry("compression", "compression"),
        Map.entry("rss", "rss"),
        Map.entry("convert_to_file", "convertToFile"),
        Map.entry("extract_from_file", "extractFromFile"),
        Map.entry("compare_datasets", "compareDatasets"),
        Map.entry("sub_workflow", "subWorkflow"),
        Map.entry("respond_to_webhook", "respondToWebhook"),
        Map.entry("send_email", "sendEmail"),
        Map.entry("email_inbox", "emailInbox"),
        Map.entry("code", "code"),
        Map.entry("data_input", "dataInput"),
        Map.entry("set", "set"),
        Map.entry("approval", "approval"),
        Map.entry("html_extract", "htmlExtract"),
        Map.entry("task", "task"),
        Map.entry("stop_on_error", "stopOnError"),
        Map.entry("ssh", "ssh"),
        Map.entry("sftp", "sftp"),
        Map.entry("database", "database")
    );

    /**
     * Trigger types whose configuration lives inside {@code node.params}.
     * Modify routes flat LLM params into the existing params map for these,
     * mirroring the MCP routing pattern. Webhook is intentionally absent -
     * it has its own dedicated routing through {@code mergeIntoTriggerParams}.
     * Other trigger types (manual, datasource, workflow, error) store
     * everything top-level and don't need routing.
     */
    private static final Set<String> PARAMS_AWARE_TRIGGER_TYPES = Set.of(
        "schedule", "form", "chat"
    );

    /**
     * The fields the engine reads out of a table node's {@code crud} block.
     *
     * <p>Table CRUD nodes ({@code table:...}, types {@code crud-find},
     * {@code crud-update-row}, ...) are NOT in {@link #NESTED_CONFIG_KEYS}: their
     * routing is not "everything that is not top-level goes inside", because
     * {@code table_id} / {@code dataSourceId} legitimately live at the top level of
     * the node. Only these keys move.
     *
     * <p>This is the list {@code WorkflowPlanParser} merges into the crud block, which is
     * one key WIDER than {@code TableCreator}'s: the creation side omits
     * {@code similarity}, leaving it in {@code params}, where the parser picks it up by
     * its last fallback. Following the parser rather than the creator keeps a modify from
     * inheriting that asymmetry.
     *
     * <p>Kept in sync with the list {@code WorkflowPlanParser} merges into the crud
     * block. That parser is TOLERANT: it reads {@code crud.<key>} first and only
     * falls back to the node top level or to {@code params} when the crud block does
     * not carry the key. Tolerance plus a writer that targets the lowest-precedence
     * location is exactly how a modify reports success and changes nothing, so this
     * set is what keeps the writer aimed at the slot that wins.
     */
    /**
     * Every spelling of a table node's data source, in the order a caller is most likely
     * to write them. All of them canonicalise to {@code dataSourceId}.
     *
     * <p>{@code WorkflowPlanParser} reads {@code dataSourceId}, then {@code datasourceId},
     * then {@code table_id}, and never reads {@code tableId} or {@code datasource_id} at
     * all, though both are documented aliases the validator accepts. A node left carrying
     * only one of those two is pointing at nothing.
     */
    public static final List<String> DATA_SOURCE_ID_ALIASES =
        List.of("table_id", "tableId", "datasource_id", "datasourceId");

    public static final Set<String> CRUD_CONFIG_KEYS = Set.of(
        "rows", "where", "set", "columns", "limit", "offset", "similarity"
    );

    /** Serialises a parsed node for lookup. Stateless, so one instance is enough. */
    private static final com.fasterxml.jackson.databind.ObjectMapper VIEW_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    // Keys that are always top-level node properties, never routed into nested config
    public static final Set<String> TOP_LEVEL_NODE_KEYS = Set.of(
        "id", "label", "type", "description", "position"
    );

    /**
     * Drop any top-level key on {@code node} that duplicates an inner field
     * of the node's nested config slot. The engine reads only from
     * {@code node[nestedKey][innerField]}; a {@code node[innerField]} alongside
     * is a zombie left over from a past buggy modify call (or from a stale
     * patch the LLM sent with the wrong shape). Removing it keeps the node in
     * its canonical, single-source-of-truth shape and prevents future surprises
     * (export round-trips, diff readability, agent confusion).
     *
     * <p>Top-level keys in {@link #TOP_LEVEL_NODE_KEYS} are protected - names
     * like {@code label} can legitimately appear both at the top level and
     * inside the nested config (e.g. a {@code task.label} field is distinct
     * from the node's own label). The nested config slot key itself is also
     * protected. Everything else is fair game.
     */
    /**
     * Drop copies of a crud field that sit outside the crud block.
     *
     * <p>The parser reads {@code crud.<field>} first and falls back to the node top level
     * and then to {@code params}, so a copy left behind is dead weight that disagrees with
     * the live value and misleads the next reader. The creation path leaves one for
     * {@code similarity}, which it does not move into the block.
     *
     * <p>Only copies of fields the crud block ALREADY carries are removed: if the block
     * does not have the field, the outer copy is the one the parser is using and dropping
     * it would change behaviour rather than tidy it.
     */
    @SuppressWarnings("unchecked")
    static void scrubCrudDuplicates(Map<String, Object> node, String nodeId) {
        if (node == null || !LabelNormalizer.isTableKey(nodeId)) return;
        if (!(node.get("crud") instanceof Map)) return;
        Map<String, Object> crud = (Map<String, Object>) node.get("crud");
        Map<String, Object> params = node.get("params") instanceof Map
            ? (Map<String, Object>) node.get("params") : null;
        for (String field : CRUD_CONFIG_KEYS) {
            if (!crud.containsKey(field)) continue;
            node.remove(field);
            if (params != null) params.remove(field);
        }
    }

    /**
     * Drop the other spellings of the data source once the canonical one is set.
     *
     * <p>A node imported through {@code set_plan} can carry only {@code table_id}, which
     * the parser does read. Canonicalising a patch to {@code dataSourceId} without
     * clearing that left two ids on the node, the stale one still readable under the very
     * name the caller had used, so the read-back reported a SUCCESSFUL write as
     * NOT_APPLIED. Same rule as the crud scrub: only ever remove a copy once the value
     * that wins is present, never the one the parser is actually using.
     */
    @SuppressWarnings("unchecked")
    static void scrubStaleDataSourceIds(Map<String, Object> node, String nodeId) {
        if (node == null || !LabelNormalizer.isTableKey(nodeId)) return;
        if (node.get("dataSourceId") == null) return;
        Map<String, Object> params = node.get("params") instanceof Map
            ? (Map<String, Object>) node.get("params") : null;
        for (String alias : DATA_SOURCE_ID_ALIASES) {
            node.remove(alias);
            if (params != null) params.remove(alias);
        }
    }

    /**
     * Whether a patched key belongs at the TOP LEVEL of the node rather than inside
     * its config block.
     *
     * <p>{@code position} is the one key that is both. On every node it holds the
     * canvas coordinates {@code {x, y}}; on a media overlay it is also a real
     * parameter naming the anchor ({@code top_left}, {@code center}, ...). The shape
     * tells them apart: coordinates are always a Map, an anchor never is. Deciding on
     * the NAME alone wrote the string "top_left" into the coordinates and left behind
     * a plan the parser could no longer read at all ("class java.lang.String cannot
     * be cast to class java.util.Map"), after it had already been persisted, so the
     * workflow could not even be re-opened to repair it (verified 2026-09-02).
     */
    static boolean isAnchorNotCanvasPlacement(String key, Object value) {
        return "position".equals(key) && !(value instanceof Map);
    }

    /**
     * The key at which a patch would replace a whole config BLOCK with a scalar, or null.
     *
     * <p>{@code NodeFieldMerger} falls through to an unconditional REPLACE on a type
     * mismatch, so {@code params={crud: 'oops'}} answered success and left the node's
     * entire crud block as that string. {@code WorkflowPlanParser} casts it to a Map on
     * the next load, so the workflow could no longer be opened to repair itself: the same
     * corruption the {@code position} guard was written for, one field to the left, in
     * the routing this change set itself added.
     *
     * <p>The primary test is the SHAPE already on the node, and a GLOBAL list of block
     * names would be the wrong instrument: {@code limit} is both a node type whose config
     * block is called {@code limit} and an ordinary crud field, so such a list would
     * refuse {@code params={limit: 100}} on a table node, the very call this change set
     * exists to make work. The shape test carries no such ambiguity and covers block names
     * nobody has thought of yet.
     *
     * <p>It is backed by the blocks this node TYPE is known to have, which is not the same
     * thing as a global list: {@code crud} counts on a table node only, {@code limit} on a
     * node whose type is {@code limit} only. That half matters because a node imported
     * through {@code set_plan} can be missing the block entirely, a shape the parser
     * tolerates, and the shape test alone cannot see those.
     */
    static String blockReplacedByScalar(Map<String, Object> node, String nodeId,
                                        Map<String, Object> changes) {
        Set<String> blocks = new HashSet<>(NodeFieldMerger.MERGE_MAP_FIELDS);
        if (LabelNormalizer.isTableKey(nodeId)) blocks.add("crud");
        String type = (String) node.get("type");
        if (type != null && LabelNormalizer.isCoreKey(nodeId)) {
            String nested = NESTED_CONFIG_KEYS.get(type);
            if (nested != null) blocks.add(nested);
        }
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            Object patched = entry.getValue();
            if (patched == null || patched instanceof Map) continue;
            if (node.get(entry.getKey()) instanceof Map || blocks.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * What the ENGINE will read off this node, obtained by asking the engine's own parser
     * instead of re-implementing where it looks.
     *
     * <p>This replaces a hand-written model of the parser's precedence. That model was a
     * SECOND encoding of "where does the engine read this", maintained by hand next to the
     * real one, and five audit rounds each found a place where the two had drifted: the
     * crud block was not consulted at all, then only two of its three tiers, then the
     * data-source aliases had an order nobody had modelled, then webhook triggers stored
     * their fields somewhere the model had never heard of. Two encodings of one fact
     * drift; that is not a run of bad luck, it is the shape of the mechanism.
     *
     * <p>The node is parsed as a one-node plan, the same trick
     * {@code validateMockAgainstNode} already uses in this class for the same reason, and
     * the parsed object is flattened for lookup. After parsing there is exactly ONE home
     * per field, because resolving the tiers is what the parser did, so the flattening
     * needs no precedence rules of its own beyond preferring the typed config over the
     * raw params bag it was resolved from.
     *
     * <p>A node the parser refuses yields an empty view rather than an exception: the
     * read-back is a report, and a report must never be the reason a modify fails.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> engineView(Map<String, Object> node, String nodeId) {
        String section = planSectionFor(nodeId);
        if (section == null || node == null) return Map.of();
        Map<String, Object> miniPlan = new LinkedHashMap<>();
        miniPlan.put(section, List.of(new LinkedHashMap<>(node)));
        Object parsed;
        try {
            com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan plan =
                com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser
                    .parse(miniPlan, null);
            List<?> section_ = switch (section) {
                case "triggers" -> plan.getTriggers();
                case "mcps" -> plan.getMcps();
                case "cores" -> plan.getCores();
                case "tables" -> plan.getTables();
                default -> List.of();
            };
            if (section_.isEmpty()) return Map.of();
            parsed = section_.get(0);
        } catch (RuntimeException e) {
            log.debug("engineView: parser refused node {} ({}), reporting an empty view",
                nodeId, e.getMessage());
            return Map.of();
        }

        Map<String, Object> serialized;
        try {
            serialized = VIEW_MAPPER.convertValue(parsed, Map.class);
        } catch (RuntimeException e) {
            log.debug("engineView: could not serialise parsed node {}", nodeId, e);
            return Map.of();
        }

        // params is the raw bag the parser resolved FROM, so it goes in first and anything
        // typed overwrites it. Everything else is already canonical.
        Map<String, Object> view = new LinkedHashMap<>();
        Object rawParams = serialized.get("params");
        if (rawParams instanceof Map) putNonNull(view, (Map<String, Object>) rawParams);
        for (Map.Entry<String, Object> entry : serialized.entrySet()) {
            if ("params".equals(entry.getKey())) continue;
            if (entry.getValue() instanceof Map) putNonNull(view, (Map<String, Object>) entry.getValue());
        }
        for (Map.Entry<String, Object> entry : serialized.entrySet()) {
            if ("params".equals(entry.getKey()) || entry.getValue() instanceof Map) continue;
            if (entry.getValue() != null) view.put(entry.getKey(), entry.getValue());
        }
        return view;
    }

    private static void putNonNull(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() != null) target.put(entry.getKey(), entry.getValue());
        }
    }

    /** Which plan section a node id belongs in, for the one-node parse. */
    static String planSectionFor(String nodeId) {
        if (LabelNormalizer.isTriggerKey(nodeId)) return "triggers";
        if (LabelNormalizer.isMcpKey(nodeId)) return "mcps";
        if (LabelNormalizer.isCoreKey(nodeId)) return "cores";
        if (LabelNormalizer.isTableKey(nodeId)) return "tables";
        return null;
    }

    /**
     * The map the ENGINE reads this node's config from, which is not always where a
     * patch was written. Used to report before/after truthfully.
     *
     * <p>A report that echoes {@code node.get(key)} describes what the modifier did,
     * not what the workflow will do. That is what let a table CRUD patch answer
     * {@code modified_fields: ["limit"], after: 100} while execution went on reading
     * {@code crud.limit}, still 1. Reading back from the slot the engine consults is
     * what makes a silent no-op visible without having to anticipate it.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> effectiveConfig(Map<String, Object> node, String nodeId) {
        if (node == null) return Map.of();
        if (LabelNormalizer.isTableKey(nodeId) && node.get("crud") instanceof Map) {
            return (Map<String, Object>) node.get("crud");
        }
        String type = (String) node.get("type");
        String nestedKey = (type != null && LabelNormalizer.isCoreKey(nodeId))
            ? NESTED_CONFIG_KEYS.get(type) : null;
        if (nestedKey != null && node.get(nestedKey) instanceof Map) {
            return (Map<String, Object>) node.get(nestedKey);
        }
        boolean paramsSlot = (LabelNormalizer.isMcpKey(nodeId) && !Boolean.TRUE.equals(node.get("isAgent")))
            || (LabelNormalizer.isTriggerKey(nodeId) && PARAMS_AWARE_TRIGGER_TYPES.contains(type));
        if (paramsSlot && node.get("params") instanceof Map) {
            return (Map<String, Object>) node.get("params");
        }
        return node;
    }

    /**
     * Scalars the caller asked for that the engine does NOT read back.
     *
     * <p>A patch landing somewhere the executor never consults used to answer
     * "Node modified" and change nothing at run time, which is the most expensive kind
     * of wrong: it is believed, and discovered much later. This is the read-back that
     * makes such a gap announce itself without anyone having to predict which node
     * family will have it.
     *
     * <p>Two deliberate silences, because a detector that cries wolf is worse than none:
     * a Map or List patch is skipped, since merge semantics make a difference between
     * what was sent and what is stored expected; and a key the engine reads NOTHING for
     * is skipped, because that is indistinguishable from harmonization having renamed it
     * (table_id becomes dataSourceId, is_entry_interface becomes isEntryInterface, and a
     * dozen more). The case this must catch, a value that landed while the engine keeps
     * reading a DIFFERENT one under that same name, is exactly the case where the
     * read-back is non-null and disagrees.
     */
    static Map<String, Object> detectNotApplied(Map<String, Object> node, String nodeId,
                                                Map<String, Object> requested) {
        Map<String, Object> notApplied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            String key = entry.getKey();
            Object want = entry.getValue();
            if (want == null) continue;
            boolean structured = want instanceof Map || want instanceof List;
            // A field the merger OVERLAYS legitimately differs from what was sent; one it
            // REPLACES does not, so where/set/rows/columns are watched like a scalar. They
            // are the crud fields an agent touches most, and skipping every structured
            // value left them with no cover at all.
            if (structured && (NodeFieldMerger.MERGE_MAP_FIELDS.contains(key)
                    || NodeFieldMerger.MERGE_LIST_BY_LABEL_FIELDS.contains(key))) continue;
            Object actual = effectiveValue(node, nodeId, key);
            if (actual == null) continue;
            boolean landed = structured
                ? Objects.equals(LabelNormalizer.normalizeValueDeep(want), actual)
                : sameScalar(want, actual);
            if (!landed) {
                notApplied.put(key, "requested " + formatExample(want)
                    + " but the engine reads " + formatExample(actual));
            }
        }
        return notApplied;
    }

    /** Equal as the report means it: same text once both sides are rendered. */
    static boolean sameScalar(Object requested, Object actual) {
        if (requested == null || actual == null) return requested == actual;
        if (requested.equals(actual)) return true;
        // A patch arrives as JSON, so 100 can be an Integer where the stored value is a
        // Long or a Double, and "100" where it is a number. Comparing the rendered form
        // keeps the report from crying wolf on a value that did land.
        return String.valueOf(requested).equals(String.valueOf(actual));
    }

    /** Short, quotable rendering of a value for an agent-facing message. */
    static String formatExample(Object value) {
        if (value == null) return "nothing";
        String text = String.valueOf(value);
        if (text.length() > 80) text = text.substring(0, 77) + "...";
        return value instanceof String ? "'" + text + "'" : text;
    }

    /** The value the engine will read for {@code key}: config slot first, then the node. */
    static Object effectiveValue(Map<String, Object> node, String nodeId, String key) {
        Map<String, Object> view = engineView(node, nodeId);
        if (view.containsKey(key)) return view.get(key);
        // The parser knows the data source under ONE name, so an alias has to be asked
        // for under it. This is a spelling map, not a precedence rule: which spelling
        // wins is the parser's business and stays there.
        if (LabelNormalizer.isTableKey(nodeId) && DATA_SOURCE_ID_ALIASES.contains(key)
                && view.containsKey("dataSourceId")) {
            return view.get("dataSourceId");
        }
        // Anything the parsed object does not name: a config BLOCK the view flattened
        // away (params, actionMapping), or a field on a node the parser refused, which a
        // half-built one in an open session often is. Read the node as written rather
        // than reporting it unset. Deliberately dumb: no per-family precedence lives
        // here any more, since that duplication is what this helper exists to end.
        Map<String, Object> config = effectiveConfig(node, nodeId);
        if (config != node && config.containsKey(key)) return config.get(key);
        return node.get(key);
    }


    @SuppressWarnings("unchecked")
    static void scrubTopLevelOrphansFromNestedConfig(Map<String, Object> node) {
        if (node == null) return;
        String type = (String) node.get("type");
        if (type == null) return;
        String nestedKey = NESTED_CONFIG_KEYS.get(type);
        if (nestedKey == null) return;
        Object configObj = node.get(nestedKey);
        if (!(configObj instanceof Map)) return;
        Map<String, Object> config = (Map<String, Object>) configObj;
        for (String innerField : new java.util.ArrayList<>(config.keySet())) {
            if (TOP_LEVEL_NODE_KEYS.contains(innerField)) continue;
            if (innerField.equals(nestedKey)) continue;
            node.remove(innerField);
        }
    }

    /**
     * HARMONIZE: Convert ADD-style params to internal storage format.
     *
     * ADD uses user-friendly keys, storage uses internal keys:
     * - conditions → decisionConditions
     * - condition (in items) → expression
     * - listExpression → list (legacy alias)
     * - condition (for loop) → loopCondition
     *
     * For nodes with nested config (download_file, http_request, transform, etc.),
     * flat params are routed INTO the nested sub-object so the execution engine
     * can find them at node.get(configKey).get(paramKey).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> harmonizeParams(Map<String, Object> params, String nodeId, Map<String, Object> node) {
        Map<String, Object> harmonized = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Decision node: conditions -> decisionConditions (only for actual decision nodes)
            if ("conditions".equals(key) && LabelNormalizer.isCoreKey(nodeId) && isDecisionNode(node)) {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) value;
                List<Map<String, Object>> converted = new ArrayList<>();
                String normalizedLabel = LabelNormalizer.extractLabelFromKey(nodeId);
                int elseifIndex = 0;

                for (int i = 0; i < conditions.size(); i++) {
                    Map<String, Object> cond = conditions.get(i);
                    Map<String, Object> newCond = new LinkedHashMap<>();

                    // Get expression (accept multiple key names)
                    String expression = (String) cond.get("condition");
                    if (expression == null) expression = (String) cond.get("expression");
                    if (expression == null) expression = (String) cond.get("expr");

                    // Get label
                    String branchLabel = (String) cond.get("label");
                    if (branchLabel == null) branchLabel = (String) cond.get("name");

                    // Determine type: first = if, default = else, others = elseif
                    String condType;
                    if (i == 0) {
                        condType = "if";
                    } else if ("default".equalsIgnoreCase(expression) || "true".equals(expression)) {
                        condType = "else";
                        expression = "default"; // Normalize to "default"
                    } else {
                        condType = "elseif";
                    }

                    // Generate ID based on type
                    String condId;
                    if ("if".equals(condType)) {
                        condId = normalizedLabel + "-if";
                    } else if ("else".equals(condType)) {
                        condId = normalizedLabel + "-else";
                    } else {
                        condId = normalizedLabel + "-elseif-" + elseifIndex;
                        elseifIndex++;
                    }

                    // Allow override from input if provided
                    if (cond.get("id") != null && !((String) cond.get("id")).contains("undefined")) {
                        condId = (String) cond.get("id");
                    }
                    if (cond.get("type") != null) {
                        condType = (String) cond.get("type");
                    }

                    newCond.put("id", condId);
                    newCond.put("type", condType);
                    newCond.put("label", branchLabel != null ? branchLabel : (expression != null ? expression : "Branch"));
                    newCond.put("expression", expression != null ? expression : "default");

                    converted.add(newCond);
                }
                harmonized.put("decisionConditions", converted);
            }
            // Split node: listExpression -> list (legacy alias conversion)
            else if ("listExpression".equals(key) && LabelNormalizer.isCoreKey(nodeId)) {
                harmonized.put("list", value);
            }
            // Split node: list stays as list (canonical name)
            else if ("list".equals(key) && LabelNormalizer.isCoreKey(nodeId)) {
                harmonized.put("list", value);
            }
            // Loop node: condition / loop_condition → loopCondition
            else if (("condition".equals(key) || "loop_condition".equals(key))
                    && LabelNormalizer.isCoreKey(nodeId) && isLoopNode(node)) {
                harmonized.put("loopCondition", value);
            }
            // Loop node: max_iterations → maxIterations
            else if ("max_iterations".equals(key) && LabelNormalizer.isCoreKey(nodeId) && isLoopNode(node)) {
                harmonized.put("maxIterations", value);
            }
            // Switch node: cases → switchCases
            else if ("cases".equals(key) && LabelNormalizer.isCoreKey(nodeId)) {
                harmonized.put("switchCases", value);
            }
            // Switch node: expression → switchExpression
            else if ("expression".equals(key) && LabelNormalizer.isCoreKey(nodeId) && isSwitchNode(node)) {
                harmonized.put("switchExpression", value);
            }
            // Trigger: trigger_type → type
            else if ("trigger_type".equals(key) && LabelNormalizer.isTriggerKey(nodeId)) {
                harmonized.put("type", value);
            }
            // Agent node: agent_id / agentId → canonical TOP-LEVEL agentConfigId.
            //
            // Agent nodes store the agent-entity reference in the top-level
            // `agentConfigId` field. That field is the SINGLE source of truth:
            // it is written by AgentCreator, read by WorkflowPlanParser.parseAgents
            // at execution time (via AgentNode → AgentConfigResolver), AND read by
            // the builder's right-side panel, which keys on `agent-${agentConfigId}`.
            //
            // The LLM and the add_node contract use the friendly alias `agent_id`
            // (mirroring agent(action='create')). Without this branch a
            // modify(params={agent_id: X}) deep-merged X into params.agent_id
            // (a non-canonical echo that the parser drops on the next round-trip)
            // and NEVER touched agentConfigId - so execution kept resolving the
            // OLD agent (a deleted one → broken fallback) and the panel kept
            // showing it. Route the alias to the canonical field instead.
            else if (isAgentConfigAlias(key) && isAgentNode(node)) {
                Object current = node.get("agentConfigId");
                harmonized.put("agentConfigId", value);
                // The cached display fields (name/avatar) belonged to the PREVIOUS
                // agent. When the id actually changes, drop them so the panel and
                // runtime re-resolve fresh from the new entity instead of showing
                // a stale name. NodeFieldMerger treats a null value as a delete.
                if (value != null && !value.equals(current)) {
                    harmonized.put("agentConfigName", null);
                    harmonized.put("agentAvatarUrl", null);
                }
            }
            // Nested-config core nodes: rewrite an accepted alias to the ONE spelling the
            // matching config record carries. See NESTED_CONFIG_ALIASES.
            else if (LabelNormalizer.isCoreKey(nodeId)
                    && isNumericNestedField(node, key)
                    && value instanceof String) {
                // A quoted number reaching a field the parser reads with `instanceof Number` is
                // dropped at parse time and the node falls back to its default: green call, value
                // silently reverted. The creators coerce (CreatorBase.toLongOrNull) precisely
                // because LLM-written params quote numbers routinely; the edit path must match,
                // or the same value works on add_node and evaporates on modify.
                harmonized.put(key, coerceNestedConfigValue(node, key, value));
            }
            else if (LabelNormalizer.isCoreKey(nodeId)
                    && nestedConfigAliasFor(node, key) != null) {
                // A patch carrying two spellings of one field with different values was already
                // refused upstream (findAmbiguousAliasPatch).
                String canonical = nestedConfigAliasFor(node, key);
                harmonized.put(canonical, coerceNestedConfigValue(node, canonical, value));
            }
            // Webhook trigger params: merge into node.params map
            else if (isWebhookTriggerParam(key) && LabelNormalizer.isTriggerKey(nodeId) && isWebhookTrigger(node)) {
                mergeIntoTriggerParams(harmonized, node, normalizeWebhookParamKey(key), value);
            }
            // Interface node: snake_case → camelCase for consistency with InterfaceNodeConfig.toNodeMap()
            else if ("action_mapping".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("actionMapping", value);
            }
            else if ("variable_mapping".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("variableMapping", value);
            }
            else if ("interface_id".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("interfaceId", value);
            }
            else if ("is_entry_interface".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("isEntryInterface", value);
            }
            else if ("generate_screenshot".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("generateScreenshot", value);
            }
            else if ("expose_rendered_source".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("exposeRenderedSource", value);
            }
            else if ("generate_pdf".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("generatePdf", value);
            }
            else if ("pdf_format".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("pdfFormat", value);
            }
            else if ("pdf_landscape".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("pdfLandscape", value);
            }
            else if ("generate_video".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("generateVideo", value);
            }
            else if ("video_preset".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("videoPreset", value);
            }
            else if ("video_max_duration_seconds".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("videoMaxDurationSeconds", value);
            }
            else if ("video_mode".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("videoMode", value);
            }
            else if ("video_fps".equals(key) && LabelNormalizer.isInterfaceKey(nodeId)) {
                harmonized.put("videoFps", value);
            }
            // The display/capture format is no longer a node param: it belongs to the interface
            // itself. Drop the key instead of harmonising it, so a plan written before the move
            // keeps working and does not resurrect a param the node ignores. Setting the shape is
            // done with interface(action='update', interface_id='<uuid>', format='vertical').
            else if (("format".equals(key) || "interface_format".equals(key) || "interfaceFormat".equals(key))
                    && LabelNormalizer.isInterfaceKey(nodeId)) {
                // intentionally dropped
            }
            // Pass through: prompt, model, temperature, input, maxIterations, maxItems, etc.
            else {
                harmonized.put(key, value);
            }
        }

        // ─── Route flat params into nested config for nodes that use it ───
        // Nodes like download_file, http_request, transform, etc. store their config in a
        // nested sub-object (e.g., node.download.url). If the LLM sends flat params like
        // {url: "new"}, we must merge them into the nested config, not leave them at top level.
        // Guard: only applies to core: nodes, plus the one AI node that keeps a
        // nested config. MCP nodes with type="transform"/"wait" (from
        // __transform__/__wait__ tool IDs) use flat params and must NOT get nested routing.
        //
        // `generate` is addressed as `agent:<label>` but stores its whole
        // configuration under `params`, exactly as it did while it was a core
        // node. Left out of this guard, a modify on it deposits the patch at
        // the TOP level of the node, which the executor never reads: the call
        // reports success, the node keeps running its old model, and nothing
        // says so. The condition is named on the type rather than widened to
        // every agent: key, because the LLM agent nodes beside it do use flat
        // params.
        String nodeType = (String) node.get("type");
        boolean nestedCapable = LabelNormalizer.isCoreKey(nodeId)
            || (LabelNormalizer.isAgentKey(nodeId) && "generate".equals(nodeType));
        String nestedKey = (nodeType != null && nestedCapable)
            ? NESTED_CONFIG_KEYS.get(nodeType) : null;

        if (nestedKey != null) {
            // Check if the LLM sent the nested key directly (e.g., params={download: {url: "new"}})
            if (harmonized.containsKey(nestedKey) && harmonized.get(nestedKey) instanceof Map) {
                // Already correctly structured - merge with existing config
                Map<String, Object> existingConfig = node.get(nestedKey) instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) node.get(nestedKey))
                    : new LinkedHashMap<>();
                Map<String, Object> incomingConfig = (Map<String, Object>) harmonized.get(nestedKey);
                existingConfig.putAll(incomingConfig);
                harmonized.put(nestedKey, existingConfig);
            } else {
                // Flat params - separate top-level keys from nested config params
                Map<String, Object> nestedParams = new LinkedHashMap<>();
                Iterator<Map.Entry<String, Object>> it = harmonized.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Object> e = it.next();
                    if (!TOP_LEVEL_NODE_KEYS.contains(e.getKey())
                            || isAnchorNotCanvasPlacement(e.getKey(), e.getValue())) {
                        nestedParams.put(e.getKey(), e.getValue());
                        it.remove();
                    }
                }

                if (!nestedParams.isEmpty()) {
                    // Merge with existing nested config (preserve untouched fields)
                    Map<String, Object> existingConfig = node.get(nestedKey) instanceof Map
                        ? new LinkedHashMap<>((Map<String, Object>) node.get(nestedKey))
                        : new LinkedHashMap<>();
                    existingConfig.putAll(nestedParams);
                    harmonized.put(nestedKey, existingConfig);
                }
            }
        }

        // ─── Route flat CRUD params into node.crud for table nodes ───
        // Without this, params={limit: 100} lands at the TOP LEVEL of the node while
        // the engine keeps reading crud.limit, and the call still answers "Node
        // modified" (verified in production 2026-09-02: crud.limit stayed 1). The
        // parser's fallback hides it further, since it only consults the top level
        // when the crud block does not already carry the key: the same patch works or
        // silently does nothing depending on whether the field was ever set.
        if (LabelNormalizer.isTableKey(nodeId)) {
            Map<String, Object> crudParams = new LinkedHashMap<>();
            // A caller may also send the block already shaped, params={crud: {...}}.
            if (harmonized.get("crud") instanceof Map) {
                crudParams.putAll((Map<String, Object>) harmonized.remove("crud"));
            }
            for (String key : CRUD_CONFIG_KEYS) {
                if (harmonized.containsKey(key)) {
                    crudParams.put(key, harmonized.remove(key));
                }
            }
            // The data source is NOT a crud field, it sits at the top level as
            // dataSourceId, and every caller (and the help) names it table_id. Without
            // this, params={table_id: 300} was accepted and dropped: the node kept
            // pointing at the old table and nothing said so.
            // dataSourceId is the canonical name, so it is not treated as one of its own
            // aliases: sending both spellings with different values must not let the
            // alias silently win over the explicit one.
            // Between two aliases the FIRST in the documented order wins, rather than
            // whichever the loop happened to reach last.
            boolean explicitCanonical = harmonized.containsKey("dataSourceId");
            Object firstAlias = null;
            for (String alias : DATA_SOURCE_ID_ALIASES) {
                Object aliased = harmonized.remove(alias);
                if (aliased != null && firstAlias == null) firstAlias = aliased;
            }
            if (firstAlias != null && !explicitCanonical) {
                harmonized.put("dataSourceId", firstAlias);
            }
            if (!crudParams.isEmpty()) {
                // A null means delete here as it does everywhere else in this class;
                // putAll would leave a permanent null-valued key inside the block.
                Map<String, Object> existingCrud = node.get("crud") instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) node.get("crud"))
                    : new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : crudParams.entrySet()) {
                    if (e.getValue() == null) {
                        // Delete every tier, not just the block. The parser reads a crud
                        // field from the block, then the node, then params, so clearing
                        // only the block leaves the value the engine actually reads
                        // exactly where it was. On a field whose ONLY home is params
                        // (similarity, which the creator does not move into the block)
                        // that made the documented delete idiom a silent no-op, and the
                        // diff then read "before: stale, after: stale", i.e. nothing to
                        // see here, rather than "your delete did not happen".
                        existingCrud.remove(e.getKey());
                        node.remove(e.getKey());
                        if (node.get("params") instanceof Map) {
                            ((Map<String, Object>) node.get("params")).remove(e.getKey());
                        }
                    } else {
                        existingCrud.put(e.getKey(), e.getValue());
                    }
                }
                harmonized.put("crud", existingCrud);
            }
        }

        // ─── Route flat tool params into node.params for MCP nodes ───
        // MCP nodes (catalog tools like gmail.list_messages) store the tool's
        // arguments inside `node.params`, with `node.id` reserved for the
        // canonical catalog UUID. The LLM doesn't know this convention and
        // sends a flat object like:
        //     params={tool_id: "<new-uuid>", userId: "me", id: "{{messageId}}"}
        // Without routing, all of those would land at top level - `id` would
        // overwrite the canonical catalog UUID with the Gmail messageId
        // template, and userId would never reach execution.
        //
        // Convention applied here:
        //   tool_id           → canonical node.id (catalog UUID swap)
        //   label, position…  → top-level (TOP_LEVEL_NODE_KEYS)
        //   everything else   → merged into node.params via NodeFieldMerger
        // Skip when the node is a transform/wait sentinel (those are core
        // nodes that already went through the nested-config block above) and
        // when the node is an agent (agents store fields top-level).
        boolean isAgentNode = Boolean.TRUE.equals(node.get("isAgent"));
        // A node imported through set_plan can arrive without a `type` key at all, and
        // gating on an explicit "mcp" skipped the whole block for it: its selector
        // stayed undeletable and an incoming one was buried in params. The node id
        // already says it is an mcp step, so an ABSENT type is treated as one.
        boolean isMcpToolNode = LabelNormalizer.isMcpKey(nodeId)
                && !isAgentNode
                && (nodeType == null || "mcp".equals(nodeType));
        if (isMcpToolNode) {
            // Pull tool_id out of harmonized and treat it as a canonical id swap.
            // Done FIRST so the canonical id update survives the tool-param split below.
            Object pendingCanonicalIdSwap = null;
            if (harmonized.containsKey("tool_id")) {
                Object toolId = harmonized.remove("tool_id");
                if (toolId != null) pendingCanonicalIdSwap = toolId;
            }

            // Separate top-level metadata from tool params and pack the tool
            // params into a single `params` map. NodeFieldMerger will then
            // deep-merge that map into the existing node.params.
            //
            // Critical for MCP: `id` is NOT treated as top-level here. Many
            // catalog tools have a parameter literally named `id` (e.g. Gmail
            // get_message takes `id` = messageId). If we let it through as
            // top-level we would overwrite the canonical catalog UUID with
            // a Gmail messageId template. Use `tool_id` to swap the canonical
            // UUID instead.
            Set<String> mcpTopLevelStays = new HashSet<>(TOP_LEVEL_NODE_KEYS);
            mcpTopLevelStays.remove("id");
            // WHICH account the step runs on configures the STEP, it is not an
            // argument for the provider. Without these two the loop below would
            // bury it in params, where the plan parser never looks and from where
            // it WOULD be sent to the provider as an undeclared argument - the
            // same failure McpCreator.RESERVED_PARAMS exists to prevent on the add
            // half, arrived at from the modify side. The snake_case spelling is
            // normalised to the one the parser reads, so an agent can use either.
            mcpTopLevelStays.add("credentialSelector");
            // camelCase wins, but only when it actually carries a value: gating on
            // containsKey alone let {credentialSelector: null, credential_selector: "x"}
            // delete the selector here while add_node created it, which is one input
            // meaning two things on the two agent surfaces.
            if (harmonized.containsKey("credential_selector")
                    && harmonized.get("credentialSelector") == null) {
                harmonized.put("credentialSelector", harmonized.get("credential_selector"));
            }
            harmonized.remove("credential_selector");
            // Normalise the NODE's own spelling too. set_plan imports node maps
            // verbatim, so a session node can hold credential_selector - which the
            // plan parser reads as a live selector. The merger deletes by exact key,
            // so without this a delete reported success, removed a key that was not
            // there, and left the selector running. A rename, not a deletion: it
            // cannot lose a value even if the modify later fails validation.
            if (node.containsKey("credential_selector")) {
                Object existing = node.remove("credential_selector");
                node.putIfAbsent("credentialSelector", existing);
            }
            // Trimmed, the way the add surface trims, so the same input does not show
            // up as "   " through one door and "" through the other.
            // Stringified, the way the add surface stringifies: a number is a valid
            // value here (a credential id), and leaving it typed on one door only
            // makes the two surfaces differ over the same input.
            Object selectorValue = harmonized.get("credentialSelector");
            if (selectorValue != null) {
                harmonized.put("credentialSelector", String.valueOf(selectorValue).trim());
            }
            // An explicit null REMOVES the selector and returns the step to the
            // account picked in the builder; NodeFieldMerger already treats null as a
            // delete, so it is left in place for the merge rather than taken off the
            // node here. Mutating the node during harmonisation would delete it even
            // when the modify goes on to FAIL validation further down.
            //
            // A blank string is a different thing on purpose: the step is set to
            // choose at run time and the expression has not been written yet, which
            // fails the run rather than quietly using the default account. One value,
            // one meaning.

            Map<String, Object> mcpToolParams = new LinkedHashMap<>();
            Iterator<Map.Entry<String, Object>> it = harmonized.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                String k = e.getKey();
                if ("params".equals(k) || (mcpTopLevelStays.contains(k)
                        && !isAnchorNotCanvasPlacement(k, e.getValue()))) {
                    continue;
                }
                mcpToolParams.put(k, e.getValue());
                it.remove();
            }

            if (pendingCanonicalIdSwap != null) {
                harmonized.put("id", pendingCanonicalIdSwap);
            }

            if (!mcpToolParams.isEmpty()) {
                // If the LLM also sent params={...} explicitly, merge the
                // flat tool args into it so a single `params` entry reaches
                // NodeFieldMerger.
                Object explicitParams = harmonized.get("params");
                if (explicitParams instanceof Map) {
                    Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) explicitParams);
                    merged.putAll(mcpToolParams);
                    harmonized.put("params", merged);
                } else {
                    harmonized.put("params", mcpToolParams);
                }
            }
        }

        // ─── Route flat params into node.params for params-aware triggers ───
        // schedule (cron/timezone/enabled), form (formTitle/fields/...) and
        // chat (chatEndpointId) all store their config inside node.params.
        // Without routing, modify wipes the whole params map every time the
        // LLM updates one field. Webhook triggers are handled earlier by
        // mergeIntoTriggerParams; manual/datasource/workflow/error don't
        // have a params block so they stay top-level.
        boolean isParamsAwareTrigger = LabelNormalizer.isTriggerKey(nodeId)
                && PARAMS_AWARE_TRIGGER_TYPES.contains(nodeType);
        if (isParamsAwareTrigger) {
            Map<String, Object> scheduleParams = new LinkedHashMap<>();
            Iterator<Map.Entry<String, Object>> it = harmonized.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                String k = e.getKey();
                if ("params".equals(k) || TOP_LEVEL_NODE_KEYS.contains(k)) continue;
                scheduleParams.put(k, e.getValue());
                it.remove();
            }
            if (!scheduleParams.isEmpty()) {
                Object explicitParams = harmonized.get("params");
                if (explicitParams instanceof Map) {
                    Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) explicitParams);
                    merged.putAll(scheduleParams);
                    harmonized.put("params", merged);
                } else {
                    harmonized.put("params", scheduleParams);
                }
            }
        }

        return harmonized;
    }

    /**
     * Friendly aliases the LLM may use to point an agent node at its agent
     * entity. All of them are routed to the canonical top-level
     * {@code agentConfigId} field by {@link #harmonizeParams}.
     */
    private static final Set<String> AGENT_CONFIG_ID_ALIASES = Set.of(
        "agent_id", "agentId", "agent_config_id", "agentConfigId"
    );

    private boolean isAgentConfigAlias(String key) {
        return AGENT_CONFIG_ID_ALIASES.contains(key);
    }

    /**
     * True only for standard agent nodes (type {@code agent}). Browser agents
     * ({@code browser_agent}) and classify/guardrail nodes also carry
     * {@code isAgent=true} but have no {@code agentConfigId} - their config is
     * inline - so they must NOT get agent_id routing.
     */
    private boolean isAgentNode(Map<String, Object> node) {
        return "agent".equals(node.get("type"));
    }

    private boolean isDecisionNode(Map<String, Object> node) {
        String type = (String) node.get("type");
        return "decision".equals(type) || node.containsKey("decisionConditions");
    }

    private boolean isSwitchNode(Map<String, Object> node) {
        String type = (String) node.get("type");
        return "switch".equals(type) || node.containsKey("switchExpression");
    }

    private boolean isLoopNode(Map<String, Object> node) {
        String type = (String) node.get("type");
        return "loop".equals(type) || node.containsKey("loopCondition") || node.containsKey("maxIterations");
    }

    /**
     * Alias spellings that {@code add_node} accepts, mapped to the ONE spelling the matching
     * config record carries, for the node types listed here.
     *
     * <p>Why this is needed only on the EDIT path: a creator normalizes its aliases on the way
     * in, so {@code add_node} has never had the problem. {@code modify}, by contrast, deposits
     * flat params into the nested config VERBATIM, so a patch written in an accepted alias
     * landed under a key nothing reads: the call reported success and the run kept its old
     * value, with no error anywhere. Several of these aliases are the ones the nodes' own
     * {@code node_type_documentation} advertises, so following the help was a way to hit it.
     * Storage is normalized rather than the readers widened, because the builder canvas reads
     * these configs too and only knows the canonical spelling.
     *
     * <p><b>Scope: three node types, not the whole class.</b> The same defect exists on other
     * nested-config types: four of them are pinned by
     * {@code NestedConfigAliasInvariantTest.outOfScopeTypesAreDeclaredNotForgotten} (the ones
     * whose aliases {@code PARAM_ALIASES} declares), and more are reachable only through the
     * per-type help, so that assertion is a floor and not a census. It is NOT fixed here,
     * deliberately, because a mechanical sweep is unsafe: an accepted spelling is only sometimes
     * a pure rename, and the other two kinds cannot be told apart without reading each creator.
     * <ul>
     *   <li><b>Pure rename</b> (safe, and all this table contains): the creator reads the alias
     *       and stores the value unchanged, e.g. {@code auth_type} into {@code authType}.</li>
     *   <li><b>Shape transform</b> (renaming CORRUPTS the plan): {@code outputs} on transform and
     *       aggregate is read as an object {@code {name: expr}} and converted to a list. Renaming
     *       the key alone parks an object where a list is expected, overwrites the real mappings,
     *       and the parser then fails on the WHOLE plan, not just that node.</li>
     *   <li><b>Value transform</b> (renaming changes the meaning): {@code seconds} on wait is
     *       multiplied by 1000 into {@code duration}; the key would move and the unit would
     *       silently change.</li>
     * </ul>
     * So widening this table is a per-alias reading job with its own tests, not a generated list.
     * Sources to work from: the creator's read for the alias, the matching {@code Core.*Config}
     * component, and {@code NodeParamsValidator.PARAM_ALIASES} for what add_node already accepts.
     *
     * <p>Within its three types the table IS complete, and the exclusions it applies are:
     * <ul>
     *   <li>an alias that is itself a component of that node's config record, so it already has
     *       its own meaning there ({@code timeout} on http_request);</li>
     *   <li>an alias equal to the node's own nested key, which would collide with the "caller
     *       sent the whole config object" path. That path ({@code params={approval: {...}}}) is
     *       merged verbatim and is NOT covered by this table or by the numeric coercion, so an
     *       alias written inside the object still lands unread. Send flat params to get the
     *       rewrite;</li>
     *   <li>an alias an earlier branch of {@code harmonizeParams} already claims. Three of those
     *       branches are node-type-blind ({@code list}, {@code listExpression}, {@code cases}), so
     *       {@code list} on a filter node still lands unread whatever this table says. That is a
     *       separate pre-existing defect, not widened here.</li>
     *   <li>an alias that is a legitimate node-level field ({@code TOP_LEVEL_NODE_KEYS}), because
     *       rewriting it would remove the ability to set it.</li>
     * </ul>
     * {@code NestedConfigAliasInvariantTest} re-checks these by reflection, so an entry that
     * starts overwriting a real field fails the build.
     *
     * <p>Keyed by node type, never flat: the same word means different things on different
     * nodes. {@code timeout} is the approval's expiry and http_request's own request timeout;
     * {@code source} is a download URL and an html_extract input.
     *
     * <p>Note that {@code modify} runs none of the creators' cross-field checks (http_request's
     * creator refuses {@code authType='bearer'} without a matching {@code authConfig};
     * {@code modify} does not). Making an alias land where it was meant to therefore makes an
     * already-existing asymmetry reachable through one more spelling. That gap is older and
     * wider than this table and is not addressed here.
     */
    private static final Map<String, Map<String, String>> NESTED_CONFIG_ALIASES = Map.ofEntries(
        Map.entry("approval", Map.ofEntries(Map.entry("approver_roles", "approverRoles"), Map.entry("context_template", "contextTemplate"), Map.entry("continuation_mode", "continuationMode"), Map.entry("required_approvals", "requiredApprovals"), Map.entry("roles", "approverRoles"), Map.entry("timeout", "timeoutMs"), Map.entry("timeout_ms", "timeoutMs"))),
        Map.entry("download_file", Map.ofEntries(Map.entry("file_name", "filename"), Map.entry("file_url", "url"), Map.entry("href", "url"), Map.entry("link", "url"), Map.entry("output", "filename"), Map.entry("source", "url"), Map.entry("src", "url"))),
        Map.entry("http_request", Map.ofEntries(Map.entry("auth_config", "authConfig"), Map.entry("auth_type", "authType"), Map.entry("body_type", "bodyType"), Map.entry("endpoint", "url"), Map.entry("query_params", "queryParams"), Map.entry("uri", "url")))
    );

    /**
     * The message to fail with when one patch carries several accepted spellings of the same
     * field, or null when it does not.
     *
     * <p>The alias rewrite below is a per-key rename, so two spellings of one field would both
     * write the same destination and the survivor would be whichever the map happened to yield
     * last. Rejecting is the honest answer: the caller asked for two values for one field and
     * only it knows which it meant.
     */
    private String findAmbiguousAliasPatch(Map<String, Object> changes, String nodeId,
                                           Map<String, Object> node) {
        if (changes.isEmpty() || !LabelNormalizer.isCoreKey(nodeId)) return null;
        String type = (String) node.get("type");
        if (type == null) return null;
        Map<String, String> aliases = NESTED_CONFIG_ALIASES.getOrDefault(type, Map.of());
        if (aliases.isEmpty()) return null;

        Map<String, List<String>> byDestination = new LinkedHashMap<>();
        for (String key : changes.keySet()) {
            String destination = aliases.get(key);
            // The canonical spelling counts too: {timeoutMs: 1, timeout_ms: 2} is the same clash.
            if (destination == null && aliases.containsValue(key)) destination = key;
            if (destination != null) {
                byDestination.computeIfAbsent(destination, k -> new ArrayList<>()).add(key);
            }
        }
        for (Map.Entry<String, List<String>> e : byDestination.entrySet()) {
            List<String> sent = e.getValue();
            // Same field twice with the SAME value asks for one thing, so let it through: there is
            // nothing to disambiguate and refusing it would fail a call that used to work. Compare
            // AFTER coercion, or 3600000 and "3600000" would read as a conflict although the very
            // next step makes them identical.
            String field = e.getKey();
            long distinct = sent.stream()
                .map(k -> coerceNestedConfigValue(node, field, changes.get(k)))
                // Compare numbers by their text, not by identity: coercion yields a Long while
                // the caller may have sent an Integer, and Integer.equals(Long) is false.
                .map(v -> v instanceof Number n ? n.toString() : v)
                .distinct().count();
            if (sent.size() > 1 && distinct > 1) {
                return "Ambiguous change: " + String.join(" and ", sent) + " are two names for the "
                    + "same field on this node and this patch gives them different values. "
                    + "Send only one of them.";
            }
        }
        return null;
    }

    /**
     * Nested-config fields the plan parser reads with {@code instanceof Number}, so a quoted
     * number there is not a lax value but a lost one: the parser drops it and the field falls
     * back to its default.
     *
     * <p>Keyed by node type for the same reason {@code NESTED_CONFIG_ALIASES} is: {@code timeout}
     * is http_request's own numeric field, and on an approval node it is an alias for
     * {@code timeoutMs}. A flat set would coerce the approval one in place and stop it ever
     * reaching the key the parser reads.
     */
    private static final Map<String, Set<String>> NUMERIC_NESTED_CONFIG_FIELDS = Map.of(
        "approval", Set.of("timeoutMs", "requiredApprovals"),
        "http_request", Set.of("timeout")
    );

    /** Numeric nested fields whose config component is an {@code Integer}, not a {@code Long}. */
    private static final Set<String> INT_RANGE_NESTED_FIELDS = Set.of("timeout", "requiredApprovals");

    private boolean isNumericNestedField(Map<String, Object> node, String key) {
        String type = (String) node.get("type");
        return type != null && NUMERIC_NESTED_CONFIG_FIELDS.getOrDefault(type, Set.of()).contains(key);
    }

    /** A numeric string on a numeric field becomes a number; everything else passes through. */
    private Object coerceNestedConfigValue(Map<String, Object> node, String canonical, Object value) {
        if (!isNumericNestedField(node, canonical) || !(value instanceof String s)) {
            return value;
        }
        try {
            long parsed = Long.parseLong(s.trim());
            // Some of these fields are Integer-typed and their readers call intValue(), which
            // WRAPS rather than clamps: an out-of-range value would become a negative timeout.
            // Leave it as sent so the reader falls back to the default, as it did before.
            if (parsed > Integer.MAX_VALUE || parsed < Integer.MIN_VALUE) {
                return INT_RANGE_NESTED_FIELDS.contains(canonical) ? value : parsed;
            }
            return parsed;
        } catch (NumberFormatException e) {
            // Not a number: stored exactly as sent. A {{...}} reference is set aside by the plan
            // parser and resolved by the node at run time, which fails naming the field when it
            // resolves to nothing or to a non-number. A typo reaches the same run-time failure
            // only when it is a reference; a plain non-numeric string is still ignored by the
            // parser, as before this rewrite existed.
            return value;
        }
    }

    /**
     * Remove every spelling this patch supersedes from the node's stored nested config, so one
     * field is never held under two keys. Only ever removes a key the rewrite just replaced, and
     * {@code noAliasShadowsARealField} guarantees such a key is never a component of the config
     * record, so nothing a reader uses can be deleted here.
     */
    @SuppressWarnings("unchecked")
    private void dropSupersededSpellings(Map<String, Object> sent, String nodeId,
                                         Map<String, Object> node, Map<String, Object> patch) {
        if (sent.isEmpty() || !LabelNormalizer.isCoreKey(nodeId)) return;
        String type = (String) node.get("type");
        if (type == null) return;
        String nestedKey = NESTED_CONFIG_KEYS.get(type);
        Map<String, String> typeAliases = NESTED_CONFIG_ALIASES.getOrDefault(type, Map.of());
        if (nestedKey == null || typeAliases.isEmpty()) return;
        // The nested config was REBUILT during harmonisation, so the copy about to be merged is
        // the one to clean: scrubbing the node's own map here would just be overwritten.
        if (!(patch.get(nestedKey) instanceof Map<?, ?> raw)) return;

        Map<String, Object> config = (Map<String, Object>) raw;
        for (String key : sent.keySet()) {
            // The field this patch is setting, whether the caller spelled it as an alias or as
            // the canonical name. Every OTHER spelling of that field is now stale.
            String canonical = typeAliases.getOrDefault(key, key);
            typeAliases.forEach((alias, target) -> {
                if (target.equals(canonical)) config.remove(alias);
            });
        }
    }

    /**
     * The canonical spelling {@code key} should be rewritten to for this node, or null when the
     * node type declares no alias for it (so the key is left exactly as sent).
     */
    private String nestedConfigAliasFor(Map<String, Object> node, String key) {
        // No fallback on the presence of the config object: the nested routing is itself driven
        // by node.type (NESTED_CONFIG_KEYS), so a typeless node never reaches its nested config
        // at all and renaming its keys here would fix nothing.
        String type = (String) node.get("type");
        if (type == null) return null;
        return NESTED_CONFIG_ALIASES.getOrDefault(type, Map.of()).get(key);
    }

    private boolean isWebhookTrigger(Map<String, Object> node) {
        return "webhook".equals(node.get("type"));
    }

    private static final Set<String> WEBHOOK_PARAM_KEYS = Set.of(
        "httpMethod", "http_method", "method",
        "authType", "auth_type",
        "basicUsername", "basic_username", "username",
        "basicPassword", "basic_password", "password",
        "authHeaderName", "auth_header_name", "headerName",
        "authHeaderValue", "auth_header_value", "headerValue",
        "jwtSecretKey", "jwt_secret_key", "secretKey",
        "jwtAlgorithm", "jwt_algorithm", "algorithm"
    );

    private boolean isWebhookTriggerParam(String key) {
        return WEBHOOK_PARAM_KEYS.contains(key);
    }

    private String normalizeWebhookParamKey(String key) {
        return switch (key) {
            case "http_method", "method" -> "httpMethod";
            case "auth_type" -> "authType";
            case "basic_username", "username" -> "basicUsername";
            case "basic_password", "password" -> "basicPassword";
            case "auth_header_name", "headerName" -> "authHeaderName";
            case "auth_header_value", "headerValue" -> "authHeaderValue";
            case "jwt_secret_key", "secretKey" -> "jwtSecretKey";
            case "jwt_algorithm", "algorithm" -> "jwtAlgorithm";
            default -> key;
        };
    }

    @SuppressWarnings("unchecked")
    private void mergeIntoTriggerParams(Map<String, Object> harmonized, Map<String, Object> node,
                                         String paramKey, Object value) {
        // Get or create the params map in harmonized output
        Map<String, Object> params;
        if (harmonized.containsKey("params")) {
            params = (Map<String, Object>) harmonized.get("params");
        } else {
            // Start from existing node params
            Map<String, Object> existingParams = (Map<String, Object>) node.get("params");
            params = existingParams != null ? new LinkedHashMap<>(existingParams) : new LinkedHashMap<>();
            harmonized.put("params", params);
        }

        // Apply normalization for specific fields
        if ("httpMethod".equals(paramKey) && value instanceof String s) {
            params.put(paramKey, s.toUpperCase());
        } else if ("authType".equals(paramKey) && value instanceof String s) {
            params.put(paramKey, s.toLowerCase());
        } else {
            params.put(paramKey, value);
        }
    }

    /**
     * Validate action_mapping references point to existing triggers/interfaces.
     * Returns list of warning strings for invalid references, or empty list if all valid.
     */
    @SuppressWarnings("unchecked")
    private List<String> validateActionMappingReferences(Object actionMappingObj, WorkflowBuilderSession session) {
        if (!(actionMappingObj instanceof Map)) return List.of();
        Map<String, String> actionMapping;
        try {
            actionMapping = (Map<String, String>) actionMappingObj;
        } catch (ClassCastException e) {
            return List.of();
        }

        Set<String> existingTriggerKeys = new HashSet<>();
        for (Map<String, Object> trigger : session.getTriggers()) {
            Object label = trigger.get("label");
            if (label != null) {
                existingTriggerKeys.add("trigger:" + WorkflowBuilderSession.normalizeLabel(label.toString()));
            }
        }

        Set<String> existingInterfaceKeys = new HashSet<>();
        for (Map<String, Object> iface : session.getInterfaces()) {
            Object label = iface.get("label");
            if (label != null) {
                existingInterfaceKeys.add("interface:" + WorkflowBuilderSession.normalizeLabel(label.toString()));
            }
        }

        List<String> invalidRefs = new ArrayList<>();
        for (Map.Entry<String, String> entry : actionMapping.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.startsWith("__")) continue;

            String[] parts = value.split(":");
            if (parts.length < 3) continue;

            String prefix = parts[0];
            String label = parts[1];

            // Same rule as the add_node validator, from the same place: this path is the
            // one the agent is told to use to FIX a mapping, so a private copy that
            // drifts from the create path is how an agent ends up "repairing" correct
            // data. See ActionMappingRefs.
            if (ActionMappingRefs.isNavigate(parts)) {
                if (!existingInterfaceKeys.contains(ActionMappingRefs.targetInterfaceKey(parts))) {
                    invalidRefs.add(entry.getKey() + " -> " + value + " (interface '" + label + "' not found)");
                }
                continue;
            }

            String nodeKey = prefix + ":" + label;
            if ("trigger".equals(prefix) && !existingTriggerKeys.contains(nodeKey)) {
                invalidRefs.add(entry.getKey() + " -> " + value + " (trigger '" + label + "' not found)");
            } else if ("interface".equals(prefix) && !existingInterfaceKeys.contains(nodeKey)) {
                invalidRefs.add(entry.getKey() + " -> " + value + " (interface '" + label + "' not found)");
            }
        }

        if (!invalidRefs.isEmpty()) {
            log.warn("[MODIFY] action_mapping has unresolved references: {}", invalidRefs);
        }

        return invalidRefs;
    }

    /**
     * Extract trigger labels already flagged as non-existent from existing warnings.
     * Used to avoid double warnings (non-existent + cross-DAG) for the same trigger.
     */
    private static Set<String> extractFlaggedTriggerLabels(List<String> existingWarnings) {
        Set<String> labels = new HashSet<>();
        for (String warning : existingWarnings) {
            int triggerIdx = warning.indexOf("trigger '");
            if (triggerIdx >= 0) {
                int start = triggerIdx + "trigger '".length();
                int end = warning.indexOf("'", start);
                if (end > start) {
                    labels.add(warning.substring(start, end));
                }
            }
        }
        return labels;
    }
}
