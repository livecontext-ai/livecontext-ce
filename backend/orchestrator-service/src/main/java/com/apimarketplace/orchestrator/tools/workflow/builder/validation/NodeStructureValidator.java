package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderConnectionManager;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderModifier;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structural integrity checks on each node's JSON shape.
 *
 * <p>Catches corruption that the engine would silently swallow at runtime:
 *
 * <ul>
 *   <li><b>NODE_DUAL_WRITE</b> - a core node has BOTH its nested config slot
 *       (e.g. {@code set.assignments}) AND a top-level key with the same inner
 *       field name (e.g. {@code assignments}). The engine reads only from the
 *       nested slot; the top-level twin is a ghost the LLM wrote in the wrong
 *       shape. Surfacing it lets the agent (or the human) realize the patch
 *       didn't land where they expected.</li>
 *   <li><b>NESTED_TEMPLATE</b> - a string value embeds one {@code &#123;&#123;…&#125;&#125;}
 *       inside another. The template engine resolves a SINGLE pass; an outer
 *       template wrapping an inner template either parses as broken SpEL or
 *       silently produces unexpected output. Either way the agent meant a
 *       different shape.</li>
 * </ul>
 *
 * <p>Both checks emit {@link ValidationResult#addWarning warnings}, not errors:
 * an existing prod workflow may already carry these (the dual-write bug class
 * has been live), and a hard failure would block re-saves. The agent-facing
 * payload still includes them via {@code agentWarnings} so the LLM sees what's
 * wrong.
 */
@Slf4j
@Component
public class NodeStructureValidator implements WorkflowValidator {

    // {{... {{ ... - an opening {{ that contains another {{ before it ever sees a }}.
    // Trivial sequential templates like {{a}}{{b}} are fine: after the first {{ we
    // hit `}` (in `}}`) before any second `{{`, so the regex won't match. Anything
    // wrapping one template inside another, however many layers deep, is caught.
    private static final Pattern NESTED_TEMPLATE = Pattern.compile("\\{\\{[^}]*\\{\\{");

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        checkTerminalNodeOutgoingEdges(session, result);
        for (Map<String, Object> node : session.getCores()) {
            String nodeId = stringOrNull(node.get("id"));
            checkDualWrite(node, nodeId, result);
            checkNestedTemplates(node, nodeId, result);
        }
        // MCP / interface / table / trigger nodes can also carry nested templates;
        // dual-write only applies to core nodes since they're the only ones with
        // a nested config slot governed by NESTED_CONFIG_KEYS.
        for (Map<String, Object> node : session.getMcps()) {
            checkNestedTemplates(node, stringOrNull(node.get("id")), result);
        }
        for (Map<String, Object> node : session.getInterfaces()) {
            checkNestedTemplates(node, stringOrNull(node.get("id")), result);
        }
        for (Map<String, Object> node : session.getTables()) {
            checkNestedTemplates(node, stringOrNull(node.get("id")), result);
        }
    }

    /**
     * Terminal nodes ({@code exit}, {@code end}, {@code stop_on_error}) MUST NOT
     * have outgoing edges. Connecting one as the {@code from} of an edge - for
     * example, exit → merge - either dead-ends silently or, worse, leaves a
     * downstream merge waiting forever for a predecessor that cannot fire.
     *
     * <p>The connect action refuses these edges upfront (see
     * {@link WorkflowBuilderConnectionManager#executeConnect}), but a stale plan
     * may already carry one from before the rule existed, or from a direct edit
     * outside the connect path. This check surfaces those as ERRORS (not
     * warnings) so {@code validate}, {@code finish}, and {@code save} all flag
     * them with the same actionable verdict.
     */
    private void checkTerminalNodeOutgoingEdges(WorkflowBuilderSession session, ValidationResult result) {
        java.util.Set<String> terminalNodeIds = new java.util.HashSet<>();
        for (Map<String, Object> node : session.getCores()) {
            if (WorkflowBuilderConnectionManager.isTerminalCoreType(node)) {
                String nodeId = stringOrNull(node.get("id"));
                if (nodeId != null) terminalNodeIds.add(nodeId);
            }
        }
        if (terminalNodeIds.isEmpty()) return;
        for (Map<String, Object> edge : session.getEdges()) {
            String from = stringOrNull(edge.get("from"));
            String to = stringOrNull(edge.get("to"));
            if (from == null) continue;
            // An edge from a terminal node's port (e.g. "core:exit_x:if") still
            // counts - strip the port suffix to match the bare nodeId via the
            // single source of truth (EdgeRefParser). Terminal nodes have no
            // ports in practice, but a defensive strip keeps the check correct
            // if someone hand-edits the plan.
            String bareFrom = EdgeRefParser.splitPort(from)[0];
            if (terminalNodeIds.contains(from) || terminalNodeIds.contains(bareFrom)) {
                result.addError("TERMINAL_NODE_HAS_OUTGOING_EDGE", from,
                        "Terminal node '" + from + "' must have NO outgoing edges, but is "
                                + "connected to '" + to + "'. Terminal types (exit, end, "
                                + "stop_on_error) end a branch. Disconnect this edge, then "
                                + "either route the predecessor of '" + from + "' to '" + to
                                + "' directly, or replace the terminal with a non-terminal "
                                + "node if you need the flow to continue.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkDualWrite(Map<String, Object> node, String nodeId, ValidationResult result) {
        String type = stringOrNull(node.get("type"));
        if (type == null) return;
        String nestedKey = WorkflowBuilderModifier.NESTED_CONFIG_KEYS.get(type);
        if (nestedKey == null) return;
        Object configObj = node.get(nestedKey);
        if (!(configObj instanceof Map)) return;
        Map<String, Object> config = (Map<String, Object>) configObj;

        List<String> orphans = new ArrayList<>();
        for (String innerField : config.keySet()) {
            if (WorkflowBuilderModifier.TOP_LEVEL_NODE_KEYS.contains(innerField)) continue;
            if (innerField.equals(nestedKey)) continue;
            if (node.containsKey(innerField)) {
                orphans.add(innerField);
            }
        }
        if (!orphans.isEmpty()) {
            result.addWarning("NODE_DUAL_WRITE", nodeId,
                    "Node '" + nodeId + "' has top-level keys " + orphans
                            + " that shadow inner fields of '" + nestedKey + "'. "
                            + "The engine reads from '" + nestedKey + ".*' only; "
                            + "the top-level twins are ignored. Re-run workflow(action='modify', "
                            + "node='" + nodeId + "', params={" + nestedKey + ": {...}}) to repair, "
                            + "or remove+re-add the node to clear the orphans.");
        }
    }

    private void checkNestedTemplates(Map<String, Object> node, String nodeId, ValidationResult result) {
        List<String> hits = new ArrayList<>();
        scanForNestedTemplate(node, "", hits);
        if (!hits.isEmpty()) {
            result.addWarning("NESTED_TEMPLATE", nodeId,
                    "Node '" + nodeId + "' contains nested template syntax {{...{{...}}...}} at "
                            + hits + ". The template engine resolves a single pass - inner braces "
                            + "are not re-evaluated. Use a single {{path}} or move the logic into a "
                            + "code node.");
        }
    }

    @SuppressWarnings("unchecked")
    private void scanForNestedTemplate(Object value, String path, List<String> hits) {
        if (value instanceof String s) {
            if (NESTED_TEMPLATE.matcher(s).find()) hits.add(path);
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String childPath = path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey();
                scanForNestedTemplate(e.getValue(), childPath, hits);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scanForNestedTemplate(list.get(i), path + "[" + i + "]", hits);
            }
        }
    }

    private static String stringOrNull(Object v) {
        return v instanceof String s ? s : null;
    }
}
