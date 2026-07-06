package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Parser for WorkflowPlan from Map (JSON) representation.
 * Extracted from WorkflowPlan for Single Responsibility Principle.
 */
public final class WorkflowPlanParser {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPlanParser.class);
    private static final Set<String> VALID_NODE_TYPES = Set.of(
        "trigger", "mcp", "table", "agent", "core", "note", "interface"
    );
    private static final Set<String> NODES_WITH_PORTS = Set.of("core", "agent");

    private WorkflowPlanParser() {}

    /**
     * Parse a WorkflowPlan from Map data with tenantId only (id will be auto-generated).
     */
    @SuppressWarnings("unchecked")
    public static WorkflowPlan parse(Map<String, Object> planData, String externalTenantId) {
        return parse(planData, null, externalTenantId);
    }

    @SuppressWarnings("unchecked")
    public static WorkflowPlan parse(Map<String, Object> planData, String externalId, String externalTenantId) {
        // id and tenantId come from dedicated DB columns, not from the plan JSON.
        String id = externalId;
        String tenantId = externalTenantId;

        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            logger.info("Generated workflow id {} for plan with no identifier", id);
        } else {
            try {
                UUID.fromString(id);
            } catch (IllegalArgumentException ex) {
                String originalId = id;
                id = UUID.randomUUID().toString();
                logger.warn("Workflow plan id '{}' is not a valid UUID. Generated {} instead", originalId, id);
            }
        }

        // Plan JSON is pure workflow definition - no metadata to strip
        Map<String, Object> originalPlan = planData;

        List<Trigger> triggers = parseTriggers((List<Map<String, Object>>) planData.get("triggers"));
        List<Step> steps = parseSteps((List<Map<String, Object>>) planData.get("mcps"));
        List<Step> tables = parseTables((List<Map<String, Object>>) planData.get("tables"));
        List<Agent> agents = parseAgents((List<Map<String, Object>>) planData.get("agents"));
        List<Core> cores = parseCores((List<Map<String, Object>>) planData.get("cores"));
        List<Edge> edges = parseEdges((List<Map<String, Object>>) planData.get("edges"));
        List<Note> notes = parseNotes((List<Map<String, Object>>) planData.get("notes"));
        List<InterfaceDef> interfaces = parseInterfaces((List<Map<String, Object>>) planData.get("interfaces"));
        Map<String, NodePolicy> nodePolicies = parseNodePolicies(planData);

        return new WorkflowPlan(id, tenantId, triggers, steps, agents, edges, cores, tables, notes, interfaces,
                nodePolicies, originalPlan);
    }

    // ===== NODE POLICIES =====

    /**
     * Parses the optional {@code nodePolicy} block on every executable node entry
     * (mcps / tables / agents / cores / interfaces). Triggers and notes are excluded
     * by design: triggers are entry points and notes are annotations - neither is an
     * executed step, so a {@code nodePolicy} block there is silently ignored.
     *
     * <p>Returned map is keyed by the node's normalized key - the SAME key the
     * execution engine uses as {@code nodeId} ({@code mcp:label}, {@code table:label},
     * {@code agent:label}, {@code core:label}, {@code interface:label}) - mirroring
     * each domain record's {@code getNormalizedKey()}.
     *
     * <p>Entries without a policy block (the overwhelming majority) are simply absent
     * from the map; {@link WorkflowPlan#getNodePolicy} resolves them to
     * {@link NodePolicy#DEFAULT}.
     *
     * @throws IllegalArgumentException on negative / non-coercible policy values
     *         (clear parse-time failure rather than runtime surprise)
     */
    /**
     * Core types whose successful execution selects EXACTLY ONE outgoing port
     * (see {@link Core#getDecisionPorts()} / {@link Core#getSwitchPorts()} /
     * {@link Core#getOptionPorts()}). {@code continueOnFailure} is REJECTED on
     * these at parse time: a failed branching node has selected no port, so the
     * engine's failure-continuation ({@code getSuccessors()} fallback) would fan
     * out ALL ports at once - if/else both taken, every case, every choice -
     * which is never what the author meant. Fork is intentionally absent
     * (fan-out-all IS its semantic); loop/approval don't accept the flag
     * combination meaningfully but are signal/back-edge driven and keep their
     * existing behavior.
     */
    private static final Set<String> SINGLE_PORT_BRANCHING_CORE_TYPES = Set.of("decision", "switch", "option");

    /**
     * Core types where {@code executeOnce: true} is REJECTED at parse time -
     * the policy is a SPLIT-ITEM filter ("execute for split item 0 only, skip the
     * rest") and these nodes' semantics are incompatible with per-item suppression:
     * <ul>
     *   <li>{@code split} - the split node PRODUCES the items; it already executes
     *       once per workflow item. executeOnce there is meaningless - the author
     *       almost certainly meant a body node.</li>
     *   <li>{@code aggregate} - consumes ALL split items by design; executing only
     *       for item 0 would silently break the N→1 reduction.</li>
     *   <li>{@code merge} - the split convergence barrier waits for ALL items /
     *       branches; suppressing per-item execution would break readiness.</li>
     *   <li>{@code loop} - REJECTED because the intent is ambiguous: executeOnce
     *       could be read as "first iteration only", which the policy does NOT
     *       implement (it filters split items, never loop iterations - a node
     *       inside a loop body still re-executes every iteration). Rather than
     *       guess, fail at parse with a clear message.</li>
     * </ul>
     * Everything else (mcps / tables / agents / interfaces / non-listed cores such
     * as transform or decision) keeps executeOnce ALLOWED.
     */
    private static final Set<String> EXECUTE_ONCE_INCOMPATIBLE_CORE_TYPES =
            Set.of("split", "aggregate", "merge", "loop");

    @SuppressWarnings("unchecked")
    static Map<String, NodePolicy> parseNodePolicies(Map<String, Object> planData) {
        Map<String, NodePolicy> policies = new HashMap<>();
        collectNodePolicies(policies, (List<Map<String, Object>>) planData.get("mcps"),
                data -> keyFromLabel("mcp", firstNonBlank(safeString(data.get("label")), safeString(data.get("alias")))), null);
        collectNodePolicies(policies, (List<Map<String, Object>>) planData.get("tables"),
                data -> keyFromLabel("table", safeString(data.get("label"))), null);
        collectNodePolicies(policies, (List<Map<String, Object>>) planData.get("agents"),
                data -> keyFromLabel("agent", safeString(data.get("label"))), null);
        collectNodePolicies(policies, (List<Map<String, Object>>) planData.get("cores"),
                WorkflowPlanParser::coreKeyFromRaw,
                ((java.util.function.BiConsumer<Map<String, Object>, NodePolicy>)
                        WorkflowPlanParser::rejectContinueOnFailureOnBranchingCore)
                    .andThen(WorkflowPlanParser::rejectExecuteOnceOnIncompatibleCore));
        collectNodePolicies(policies, (List<Map<String, Object>>) planData.get("interfaces"),
                data -> keyFromLabel("interface", safeString(data.get("label"))), null);
        return policies;
    }

    private static void collectNodePolicies(
            Map<String, NodePolicy> policies,
            List<Map<String, Object>> entries,
            java.util.function.Function<Map<String, Object>, String> keyFn,
            java.util.function.BiConsumer<Map<String, Object>, NodePolicy> validator) {
        if (entries == null) return;
        for (Map<String, Object> data : entries) {
            if (data == null || !data.containsKey(NodePolicy.JSON_KEY)) continue;
            String key = keyFn.apply(data);
            if (key == null) {
                // Entry is dropped by its own parser (missing label/type) - its policy is moot.
                continue;
            }
            NodePolicy policy = NodePolicy.fromMap(data.get(NodePolicy.JSON_KEY), key);
            if (validator != null) {
                validator.accept(data, policy);
            }
            if (!policy.isDefault()) {
                policies.put(key, policy);
            }
        }
    }

    /**
     * Parse-time rejection of {@code continueOnFailure: true} on single-port
     * branching cores (decision / switch / option) - see
     * {@link #SINGLE_PORT_BRANCHING_CORE_TYPES} for the fan-out rationale.
     * {@code retryCount}/{@code retryBackoffMs} remain allowed on these nodes.
     */
    private static void rejectContinueOnFailureOnBranchingCore(Map<String, Object> data, NodePolicy policy) {
        if (!policy.continueOnFailure()) return;
        String type = safeString(data.get("type"));
        if (type != null && SINGLE_PORT_BRANCHING_CORE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            String key = coreKeyFromRaw(data);
            throw new IllegalArgumentException(
                "Invalid nodePolicy for node '" + key + "': continueOnFailure=true is not supported on "
                    + "branching nodes (core type '" + type + "'). A failed " + type + " selects no port, so "
                    + "continuing past the failure would traverse ALL its ports at once (every branch/case/choice). "
                    + "Remove continueOnFailure from this node (retryCount is still allowed) or handle the "
                    + "failure on the nodes upstream/downstream of the branch instead.");
        }
    }

    /**
     * Parse-time rejection of {@code executeOnce: true} on incompatible cores
     * (split / aggregate / merge / loop) - see
     * {@link #EXECUTE_ONCE_INCOMPATIBLE_CORE_TYPES} for the per-type rationale.
     * The other policy fields stay allowed on these nodes.
     */
    private static void rejectExecuteOnceOnIncompatibleCore(Map<String, Object> data, NodePolicy policy) {
        if (!policy.executeOnce()) return;
        String type = safeString(data.get("type"));
        if (type != null && EXECUTE_ONCE_INCOMPATIBLE_CORE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            String key = coreKeyFromRaw(data);
            String reason = switch (type.toLowerCase(Locale.ROOT)) {
                case "split" -> "the split node produces the split items and already executes once per workflow item - "
                    + "put executeOnce on a node INSIDE the split scope instead";
                case "aggregate" -> "an aggregate consumes ALL split items by design - executing it for item 0 only "
                    + "would silently break the N-to-1 reduction";
                case "merge" -> "a merge is the convergence barrier that waits for ALL items/branches - suppressing "
                    + "per-item execution would break merge readiness";
                default -> "executeOnce filters SPLIT ITEMS, never loop iterations - on a loop node the intent "
                    + "('first iteration only'?) is ambiguous, so it is rejected rather than guessed. A node inside "
                    + "a loop body still re-executes every iteration regardless of executeOnce";
            };
            throw new IllegalArgumentException(
                "Invalid nodePolicy for node '" + key + "': executeOnce=true is not supported on core type '"
                    + type + "': " + reason + ". Remove executeOnce from this node "
                    + "(retryCount/retryBackoffMs/timeoutMs remain allowed).");
        }
    }

    /** Mirrors Step/Agent/InterfaceDef.getNormalizedKey(): "prefix:" + normalized label. */
    private static String keyFromLabel(String prefix, String label) {
        String normalized = LabelNormalizer.normalizeLabel(label);
        return normalized != null ? prefix + ":" + normalized : null;
    }

    /** Mirrors {@link Core#getNormalizedKey()}: label falls back to id, then lowercase fallback. */
    private static String coreKeyFromRaw(Map<String, Object> data) {
        String id = safeString(data.get("id"));
        String type = safeString(data.get("type"));
        if (id == null || id.isBlank() || type == null || type.isBlank()) {
            return null; // parseCore skips this entry entirely
        }
        String label = safeString(data.get("label"));
        String base = (label != null && !label.isBlank()) ? label : id;
        String normalized = LabelNormalizer.normalizeLabel(base);
        return normalized != null ? "core:" + normalized : "core:" + base.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    // ===== TRIGGERS =====

    @SuppressWarnings("unchecked")
    static List<Trigger> parseTriggers(List<Map<String, Object>> triggersData) {
        if (triggersData == null) {
            logger.info("[parseTriggers] triggersData is null, returning empty list");
            return new ArrayList<>();
        }

        logger.info("[parseTriggers] Parsing {} triggers", triggersData.size());
        return triggersData.stream()
            .map(data -> {
                logger.info("[parseTriggers] Parsing trigger data: {}", data);
                String id = safeString(data.get("id"));
                if (id == null) id = UUID.randomUUID().toString();
                String label = safeString(data.get("label"));
                String strategy = safeStringOrDefault(data, "strategy", "single");
                String type = safeStringOrDefault(data, "type", "datasource");
                // "params" is the standard key; "input" is a legacy key from older builder sessions
                Map<String, Object> params = (Map<String, Object>) data.getOrDefault("params",
                    data.getOrDefault("input", new HashMap<>()));

                ChatMatchConfig chatMatch = null;
                if ("chat".equals(type) && data.containsKey("chatMatch")) {
                    Object chatMatchData = data.get("chatMatch");
                    if (chatMatchData instanceof Map) {
                        chatMatch = ChatMatchConfig.fromMap((Map<String, Object>) chatMatchData);
                    }
                }

                logger.info("[parseTriggers] Created trigger: id={}, label={}, type={}", id, label, type);
                return new Trigger(id, label, strategy, type, params, chatMatch);
            })
            .collect(Collectors.toList());
    }

    // ===== STEPS (MCP catalog tools only) =====

    @SuppressWarnings("unchecked")
    static List<Step> parseSteps(List<Map<String, Object>> stepsData) {
        if (stepsData == null) return new ArrayList<>();

        return stepsData.stream()
            .map(data -> {
                String label = safeString(data.get("label"));
                if (label == null || label.isBlank()) label = safeString(data.get("alias"));

                String id = safeString(data.get("id"));
                if (id == null || id.isBlank()) id = safeString(data.get("tool_id"));

                // Parse type field - default to "mcp" for catalog tools
                String type = safeStringOrDefault(data, "type", "mcp");

                return new Step(
                    id,
                    type,
                    label,
                    safeString(data.get("parentLoopId")),
                    (Map<String, Object>) data.getOrDefault("params", new HashMap<>()),
                    parseDataSourceId(data.get("dataSourceId")),
                    parseCrudConfig((Map<String, Object>) data.get("crud")),
                    safeString(data.get("graphNodeId")),
                    parseLong(data.get("selectedCredentialId")),
                    parseCredentialSource(data.get("credentialSource")),
                    parseLong(data.get("platformCredentialId"))
                );
            })
            .collect(Collectors.toList());
    }

    private static CredentialSource parseCredentialSource(Object raw) {
        if (raw == null) return CredentialSource.USER;
        if (raw instanceof CredentialSource cs) return cs;
        return CredentialSource.fromWire(raw.toString());
    }

    private static Long parseLong(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            logger.warn("Invalid long value: {}", raw);
            return null;
        }
    }

    // ===== TABLES (CRUD operations) =====

    /**
     * Parse tables array (CRUD operations) as Steps.
     * Tables use the table: prefix and contain CRUD configuration.
     * Type must be one of: crud-create-row, crud-read-row, crud-update-row, crud-delete-row, crud-create-column, crud-find
     */
    @SuppressWarnings("unchecked")
    static List<Step> parseTables(List<Map<String, Object>> tablesData) {
        if (tablesData == null) return new ArrayList<>();

        return tablesData.stream()
            .map(data -> {
                String label = safeString(data.get("label"));
                if (label == null || label.isBlank()) {
                    logger.warn("Table with missing label, skipping");
                    return null;
                }

                String id = safeString(data.get("id"));

                // Parse type field (required for tables)
                String type = safeString(data.get("type"));
                if (type == null || type.isBlank()) {
                    logger.warn("Table '{}' with missing type, skipping", label);
                    return null;
                }

                // Normalize type: table types use "type" directly (e.g., "read-row", "find")
                // but Step expects "crud-" prefix for CRUD types
                String normalizedType = type;
                if (!type.startsWith("crud-")) {
                    normalizedType = "crud-" + type;
                }

                // Build params - include "list" from top-level for crud-find
                Map<String, Object> params = new HashMap<>(
                    (Map<String, Object>) data.getOrDefault("params", new HashMap<>()));
                if (data.containsKey("list")) {
                    params.put("list", data.get("list"));
                }

                // Parse datasourceId from top-level (tables use "dataSourceId", "datasourceId", or "table_id")
                Long dataSourceId = parseDataSourceId(data.get("dataSourceId"));
                if (dataSourceId == null) {
                    dataSourceId = parseDataSourceId(data.get("datasourceId"));
                }
                if (dataSourceId == null) {
                    dataSourceId = parseDataSourceId(data.get("table_id"));
                }

                // Parse CRUD config - try "crud" block first, then top-level fields, then params
                Map<String, Object> crudBlock = (Map<String, Object>) data.get("crud");
                if (crudBlock == null) {
                    crudBlock = new HashMap<>();
                } else {
                    crudBlock = new HashMap<>(crudBlock); // mutable copy
                }
                // Merge top-level and params fields into crud block (similarity often in params)
                for (String key : List.of("where", "limit", "offset", "set", "rows", "columns", "similarity")) {
                    if (!crudBlock.containsKey(key)) {
                        if (data.containsKey(key)) {
                            crudBlock.put(key, data.get(key));
                        } else if (params.containsKey(key)) {
                            crudBlock.put(key, params.get(key));
                        }
                    }
                }
                Step.CrudConfig crudConfig = !crudBlock.isEmpty() ? parseCrudConfig(crudBlock) : null;

                return new Step(
                    id,
                    normalizedType,
                    label,
                    safeString(data.get("parentLoopId")),
                    params,
                    dataSourceId,
                    crudConfig,
                    safeString(data.get("graphNodeId"))
                );
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Core.TransformConfig parseTransformConfig(Map<String, Object> data) {
        if (data == null) return null;

        List<Map<String, Object>> mappingsData = (List<Map<String, Object>>) data.get("mappings");
        if (mappingsData == null || mappingsData.isEmpty()) return null;

        List<Core.TransformMapping> mappings = mappingsData.stream()
            .map(m -> new Core.TransformMapping(safeString(m.get("label")), safeString(m.get("expression"))))
            .collect(Collectors.toList());

        return new Core.TransformConfig(mappings);
    }

    private static Core.WaitConfig parseWaitConfig(Map<String, Object> data) {
        if (data == null) return null;

        Object durationObj = data.get("duration");
        if (durationObj == null) return new Core.WaitConfig(0);

        long duration = 0;
        if (durationObj instanceof Number) {
            duration = ((Number) durationObj).longValue();
        } else if (durationObj instanceof String) {
            try { duration = Long.parseLong((String) durationObj); } catch (NumberFormatException ignored) {}
        }

        return new Core.WaitConfig(duration);
    }

    private static Core.DownloadConfig parseDownloadConfig(Map<String, Object> data) {
        if (data == null) return null;

        String url = safeString(data.get("url"));
        String filename = safeString(data.get("filename"));
        String mimeType = safeString(data.get("mimeType"));

        return new Core.DownloadConfig(url, filename, mimeType);
    }

    private static Core.ResponseConfig parseResponseConfig(Map<String, Object> data) {
        if (data == null) return null;

        String message = safeString(data.get("message"));
        return new Core.ResponseConfig(message);
    }

    @SuppressWarnings("unchecked")
    private static Core.AggregateConfig parseAggregateConfig(Map<String, Object> data) {
        if (data == null) return null;

        List<Map<String, Object>> fieldsData = (List<Map<String, Object>>) data.get("fields");
        List<Core.AggregateField> fields = null;
        if (fieldsData != null) {
            fields = fieldsData.stream()
                .map(f -> new Core.AggregateField(
                    safeString(f.get("label")),
                    safeString(f.get("expression"))))
                .collect(Collectors.toList());
        }
        return new Core.AggregateConfig(fields);
    }

    private static List<Core.OptionChoice> parseOptionChoices(List<Map<String, Object>> data) {
        if (data == null) return null;
        return data.stream()
            .map(c -> new Core.OptionChoice(
                safeString(c.get("id")),
                safeString(c.get("label")),
                safeString(c.get("expression"))))
            .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Core.HttpRequestConfig parseHttpRequestConfig(Map<String, Object> data) {
        if (data == null) return null;

        String method = safeString(data.get("method"));
        String url = safeString(data.get("url"));
        String authType = safeString(data.get("authType"));
        String bodyType = safeString(data.get("bodyType"));
        String body = safeString(data.get("body"));
        String contentType = safeString(data.get("contentType"));
        Integer timeout = data.get("timeout") instanceof Number ? ((Number) data.get("timeout")).intValue() : null;

        // Parse authConfig
        Core.HttpAuthConfig authConfig = null;
        if (data.get("authConfig") instanceof Map) {
            Map<String, Object> ac = (Map<String, Object>) data.get("authConfig");
            authConfig = new Core.HttpAuthConfig(
                safeString(ac.get("username")),
                safeString(ac.get("password")),
                safeString(ac.get("bearerToken")),
                safeString(ac.get("apiKeyName")),
                safeString(ac.get("apiKeyValue")),
                safeString(ac.get("apiKeyLocation")),
                safeString(ac.get("headerName")),
                safeString(ac.get("headerValue"))
            );
        }

        // Parse queryParams
        List<Core.HttpParam> queryParams = null;
        if (data.get("queryParams") instanceof List) {
            queryParams = ((List<Map<String, Object>>) data.get("queryParams")).stream()
                .map(p -> new Core.HttpParam(
                    safeString(p.get("id")),
                    safeString(p.get("key")),
                    safeString(p.get("value"))))
                .collect(Collectors.toList());
        }

        // Parse headers
        List<Core.HttpParam> headers = null;
        if (data.get("headers") instanceof List) {
            headers = ((List<Map<String, Object>>) data.get("headers")).stream()
                .map(h -> new Core.HttpParam(
                    safeString(h.get("id")),
                    safeString(h.get("key")),
                    safeString(h.get("value"))))
                .collect(Collectors.toList());
        }

        return new Core.HttpRequestConfig(
            method, url, authType, authConfig, queryParams, headers,
            bodyType, body, contentType, timeout
        );
    }

    private static Long parseDataSourceId(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        if (obj instanceof String) {
            try { return Long.parseLong((String) obj); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Step.CrudConfig parseCrudConfig(Map<String, Object> data) {
        if (data == null) return null;

        Step.CrudConfig.WhereCondition where = null;
        Map<String, Object> whereData = (Map<String, Object>) data.get("where");
        if (whereData != null) {
            String column = (String) whereData.get("column");
            String operator = (String) whereData.get("operator");
            Object value = whereData.get("value");
            if (column != null && operator != null) {
                where = new Step.CrudConfig.WhereCondition(column, operator, value);
            }
        }

        Map<String, Object> set = (Map<String, Object>) data.get("set");

        List<Step.CrudConfig.RowData> rows = new ArrayList<>();
        Object rowsObj = data.get("rows");
        if (rowsObj instanceof List<?> rowsList) {
            for (Object item : rowsList) {
                if (item instanceof Map) {
                    Map<String, Object> rowData = (Map<String, Object>) item;
                    rows.add(new Step.CrudConfig.RowData((String) rowData.get("id"), (Map<String, Object>) rowData.get("columns")));
                }
            }
        }

        List<Step.CrudConfig.ColumnDefinition> columns = new ArrayList<>();
        Object columnsObj = data.get("columns");
        if (columnsObj instanceof List<?> columnsList) {
            for (Object item : columnsList) {
                if (item instanceof Map) {
                    Map<String, Object> colData = (Map<String, Object>) item;
                    columns.add(new Step.CrudConfig.ColumnDefinition(
                        (String) colData.get("name"), (String) colData.get("type"), colData.get("defaultValue")));
                }
            }
        } else if (columnsObj instanceof Map) {
            // Agent may produce columns as an object {"col_name": {"type": "..."}} instead of an array
            Map<String, Object> columnsMap = (Map<String, Object>) columnsObj;
            for (Map.Entry<String, Object> entry : columnsMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Map<String, Object> colData = (Map<String, Object>) entry.getValue();
                    columns.add(new Step.CrudConfig.ColumnDefinition(
                        entry.getKey(),
                        (String) colData.get("type"),
                        colData.get("defaultValue")));
                }
            }
        }

        Integer limit = null;
        Object limitObj = data.get("limit");
        if (limitObj instanceof Number) limit = ((Number) limitObj).intValue();
        else if (limitObj instanceof String) {
            try { limit = Integer.parseInt((String) limitObj); } catch (NumberFormatException ignored) {}
        }

        Integer offset = null;
        Object offsetObj = data.get("offset");
        if (offsetObj instanceof Number) offset = ((Number) offsetObj).intValue();
        else if (offsetObj instanceof String) {
            try { offset = Integer.parseInt((String) offsetObj); } catch (NumberFormatException ignored) {}
        }

        Step.CrudConfig.SimilarityConfig similarity = null;
        Map<String, Object> simData = null;
        Object simRaw = data.get("similarity");
        if (simRaw instanceof Map) {
            simData = (Map<String, Object>) simRaw;
        } else if (simRaw instanceof String simStr && !simStr.isBlank()) {
            // Handle stringified JSON from frontend params
            try {
                simData = new com.fasterxml.jackson.databind.ObjectMapper().readValue(simStr, Map.class);
            } catch (Exception e) {
                logger.warn("[parseCrudConfig] Failed to parse similarity JSON string: {}", e.getMessage());
            }
        }
        if (simData != null) {
            String simColumn = (String) simData.get("column");
            String queryVector = (String) simData.get("queryVector");
            Integer topK = null;
            Object topKObj = simData.get("topK");
            if (topKObj instanceof Number) topK = ((Number) topKObj).intValue();
            Double threshold = null;
            Object threshObj = simData.get("threshold");
            if (threshObj instanceof Number) threshold = ((Number) threshObj).doubleValue();
            if (simColumn != null && queryVector != null) {
                similarity = new Step.CrudConfig.SimilarityConfig(simColumn, queryVector, topK, threshold);
            }
        }

        return new Step.CrudConfig(where, similarity, set, rows, columns, limit, offset);
    }

    // ===== AGENTS =====

    @SuppressWarnings("unchecked")
    static List<Agent> parseAgents(List<Map<String, Object>> agentsData) {
        if (agentsData == null) return new ArrayList<>();

        return agentsData.stream()
            .map(data -> {
                String label = safeString(data.get("label"));
                if (label == null || label.isBlank()) {
                    logger.warn("Agent with missing label, skipping");
                    return null;
                }

                Double temperature = null;
                if (data.get("temperature") instanceof Number) temperature = ((Number) data.get("temperature")).doubleValue();

                Integer maxTokens = null;
                if (data.get("maxTokens") instanceof Number) maxTokens = ((Number) data.get("maxTokens")).intValue();

                Integer maxIterations = null;
                if (data.get("maxIterations") instanceof Number) maxIterations = ((Number) data.get("maxIterations")).intValue();

                Integer maxTools = null;
                if (data.get("maxTools") instanceof Number) maxTools = ((Number) data.get("maxTools")).intValue();

                String agentConfigId = safeString(data.get("agentConfigId"));
                Boolean withMemory = data.get("withMemory") instanceof Boolean b ? b : null;

                return new Agent(
                    safeString(data.get("id")),
                    safeStringOrDefault(data, "type", "agent"),  // agent, guardrail, classify
                    label,
                    agentConfigId,
                    withMemory,
                    safeString(data.get("provider")),
                    safeString(data.get("model")),
                    safeString(data.get("systemPrompt")),
                    safeString(data.get("prompt")),
                    temperature, maxTokens, maxIterations, maxTools,
                    (List<String>) data.get("tools"),
                    safeString(data.get("parentLoopId")),
                    (Map<String, Object>) data.getOrDefault("params", new HashMap<>()),
                    // Classify-specific fields
                    (List<Map<String, Object>>) data.get("classifyCategories"),
                    safeString(data.get("classifyParams")),
                    // Guardrail-specific fields (may be List<Map> or Map<key,desc> from builder)
                    parseGuardrailRules(data.get("guardrailRules")),
                    safeString(data.get("guardrailParams")),
                    safeString(data.get("graphNodeId"))
                );
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    // ===== EDGES =====

    static List<Edge> parseEdges(List<Map<String, Object>> edgesData) {
        if (edgesData == null) return new ArrayList<>();

        return edgesData.stream()
            .map(data -> {
                String from = normalizeEdgeRef(safeString(data.get("from")));
                String to = normalizeEdgeRef(safeString(data.get("to")));
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) data.getOrDefault("params", new HashMap<>());
                return new Edge(from, to, params);
            })
            .filter(edge -> edge.from() != null)
            .collect(Collectors.toList());
    }

    // ===== CORE NODES =====

    @SuppressWarnings("unchecked")
    static List<Core> parseCores(List<Map<String, Object>> coresData) {
        if (coresData == null) return new ArrayList<>();

        return coresData.stream()
            .map(WorkflowPlanParser::parseCore)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Core parseCore(Map<String, Object> data) {
        String id = safeString(data.get("id"));
        String type = safeString(data.get("type"));

        if (id == null || id.isBlank()) {
            logger.warn("Core with missing id, skipping");
            return null;
        }
        if (type == null || type.isBlank()) {
            logger.warn("Core {} with missing type, skipping", id);
            return null;
        }

        Map<String, Object> position = (Map<String, Object>) data.get("position");
        String label = safeString(data.get("label"));
        Map<String, Object> params = (Map<String, Object>) data.getOrDefault("params", new HashMap<>());

        List<Core.DecisionCondition> decisionConditions = null;
        String switchExpression = null;
        List<Core.SwitchCase> switchCases = null;
        String loopCondition = null;
        Integer maxIterations = null;
        String strategy = null;
        String list = null;
        Integer maxItems = null;
        String splitStrategy = null;
        List<Core.ForkOutput> forkOutputs = null;
        Core.TransformConfig transformConfig = null;
        Core.WaitConfig waitConfig = null;
        Core.DownloadConfig downloadConfig = null;
        Core.ResponseConfig responseConfig = null;
        Core.AggregateConfig aggregateConfig = null;
        List<Core.OptionChoice> optionChoices = null;
        Core.HttpRequestConfig httpRequestConfig = null;
        Core.DataInputConfig dataInputConfig = null;

        if ("decision".equals(type) || "switch".equals(type)) {
            decisionConditions = parseDecisionConditions((List<Map<String, Object>>) data.get("decisionConditions"));
            if ("switch".equals(type)) {
                switchExpression = safeString(data.get("switchExpression"));
                switchCases = parseSwitchCases((List<Map<String, Object>>) data.get("switchCases"));
            }
        } else if ("loop".equals(type)) {
            loopCondition = safeString(data.get("loopCondition"));
            // Accept both "maxIterations" (standard V2) and "maxIteration" (legacy)
            Object maxIterObj = data.get("maxIterations");
            if (maxIterObj == null) maxIterObj = data.get("maxIteration");
            if (maxIterObj instanceof Number) maxIterations = ((Number) maxIterObj).intValue();
            strategy = safeStringOrDefault(data, "strategy", "continue-anyway");
        } else if ("split".equals(type)) {
            // Accept both "list" (new) and "listExpression" (legacy) for backward compatibility
            list = safeString(data.get("list"));
            if (list == null) list = safeString(data.get("listExpression"));
            if (data.get("maxItems") instanceof Number) maxItems = ((Number) data.get("maxItems")).intValue();
            splitStrategy = safeStringOrDefault(data, "splitStrategy", "stop-on-error");
        } else if ("fork".equals(type)) {
            forkOutputs = parseForkOutputs((List<Map<String, Object>>) data.get("forkOutputs"));
        } else if ("transform".equals(type)) {
            transformConfig = parseTransformConfig((Map<String, Object>) data.get("transform"));
        } else if ("wait".equals(type)) {
            waitConfig = parseWaitConfig((Map<String, Object>) data.get("wait"));
        } else if ("download_file".equals(type)) {
            downloadConfig = parseDownloadConfig((Map<String, Object>) data.get("download"));
        } else if ("response".equals(type)) {
            responseConfig = parseResponseConfig((Map<String, Object>) data.get("response"));
        } else if ("aggregate".equals(type)) {
            aggregateConfig = parseAggregateConfig((Map<String, Object>) data.get("aggregate"));
        } else if ("option".equals(type)) {
            optionChoices = parseOptionChoices((List<Map<String, Object>>) data.get("optionChoices"));
        } else if ("http_request".equals(type)) {
            httpRequestConfig = parseHttpRequestConfig((Map<String, Object>) data.get("httpRequest"));
        } else if ("data_input".equals(type)) {
            dataInputConfig = parseDataInputConfig((Map<String, Object>) data.get("dataInput"));
        }

        // Approval-specific
        Core.ApprovalConfig approvalConfig = null;
        if ("approval".equals(type)) {
            approvalConfig = parseApprovalConfig((Map<String, Object>) data.get("approval"));
        }

        // Parse new node configs - each reads from its corresponding JSON key
        Core.FilterConfig filterConfig = parseConfigSafe(data, "filter", Core.FilterConfig.class);
        Core.SortConfig sortConfig = parseConfigSafe(data, "sort", Core.SortConfig.class);
        Core.LimitConfig limitConfig = parseConfigSafe(data, "limit", Core.LimitConfig.class);
        Core.RemoveDuplicatesConfig removeDuplicatesConfig = parseConfigSafe(data, "removeDuplicates", Core.RemoveDuplicatesConfig.class);
        Core.SummarizeConfig summarizeConfig = parseConfigSafe(data, "summarize", Core.SummarizeConfig.class);
        Core.DateTimeConfig dateTimeConfig = parseConfigSafe(data, "dateTime", Core.DateTimeConfig.class);
        Core.CryptoJwtConfig cryptoJwtConfig = parseConfigSafe(data, "cryptoJwt", Core.CryptoJwtConfig.class);
        Core.XmlConfig xmlConfig = parseConfigSafe(data, "xml", Core.XmlConfig.class);
        Core.CompressionConfig compressionConfig = parseConfigSafe(data, "compression", Core.CompressionConfig.class);
        Core.RssConfig rssConfig = parseConfigSafe(data, "rss", Core.RssConfig.class);
        Core.ConvertToFileConfig convertToFileConfig = parseConfigSafe(data, "convertToFile", Core.ConvertToFileConfig.class);
        Core.ExtractFromFileConfig extractFromFileConfig = parseConfigSafe(data, "extractFromFile", Core.ExtractFromFileConfig.class);
        Core.CompareDatasetsConfig compareDatasetsConfig = parseConfigSafe(data, "compareDatasets", Core.CompareDatasetsConfig.class);
        Core.SubWorkflowConfig subWorkflowConfig = parseConfigSafe(data, "subWorkflow", Core.SubWorkflowConfig.class);
        Core.RespondToWebhookConfig respondToWebhookConfig = parseConfigSafe(data, "respondToWebhook", Core.RespondToWebhookConfig.class);
        Core.SendEmailConfig sendEmailConfig = parseConfigSafe(data, "sendEmail", Core.SendEmailConfig.class);
        Core.EmailInboxConfig emailInboxConfig = parseConfigSafe(data, "emailInbox", Core.EmailInboxConfig.class);
        Core.CodeConfig codeConfig = parseConfigSafe(data, "code", Core.CodeConfig.class);
        Core.SetConfig setConfig = parseConfigSafe(data, "set", Core.SetConfig.class);
        Core.HtmlExtractConfig htmlExtractConfig = parseConfigSafe(data, "htmlExtract", Core.HtmlExtractConfig.class);
        Core.TaskConfig taskConfig = parseConfigSafe(data, "task", Core.TaskConfig.class);
        Core.StopOnErrorConfig stopOnErrorConfig = parseConfigSafe(data, "stopOnError", Core.StopOnErrorConfig.class);
        Core.SshConfig sshConfig = parseConfigSafe(data, "ssh", Core.SshConfig.class);
        Core.SftpConfig sftpConfig = parseConfigSafe(data, "sftp", Core.SftpConfig.class);
        Core.DatabaseConfig databaseConfig = parseConfigSafe(data, "database", Core.DatabaseConfig.class);

        return new Core(id, type, position != null ? new HashMap<>(position) : Map.of(), label,
            decisionConditions, switchExpression, switchCases, loopCondition, maxIterations, strategy,
            list, maxItems, splitStrategy, forkOutputs,
            transformConfig, waitConfig, downloadConfig, responseConfig, aggregateConfig, optionChoices,
            httpRequestConfig, approvalConfig, dataInputConfig,
            filterConfig, sortConfig, limitConfig, removeDuplicatesConfig,
            summarizeConfig, dateTimeConfig, cryptoJwtConfig, xmlConfig,
            compressionConfig, rssConfig, convertToFileConfig, extractFromFileConfig,
            compareDatasetsConfig, subWorkflowConfig, respondToWebhookConfig,
            sendEmailConfig, emailInboxConfig, codeConfig, setConfig, htmlExtractConfig, taskConfig,
            stopOnErrorConfig, sshConfig, sftpConfig, databaseConfig,
            params, safeString(data.get("graphNodeId"))
        );
    }

    private static List<Core.DecisionCondition> parseDecisionConditions(List<Map<String, Object>> data) {
        if (data == null) return null;
        return data.stream()
            .map(c -> new Core.DecisionCondition(
                safeString(c.get("id")), safeString(c.get("type")),
                safeString(c.get("label")), safeString(c.get("expression"))))
            .collect(Collectors.toList());
    }

    private static List<Core.SwitchCase> parseSwitchCases(List<Map<String, Object>> data) {
        if (data == null) return null;
        return data.stream()
            .map(c -> new Core.SwitchCase(
                safeString(c.get("id")), safeString(c.get("type")),
                safeString(c.get("label")), safeString(c.get("value"))))
            .collect(Collectors.toList());
    }

    private static List<Core.ForkOutput> parseForkOutputs(List<Map<String, Object>> data) {
        if (data == null) return null;
        return data.stream()
            .map(o -> new Core.ForkOutput(
                safeString(o.get("id")), safeString(o.get("label")), safeString(o.get("targetStep"))))
            .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Core.ApprovalConfig parseApprovalConfig(Map<String, Object> data) {
        if (data == null) return null;
        List<String> approverRoles = data.get("approverRoles") instanceof List
            ? ((List<Object>) data.get("approverRoles")).stream().map(String::valueOf).collect(Collectors.toList())
            : List.of();
        int requiredApprovals = data.get("requiredApprovals") instanceof Number
            ? ((Number) data.get("requiredApprovals")).intValue() : 1;
        long timeoutMs = data.get("timeoutMs") instanceof Number
            ? ((Number) data.get("timeoutMs")).longValue() : 86400000L; // default 24h
        String contextTemplate = data.get("contextTemplate") instanceof String s ? s : "";
        return new Core.ApprovalConfig(approverRoles, requiredApprovals, timeoutMs, contextTemplate);
    }

    @SuppressWarnings("unchecked")
    private static Core.DataInputConfig parseDataInputConfig(Map<String, Object> data) {
        if (data == null) return null;
        List<Core.DataInputItem> items = new ArrayList<>();
        Object itemsObj = data.get("items");
        if (itemsObj instanceof List<?> itemsList) {
            for (Object entry : itemsList) {
                if (entry instanceof Map<?, ?> itemMap) {
                    String id = safeString(itemMap.get("id"));
                    String label = safeString(itemMap.get("label"));
                    String type = safeString(itemMap.get("type"));
                    String text = safeString(itemMap.get("text"));
                    Map<String, Object> file = itemMap.get("file") instanceof Map
                        ? (Map<String, Object>) itemMap.get("file") : null;
                    items.add(new Core.DataInputItem(id, label, type, text, file));
                }
            }
        }
        return new Core.DataInputConfig(items);
    }

    // ===== NOTES =====

    @SuppressWarnings("unchecked")
    static List<Note> parseNotes(List<Map<String, Object>> notesData) {
        if (notesData == null) return new ArrayList<>();

        return notesData.stream()
            .map(data -> {
                try {
                    String id = safeString(data.get("id"));
                    if (id == null || id.isBlank()) return null;

                    String text = safeString(data.get("text"));
                    if (text == null) text = "";

                    Integer width = data.get("width") instanceof Number ? ((Number) data.get("width")).intValue() : null;
                    Integer height = data.get("height") instanceof Number ? ((Number) data.get("height")).intValue() : null;
                    Map<String, Object> position = (Map<String, Object>) data.get("position");

                    String type = safeStringOrDefault(data, "type", "note");
                    return new Note(id, type, safeString(data.get("label")), text,
                        safeString(data.get("color")), safeString(data.get("borderColor")),
                        safeString(data.get("textColor")), width, height,
                        position != null ? new HashMap<>(position) : Map.of());
                } catch (Exception e) {
                    logger.error("Error creating Note from data {}: {}", data, e.getMessage());
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    // ===== INTERFACES =====

    /**
     * Parse interfaces array from plan JSON.
     * Interfaces are UI nodes that participate in DAG execution.
     */
    @SuppressWarnings("unchecked")
    static List<InterfaceDef> parseInterfaces(List<Map<String, Object>> interfacesData) {
        if (interfacesData == null) return new ArrayList<>();

        return interfacesData.stream()
            .map(data -> {
                String id = safeString(data.get("id"));
                String label = safeString(data.get("label"));
                if (label == null || label.isBlank()) {
                    logger.warn("Interface with missing label, skipping");
                    return null;
                }

                // mode field ignored - always on_event implicitly
                Map<String, String> actionMapping = data.get("actionMapping") instanceof Map
                    ? (Map<String, String>) data.get("actionMapping") : Map.of();
                Map<String, String> variableMapping = data.get("variableMapping") instanceof Map
                    ? (Map<String, String>) data.get("variableMapping") : Map.of();
                Boolean showPreview = data.get("showPreview") instanceof Boolean
                    ? (Boolean) data.get("showPreview") : true;
                Map<String, Object> position = data.get("position") instanceof Map
                    ? new HashMap<>((Map<String, Object>) data.get("position")) : Map.of();
                Boolean isEntryInterface = data.get("isEntryInterface") instanceof Boolean
                    ? (Boolean) data.get("isEntryInterface") : false;
                Boolean generateScreenshot = data.get("generateScreenshot") instanceof Boolean
                    ? (Boolean) data.get("generateScreenshot") : false;
                Boolean exposeRenderedSource = data.get("exposeRenderedSource") instanceof Boolean
                    ? (Boolean) data.get("exposeRenderedSource") : false;
                Boolean generatePdf = data.get("generatePdf") instanceof Boolean
                    ? (Boolean) data.get("generatePdf") : false;
                // pdfFormat is a free-form string in the plan (kept raw here). The agent/build path
                // already normalises it to A4/Letter/Legal via InterfaceNodeConfig; the raw value is
                // forwarded to the renderer, which falls back to A4 when it is null/blank.
                String pdfFormat = data.get("pdfFormat") instanceof String s && !s.isBlank() ? s : null;
                Boolean pdfLandscape = data.get("pdfLandscape") instanceof Boolean
                    ? (Boolean) data.get("pdfLandscape") : false;

                return new InterfaceDef(id, label, actionMapping, variableMapping, showPreview, position,
                    isEntryInterface, generateScreenshot, exposeRenderedSource, generatePdf, pdfFormat, pdfLandscape);
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    // ===== NORMALIZATION =====

    /**
     * Normalize a step ID to canonical form (type:normalized_label).
     *
     * V2 Prefixes (7 categories):
     * - trigger: All triggers (webhook, chat, schedule, datasource, manual, workflow)
     * - mcp: Tool calls (MCP catalog tools)
     * - table: CRUD operations (database tables)
     * - agent: AI nodes (agent, guardrail, classify)
     * - core: Control flow (decision, switch, loop, split, merge, fork, transform, wait)
     * - note: Notes (visual only)
     * - interface: User interfaces
     */
    public static String normalizeStepId(String stepId) {
        if (stepId == null) return null;

        String trimmed = stepId.trim();
        if (trimmed.isEmpty()) return null;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        int colonIdx = lower.indexOf(':');

        if (colonIdx > 0) {
            String prefix = lower.substring(0, colonIdx);

            // V2 valid prefixes only
            if (VALID_NODE_TYPES.contains(prefix)) {
                String identifier = trimmed.substring(colonIdx + 1).trim();
                if (identifier.isEmpty()) return null;
                return prefix + ":" + identifier.toLowerCase(Locale.ROOT);
            }
        }

        // Default to mcp: prefix for unknown/unprefixed identifiers
        return "mcp:" + lower;
    }

    /**
     * Normalize a V2 edge reference (with optional port).
     */
    public static String normalizeEdgeRef(String ref) {
        if (ref == null || ref.isBlank()) return null;

        String trimmed = ref.trim();
        String[] parts = trimmed.split(":");
        if (parts.length < 2) return null;

        String nodeType = parts[0].toLowerCase(Locale.ROOT);
        if (!VALID_NODE_TYPES.contains(nodeType)) {
            return "mcp:" + LabelNormalizer.normalizeLabel(trimmed);
        }

        if (NODES_WITH_PORTS.contains(nodeType) && parts.length >= 3) {
            String port = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            StringBuilder labelBuilder = new StringBuilder();
            for (int i = 1; i < parts.length - 1; i++) {
                if (i > 1) labelBuilder.append(":");
                labelBuilder.append(parts[i]);
            }
            String normalizedLabel = LabelNormalizer.normalizeLabel(labelBuilder.toString());
            if (normalizedLabel == null || normalizedLabel.isBlank()) return null;
            return nodeType + ":" + normalizedLabel + ":" + port;
        }

        StringBuilder labelBuilder = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (i > 1) labelBuilder.append(":");
            labelBuilder.append(parts[i]);
        }
        String normalizedLabel = LabelNormalizer.normalizeLabel(labelBuilder.toString());
        if (normalizedLabel == null || normalizedLabel.isBlank()) return null;
        return nodeType + ":" + normalizedLabel;
    }

    // ===== HELPERS =====

    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Safely parse a nested config object from a Map using Jackson conversion.
     * Returns null if the key is absent, not a Map, or conversion fails.
     */
    @SuppressWarnings("unchecked")
    private static <T> T parseConfigSafe(Map<String, Object> data, String key, Class<T> clazz) {
        Object raw = data.get(key);
        if (raw == null || !(raw instanceof Map)) return null;
        try {
            return CONFIG_MAPPER.convertValue(raw, clazz);
        } catch (Exception e) {
            logger.warn("[parseCore] Failed to parse {} config: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Guardrail rules can arrive as List&lt;Map&gt; (frontend) or Map&lt;key,desc&gt; (builder tool).
     * Normalize to List&lt;Map&gt; for the Agent record.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseGuardrailRules(Object raw) {
        if (raw == null) return null;
        if (raw instanceof List) return (List<Map<String, Object>>) raw;
        if (raw instanceof Map<?, ?> map) {
            // Builder format: {"pii": "Block PII", "toxicity": "Block offensive"} → convert to list
            List<Map<String, Object>> result = new ArrayList<>();
            map.forEach((k, v) -> result.add(Map.of("id", String.valueOf(k), "type", String.valueOf(k),
                    "description", String.valueOf(v))));
            return result;
        }
        return null;
    }

    private static String safeString(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String key : List.of("text", "value", "name", "content", "label")) {
                Object extracted = map.get(key);
                if (extracted instanceof String) return (String) extracted;
            }
            return map.toString();
        }
        return String.valueOf(value);
    }

    private static String safeStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        String result = safeString(value);
        return result != null ? result : defaultValue;
    }
}
