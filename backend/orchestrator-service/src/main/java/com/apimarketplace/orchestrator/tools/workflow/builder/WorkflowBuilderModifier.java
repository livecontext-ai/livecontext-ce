package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.NodeMock;
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
     * Remove the last added node.
     */
    public ToolExecutionResult executeRemoveLast(WorkflowBuilderSession session, Map<String, Object> parameters) {
        Optional<WorkflowBuilderSession.SessionAction> lastActionOpt = session.getLastAction();
        if (lastActionOpt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "No actions in history. Nothing to remove.");
        }

        WorkflowBuilderSession.SessionAction lastAction = lastActionOpt.get();
        String nodeId = lastAction.getNodeId();

        if (nodeId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Last action '" + lastAction.getActionType() + "' did not create a node. Use workflow(action='undo') instead.");
        }

        // Delegate to remove with the node reference
        Map<String, Object> removeParams = new LinkedHashMap<>(parameters);
        removeParams.put("node", nodeId);
        return executeRemove(session, removeParams);
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
            // Allow modify with only connect_after or only mock (no params needed)
            String topLevelConnectAfter = (String) parameters.get("connect_after");
            if ((topLevelConnectAfter != null && !topLevelConnectAfter.isBlank())
                    || parameters.get("mock") != null) {
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

        if (rawChanges.isEmpty() && (connectAfterRef == null || connectAfterRef.isBlank()) && mockObj == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'params' object cannot be empty.");
        }

        // Find the node first to determine its type
        Map<String, Object> node = findNodeById(session, nodeId);
        if (node == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Node not found: " + nodeRef + ". Available: " + formatAvailableNodes(session));
        }

        // HARMONIZE: Convert ADD-style params to internal storage format
        Map<String, Object> changes = rawChanges.isEmpty() ? new LinkedHashMap<>() : harmonizeParams(rawChanges, nodeId, node);

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

        // Store old values for undo
        Map<String, Object> oldValues = new LinkedHashMap<>();
        for (String key : changes.keySet()) {
            oldValues.put(key, node.get(key));
        }
        if (mockChanged) {
            oldValues.put(NodeMock.JSON_KEY, oldMockValue);
        }

        // Check if label is changing - we need to update logical mappings
        String oldLabel = (String) node.get("label");
        String newLabel = (String) changes.get("label");
        boolean labelChanging = newLabel != null && !newLabel.equals(oldLabel);

        // Apply changes via the centralized merger so map fields (params,
        // actionMapping, …) and list fields (decisionConditions, switchCases,
        // classifyCategories) are MERGED with existing data instead of
        // blindly overwritten. The previous dumb apply loop wiped untouched
        // sub-fields and broke "modify one item" workflows for many node
        // types - see NodeFieldMerger javadoc for the full strategy.
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
        result.put("modified_fields", modifiedFields);

        // Show before/after for key fields
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String key : changes.keySet()) {
            Map<String, Object> fieldDiff = new LinkedHashMap<>();
            Object before = oldValues.get(key);
            // Show the ACTUAL post-merge value (node.get(key)), not the raw patch
            // the caller sent (changes.get(key)). For merge-strategy fields like
            // params / actionMapping the patch is only a partial overlay - echoing
            // it back made a key the caller OMITTED look removed when the merge had
            // in fact kept it (e.g. trying to drop AccountSid by re-sending the
            // other params). The diff must reflect the node's real state, otherwise
            // the agent believes a deletion happened and stops, leaving the stale
            // key in place. To actually delete a key, send params={key: null}.
            Object after = node.get(key);
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
     * Modify the last added node.
     */
    public ToolExecutionResult executeModifyLast(WorkflowBuilderSession session, Map<String, Object> parameters) {
        Optional<WorkflowBuilderSession.SessionAction> lastActionOpt = session.getLastAction();
        if (lastActionOpt.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "No actions in history. Add a node first.");
        }

        String nodeId = lastActionOpt.get().getNodeId();
        if (nodeId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Last action did not create a node. Use modify_node with explicit node reference.");
        }

        Map<String, Object> modifyParams = new LinkedHashMap<>(parameters);
        modifyParams.put("node", nodeId);
        return executeModifyNode(session, modifyParams);
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
                            node.put(entry.getKey(), entry.getValue());
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
        // Guard: only applies to core: nodes. MCP nodes with type="transform"/"wait" (from
        // __transform__/__wait__ tool IDs) use flat params and must NOT get nested routing.
        String nodeType = (String) node.get("type");
        String nestedKey = (nodeType != null && LabelNormalizer.isCoreKey(nodeId))
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
                    if (!TOP_LEVEL_NODE_KEYS.contains(e.getKey())) {
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
        boolean isMcpToolNode = LabelNormalizer.isMcpKey(nodeId)
                && !isAgentNode
                && "mcp".equals(nodeType);
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

            Map<String, Object> mcpToolParams = new LinkedHashMap<>();
            Iterator<Map.Entry<String, Object>> it = harmonized.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                String k = e.getKey();
                if ("params".equals(k) || mcpTopLevelStays.contains(k)) {
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
