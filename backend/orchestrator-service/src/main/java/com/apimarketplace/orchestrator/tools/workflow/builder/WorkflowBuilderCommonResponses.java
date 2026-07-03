package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.service.NodeLibraryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized common response elements for workflow init and load.
 * Ensures consistency between init and load responses.
 */
public final class WorkflowBuilderCommonResponses {

    private WorkflowBuilderCommonResponses() {}

    /**
     * Build the rules map - common between init and load.
     * @param includesTriggerFirst true for init (no trigger yet), false for load (already has trigger)
     */
    public static Map<String, Object> buildRules(boolean includesTriggerFirst) {
        Map<String, Object> rules = new LinkedHashMap<>();
        int idx = 1;

        rules.put(idx + "_SEQUENTIAL", "CRITICAL: Call workflow ONE AT A TIME. Wait for each result before the next call. Never batch multiple workflow calls in a single response.");
        idx++;

        if (includesTriggerFirst) {
            rules.put(idx + "_trigger_first", "Trigger must be created FIRST");
            idx++;
        }

        rules.put(idx + "_multi_trigger", "MULTI-TRIGGER: A workflow can have several triggers with UNIQUE labels. Triggers with NO overlapping descendants form independent DAGs. Triggers whose descendants overlap are auto-detected as ONE shared DAG - allowed. Each trigger still fires its OWN epoch; a shared sink (any node - transform/mcp/merge/interface/…) with multiple incoming trigger edges executes ONCE per trigger fire - the engine filters out the other trigger predecessors in this epoch. Interfaces additionally persist per-trigger UI state. Use workflow(action='help', topics=['multi_dag']) for details and examples.");
        idx++;
        rules.put(idx + "_agent_prerequisite", "AGENT nodes: Create entity first. (1) agent(action='create', name='...', system_prompt='...') to get UUID, (2) workflow(action='add_node', type='agent', label='...', params={agent_id:'<uuid>'}, connect_after='...'). Call workflow(action='help', topics=['agent']) for details.");
        idx++;
        rules.put(idx + "_interface_prerequisite", "INTERFACE nodes: Call interface(action='help') first. Then (1) interface(action='create') to get UUID, (2) workflow(action='add_node', type='interface', params={interface_id:'<uuid>'}).");
        idx++;
        rules.put(idx + "_connect_after", "Every node (except trigger) must have connect_after or it becomes orphan");
        idx++;
        rules.put(idx + "_connect_after_syntax", "connect_after is OUTSIDE params: workflow(action='add_node', type='...', label='X', params={...}, connect_after='Y')");
        idx++;
        rules.put(idx + "_data_flow", "Connection ≠ data access. Use {{type:label.output.field}} to pass data between nodes");
        idx++;
        rules.put(idx + "_outputs_provided", "After each node creation, available_inputs shows all outputs from existing nodes that can be used");
        idx++;
        rules.put(idx + "_HELP_BEFORE_ADD", "Call workflow(action='help', topics=['<node_type>']) BEFORE adding a node to see required params and outputs");

        return rules;
    }

    /**
     * Build the variable syntax map - common between init and load.
     */
    public static Map<String, String> buildVariableSyntax() {
        Map<String, String> variableSyntax = new LinkedHashMap<>();
        variableSyntax.put("trigger", "{{trigger:label.output.field}}");
        variableSyntax.put("mcp", "{{mcp:label.output.field}}");
        variableSyntax.put("agent", "{{agent:label.output.response}}");
        variableSyntax.put("core", "{{core:label.output.field}}");
        variableSyntax.put("table", "{{table:label.output.field}}");
        variableSyntax.put("vars", "{{$vars.name}} - Reusable workspace variable (user-defined config, no .output. segment). Alias: {{vars:name}}.");
        variableSyntax.put("interface_templates", "{{variable|default}} - Interface templates use GENERIC names with pipe defaults. Map to workflow data via variable_mapping on the node.");
        variableSyntax.put("interface_outputs", "{{interface:label.output.action_name.field_name}} - Access form data submitted via interface actions");
        return variableSyntax;
    }

    /**
     * Build the actions map - common between init and load.
     */
    public static Map<String, String> buildActions() {
        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("add_node", "workflow(action='add_node', type='...', label='...', params={...}, connect_after='Previous')");
        actions.put("modify", "workflow(action='modify', node='Label', params={...})");
        actions.put("remove", "workflow(action='remove', node='Label')");
        actions.put("connect", "workflow(action='connect', from='A', to='B')");
        actions.put("validate", "workflow(action='validate')");
        actions.put("finish", "workflow(action='finish') - finalize and save the workflow (closes the build session)");
        actions.put("execute", "workflow(action='execute') - run this workflow");
        return actions;
    }

    /**
     * Add all common elements to a result map.
     * @param result The result map to populate
     * @param nodeLibraryService Service to get node types from database
     * @param isInit true for init (includes trigger_first rule), false for load
     */
    public static void addCommonElements(Map<String, Object> result,
                                          NodeLibraryService nodeLibraryService,
                                          boolean isInit) {
        result.put("rules", buildRules(isInit));
        result.put("variable_syntax", buildVariableSyntax());
        result.put("spel_quick_reference", buildSpelQuickReference());
        result.put("available_node_types_by_category", nodeLibraryService.getNodeTypesMap());
        result.put("actions", buildActions());
        result.put("help", "workflow(action='help', topics=['<node_type>']) for params details (e.g. agent, decision, interface)");
    }

    /**
     * Compact SpEL reference included in every init/load response.
     * Gives the LLM enough to use expressions immediately, with a pointer to full docs.
     */
    public static Map<String, Object> buildSpelQuickReference() {
        Map<String, Object> spel = new LinkedHashMap<>();
        spel.put("description", "Use SpEL expressions inside {{...}} for calculations, conditions, and transformations in node parameters.");
        spel.put("operators", "+ - * / % == != < > <= >= && || ! (ternary: condition ? a : b)");
        spel.put("functions_by_category", Map.ofEntries(
            Map.entry("type_casting", "int(v), double(v), string(v), bool(v), long(v), float(v)"),
            Map.entry("utility", "size(v), len(v), length(v), typeof(v), default(v, fallback), coalesce(v1, v2, ...), ifempty(v, fallback), isnull(v), isempty(v)"),
            Map.entry("math", "abs(v), round(v, decimals), floor(v), ceil(v), min(a, b), max(a, b), pow(base, exp), sqrt(v)"),
            Map.entry("string", "uppercase(v), lowercase(v), capitalize(v), trim(v), truncate(v, max, suffix), replace(v, search, rep), substring(v, start, end), split(v, delim), join(list, delim), padleft(v, len, char), padright(v, len, char)"),
            Map.entry("string_checks", "startswith(v, prefix), endswith(v, suffix), contains(v, search), matches(v, regex)"),
            Map.entry("date_format", "now(), today(), formatdate(v, pattern), formatnumber(v, decimals), formatcurrency(v, code)"),
            Map.entry("json", "json(v) - parse JSON string → typed Map/List (idempotent), fromjson(v) - alias for GHA parity, tojson(v) - serialize Map/List → compact JSON string")
        ));
        spel.put("examples", List.of(
            "{{uppercase(trigger:form.output.name)}}",
            "{{int(mcp:api.output.count) > 10 ? 'many' : 'few'}}",
            "{{round(mcp:calc.output.price * 1.2, 2)}}",
            "{{default(mcp:api.output.title, 'Untitled')}}",
            "{{json(mcp:fetch.output.body)}} - re-parse stringified JSON to a typed Map for an object-typed param"
        ));
        spel.put("full_documentation", "workflow(action='help', topics=['spel']) - 46 functions with full signatures, parameter details, and more examples");
        return spel;
    }
}
