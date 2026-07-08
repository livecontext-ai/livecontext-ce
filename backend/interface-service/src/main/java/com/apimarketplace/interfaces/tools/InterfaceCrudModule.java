package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.AgentListEnvelope;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.agent.tools.common.ToolRateLimiter;
import com.apimarketplace.interfaces.config.InterfaceAgentDefaultsConfig;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.interfaces.service.InterfaceTemplatePatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * CRUD module for the interface tool.
 * Interface-service native version - uses InterfaceService directly (no HTTP hop).
 * Handles: create, get, list, update, patch, delete actions.
 */
@Component
public class InterfaceCrudModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(InterfaceCrudModule.class);

    private final InterfaceService interfaceService;
    private final InterfaceAgentDefaultsConfig agentDefaults;

    private static final int MAX_CONSECUTIVE_UPDATES = 3;
    // patch is surgical (a few lines, not a full-template rewrite), so iterating is the
    // whole point - a tight update-style cap would defeat it. The guard still bounds
    // run-away loops at a generous ceiling, on a limiter separate from update. Edits
    // that fail to match write nothing and are refunded (see executePatch), so only
    // SUCCESSFUL patches count toward this cap.
    private static final int MAX_CONSECUTIVE_PATCHES = 10;
    // PR3 (2026-05-15): legacy cap (100) replaced by AgentListEnvelope.Caps.STANDARD.maxLimit=50
    // for cross-tool consistency (workflow.list / application.my / agent.list all use STANDARD).
    // Kept as a stub for git-blame searchability; remove in the next refactor pass.
    @SuppressWarnings("unused")
    private static final int MAX_LIST_LIMIT_LEGACY = 100;
    private final ToolRateLimiter updateLimiter = new ToolRateLimiter();
    private final ToolRateLimiter patchLimiter = new ToolRateLimiter();
    private final ToolRateLimiter createLimiter = new ToolRateLimiter();

    private static final Set<String> HANDLED_ACTIONS = Set.of("create", "get", "list", "update", "patch", "delete");

    public InterfaceCrudModule(InterfaceService interfaceService,
                               InterfaceAgentDefaultsConfig agentDefaults) {
        this.interfaceService = interfaceService;
        this.agentDefaults = agentDefaults;
    }

    /**
     * Resolves the per-turn interface-create cap via
     * {@link com.apimarketplace.agent.config.GuardOverrides#resolve}:
     * conversation-scope / caller-agent credential (via
     * {@code __chatMaxPerResourcePerTurn__}, propagated by
     * conversation-service/AgentContextBuilder) → YAML default. No direct
     * caller-agent lookup path here: interface-service has no agents table,
     * so the effective per-agent value arrives through credentials.
     */
    int resolveMaxPerResourcePerTurn(ToolExecutionContext context) {
        int fallback = agentDefaults.getMaxPerResourcePerTurn();
        Map<String, Object> credentials = context != null ? context.credentials() : null;
        return com.apimarketplace.agent.config.GuardOverrides.resolve(
            null, credentials,
            com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN,
            fallback);
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();

        // Access mode check (read/write)
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "interface", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        return Optional.of(switch (action) {
            case "create" -> executeCreate(parameters, tenantId, context);
            case "get" -> executeGet(parameters, tenantId, context);
            case "list" -> executeList(parameters, tenantId, context);
            case "update" -> executeUpdate(parameters, tenantId, context);
            case "patch" -> executePatch(parameters, tenantId, context);
            case "delete" -> executeDelete(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Create ====================

    private ToolExecutionResult executeCreate(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String name = getStringParam(parameters, "name");
        String description = getStringParam(parameters, "description");
        String type = getStringParam(parameters, "type");
        String htmlTemplate = getStringParam(parameters, "html_template");
        String cssTemplate = getStringParam(parameters, "css_template");
        String jsTemplate = getStringParam(parameters, "js_template");

        if (name == null || name.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "name is required");
        }

        // Route to slide creation if type=slide
        if ("slide".equals(type)) {
            return executeCreateSlide(parameters, tenantId, context, name, description);
        }

        if (htmlTemplate == null || htmlTemplate.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "html_template is REQUIRED for interface.\n\n" +
                "IMPORTANT: Use GENERIC variable names with pipe defaults (|), NOT workflow expressions.\n" +
                "The template is REUSABLE - mapping to workflow data happens on the workflow node.\n" +
                "ALWAYS provide all 3 templates: html_template, css_template, js_template.\n\n" +
                "EXAMPLE:\n" +
                "interface(action='create', name='Product Card', description='Displays product details',\n" +
                "  html_template='<div class=\"card\"><h2>{{title|My Product}}</h2><p>{{description|Product description here}}</p><span class=\"price\">{{price|0.00}} EUR</span></div>',\n" +
                "  css_template='.card { padding: 16px; font-family: sans-serif; } .price { font-weight: bold; color: green; } h2 { margin: 0 0 8px; }',\n" +
                "  js_template='// Conditional display\\ndocument.querySelectorAll(\"[data-var]\").forEach(el => { if (!el.textContent.trim()) el.style.display = \"none\"; });')\n\n" +
                "JS TEMPLATE TIP: In run mode, all resolved variables are available via window.__RESOLVED_DATA__.\n" +
                "Use it to access complex data (arrays, maps) safely - no JSON.parse needed.\n" +
                "Example: const data = window.__RESOLVED_DATA__; data.posts // array of posts\n\n" +
                "FILE DISPLAY TIP: For images/videos from download_file nodes, use the canonical FileRef in html_template:\n" +
                "  <img src=\"{{avatar}}\" /> - map 'avatar' to core:download.output.file on the workflow node.\n" +
                "  The renderer auto-injects an auth token (logged-in app) or HMAC signature (marketplace + share preview).");
        }

        // Check creation limit per message. Cap resolves via:
        // caller-agent override (through __chatMaxPerResourcePerTurn__) → YAML default.
        String turnId = context != null && context.variables() != null ? getTurnId(context.variables()) : null;
        int maxCreates = resolveMaxPerResourcePerTurn(context);
        if (turnId != null) {
            String createKey = tenantId + ":" + turnId;
            var limitResult = createLimiter.checkLimit(createKey, maxCreates,
                "LIMIT REACHED: You have already created " + maxCreates + " interfaces for this message.\n\n" +
                "WHAT TO DO:\n" +
                "1. Use interface(action='list') to see existing interfaces\n" +
                "2. Use interface(action='update', id='...') to modify an existing one\n" +
                "3. Ask the user: 'I've created several interfaces. Which one would you like me to refine?'\n\n" +
                "DO NOT create more interfaces. Work with what exists.");
            if (limitResult.isPresent()) return limitResult.get();

            log.info("[INTERFACE_CREATE] Creating interface {}/{} in turn {}",
                createLimiter.getCount(createKey), maxCreates, turnId);
        }

        try {
            // Direct service call - no HTTP hop. Stamp organization_id from
            // the MCP tool context so org-teammates can read the interface back.
            // Audit 2026-05-16 - prior code passed null for the 13th arg
            // (organizationId), losing the workspace tag.
            String orgId = context != null ? context.orgId() : null;
            InterfaceEntity result = interfaceService.createInterface(
                tenantId, name, description, htmlTemplate, cssTemplate, jsTemplate,
                null, null, null, null, false, null, orgId
            );

            if (result == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create interface: service returned null");
            }

            String interfaceId = result.getId().toString();
            String displayTitle = result.getName();

            // Auto-grant: agent gets access to the interface it just created
            ToolAccessControl.grantCreatedResource(context.credentials(), "interface", interfaceId);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("id", interfaceId);
            resultMap.put("name", result.getName());
            resultMap.put("description", result.getDescription() != null ? result.getDescription() : "");
            resultMap.put("status", "CREATED");
            resultMap.put("display", Map.of("type", "interface", "id", interfaceId, "title", displayTitle, "name", result.getName()));
            resultMap.put("marker", "[visualize:interface:" + interfaceId + "]");
            resultMap.put("message", "You successfully created interface '" + result.getName() + "' and it's now displayed to the user.");

            // Include template variables for mapping guidance
            List<String> templateVars = result.getTemplateVariables();
            if (templateVars != null && !templateVars.isEmpty()) {
                resultMap.put("template_variables", templateVars);
                String mappingExample = templateVars.stream()
                    .map(v -> "'" + v + "': '{{mcp:<previous_step>.output." + v + "}}'")
                    .reduce((a, b) -> a + ", " + b).orElse("");
                resultMap.put("NEXT_STEP", "Add to workflow WITH variable mapping in one call: " +
                    "workflow(action='add_node', type='interface', label='Display', " +
                    "params={interface_id: '" + interfaceId + "', variable_mapping: {" + mappingExample + "}}, " +
                    "connect_after='<previous_step>')");
            } else {
                resultMap.put("NEXT_STEP", "Add this interface to your workflow: " +
                    "workflow(action='add_node', type='interface', label='Display', " +
                    "params={interface_id: '" + interfaceId + "'}, connect_after='<previous_step>')");
            }

            // Add creation limit info
            if (turnId != null) {
                String createKey = tenantId + ":" + turnId;
                int createCount = createLimiter.getCount(createKey);
                resultMap.put("creates_in_message", createCount + "/" + maxCreates);
                if (createCount >= maxCreates) {
                    resultMap.put("LIMIT_REACHED", "You have reached the interface creation limit for this message. " +
                        "Use update action to modify existing interfaces instead of creating new ones.");
                }
            }

            Map<String, Object> metadata = Map.of("visualization", Map.of("type", "interface", "id", interfaceId, "title", displayTitle));
            return ToolExecutionResult.success(resultMap, metadata);
        } catch (com.apimarketplace.auth.client.entitlement.LimitExceededException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Service-layer validation (e.g. "name cannot exceed 255 characters", tenant mismatch).
            // Surface the message verbatim - it's user input, not an internal fault.
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create interface: " + e.getMessage());
        }
    }

    // ==================== Create Slide ====================

    private ToolExecutionResult executeCreateSlide(Map<String, Object> parameters, String tenantId,
                                                    ToolExecutionContext context, String name, String description) {
        String turnId = context != null && context.variables() != null ? getTurnId(context.variables()) : null;
        int maxCreates = resolveMaxPerResourcePerTurn(context);

        if (turnId != null) {
            String createKey = tenantId + ":" + turnId;
            var limitResult = createLimiter.checkLimit(createKey, maxCreates,
                "LIMIT REACHED: You have already created " + maxCreates + " interfaces for this message.");
            if (limitResult.isPresent()) return limitResult.get();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> slideData = (Map<String, Object>) parameters.get("slide_data");

            // Direct service call - no HTTP hop. Stamp organization_id from
            // the MCP context so org-teammates see the slide deck.
            String orgId = context != null ? context.orgId() : null;
            InterfaceEntity result = interfaceService.createSlideInterface(tenantId, name, description, slideData, orgId);
            if (result == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create slide deck: service returned null");
            }

            String interfaceId = result.getId().toString();
            String displayTitle = result.getName();

            // Auto-grant: agent gets access to the slide deck it just created
            ToolAccessControl.grantCreatedResource(context.credentials(), "interface", interfaceId);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("id", interfaceId);
            resultMap.put("name", result.getName());
            resultMap.put("description", result.getDescription() != null ? result.getDescription() : "");
            resultMap.put("type", "slide");
            resultMap.put("status", "CREATED");
            resultMap.put("display", Map.of("type", "slide", "id", interfaceId, "title", displayTitle, "name", result.getName()));
            resultMap.put("marker", "[visualize:slide:" + interfaceId + "]");
            resultMap.put("message", "You successfully created slide deck '" + result.getName() + "'. " +
                "Use interface(action='update', interface_id='" + interfaceId + "', slide_data={...}) to add/modify slides.");

            int slideCount = 0;
            if (result.getData() != null && result.getData().get("slides") instanceof List<?> slides) {
                slideCount = slides.size();
            }
            resultMap.put("slide_count", slideCount);

            if (turnId != null) {
                String createKey = tenantId + ":" + turnId;
                resultMap.put("creates_in_message", createLimiter.getCount(createKey) + "/" + maxCreates);
            }

            Map<String, Object> metadata = Map.of("visualization", Map.of("type", "slide", "id", interfaceId, "title", displayTitle));
            return ToolExecutionResult.success(resultMap, metadata);
        } catch (com.apimarketplace.auth.client.entitlement.LimitExceededException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create slide deck: " + e.getMessage());
        }
    }

    // ==================== Get ====================

    private static final String INTERFACE_ID_MISSING = """
        interface_id is required.
        - To LIST existing interfaces: interface(action='list')
        - To CREATE new UI card: interface(action='create', name='...', description='What it displays', html_template='<div>{{title|My Product}}</div>')
        """;

    private static String interfaceNotFoundHint(String rawValue) {
        return "No interface matches '" + rawValue + "'. " +
            "Provide a valid UUID or an exact interface name. " +
            "Use interface(action='list') to see available interfaces.";
    }

    private ToolExecutionResult executeGet(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        UUID id = getUuidParam(parameters, "interface_id");

        // Fallback: if not a valid UUID, try resolving by name
        if (id == null) {
            String rawValue = getStringParam(parameters, "interface_id");
            if (rawValue != null && !rawValue.isBlank()) {
                String humanized = rawValue.replace("_", " ");
                String orgId = context != null ? context.orgId() : null;
                String orgRole = context != null ? context.orgRole() : null;
                var all = interfaceService.listInterfaces(tenantId, null, orgId, orgRole);
                id = all.stream()
                    .filter(i -> rawValue.equalsIgnoreCase(i.getName())
                              || humanized.equalsIgnoreCase(i.getName()))
                    .map(InterfaceEntity::getId)
                    .findFirst()
                    .orElse(null);
                if (id != null) {
                    log.debug("Resolved interface name '{}' to UUID {}", rawValue, id);
                }
            }
        }

        if (id == null) {
            String rawValue = getStringParam(parameters, "interface_id");
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, rawValue == null || rawValue.isBlank()
                    ? INTERFACE_ID_MISSING
                    : interfaceNotFoundHint(rawValue));
        }

        List<String> allowedInterfaceIds = getAllowedInterfaceIds(context);
        if (allowedInterfaceIds != null && !allowedInterfaceIds.contains(id.toString())) {
            log.info("Agent restriction: interface {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This interface is not in your approved interface list.");
        }

        try {
            // Round-13B: thread orgId so the lookup hits the strict-isolation
            // finder pair (#150). MCP tool fleet runs inside an org workspace
            // via ToolExecutionContext.orgId(); cross-scope id → empty Optional
            // (404), same shape as the legacy tenant-only path.
            String orgIdForGet = context != null ? context.orgId() : null;
            Optional<InterfaceEntity> opt = interfaceService.getInterface(id, tenantId, orgIdForGet);
            if (opt.isEmpty()) return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Interface not found: " + id);
            InterfaceEntity entity = opt.get();

            String interfaceId = entity.getId().toString();

            Map<String, Object> getResult = new LinkedHashMap<>();
            getResult.put("id", interfaceId);
            getResult.put("name", entity.getName());
            getResult.put("type", entity.getInterfaceType() != null ? entity.getInterfaceType() : "html");
            getResult.put("description", entity.getDescription() != null ? entity.getDescription() : "");

            if ("slide".equals(entity.getInterfaceType())) {
                getResult.put("slide_data", entity.getData());
                int slideCount = 0;
                if (entity.getData() != null && entity.getData().get("slides") instanceof List<?> slides) {
                    slideCount = slides.size();
                }
                getResult.put("slide_count", slideCount);
                getResult.put("marker", "[visualize:slide:" + interfaceId + "]");
            } else {
                getResult.put("htmlTemplate", entity.getHtmlTemplate() != null ? entity.getHtmlTemplate() : "");
                if (entity.getCssTemplate() != null && !entity.getCssTemplate().isBlank()) {
                    getResult.put("cssTemplate", entity.getCssTemplate());
                }
                if (entity.getJsTemplate() != null && !entity.getJsTemplate().isBlank()) {
                    getResult.put("jsTemplate", entity.getJsTemplate());
                }
                if (entity.getTargetTable() != null && !entity.getTargetTable().isBlank()) {
                    getResult.put("targetTable", entity.getTargetTable());
                }
                getResult.put("marker", "[visualize:interface:" + interfaceId + "]");
            }
            getResult.put("isActive", entity.getIsActive());
            return ToolExecutionResult.success(getResult);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to get interface: " + e.getMessage());
        }
    }

    // ==================== List ====================

    private ToolExecutionResult executeList(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        // interface.list - STANDARD caps. Scope IS the tenant - no general filter.
        // Caps.STANDARD (max=50) is stricter than the legacy MAX_LIST_LIMIT=100; this is
        // intentionally aligned with workflow.list / application.my for consistency, and the
        // legacy ceiling is preserved as a comment for future audits. Callers passing
        // limit>50 are silently clamped (caps invariant) - agent UX favors a consistent
        // ceiling across all list actions over per-tool surprise capacity.
        // `query` (name/description substring) is the one refinement filter, so the
        // `refine` hint suggests it on large result sets.
        String query = getStringParam(parameters, "query");
        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.STANDARD, "interfaces", "interfaces", "interfaces")
                .withSuggestedFilters(List.of("query"));
        AgentListEnvelope.Bounds bounds;
        try {
            Set<String> activeFilters = hasQuery(query) ? Set.of("query") : Set.of();
            bounds = AgentListEnvelope.readBounds(parameters, spec, activeFilters);
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            String orgId = context != null ? context.orgId() : null;
            String orgRole = context != null ? context.orgRole() : null;
            List<InterfaceEntity> allInterfaces = interfaceService.listInterfaces(tenantId, null, orgId, orgRole);

            // Strip system-owned auto-created interface rows (agent_browse,
            // image_generation, legacy web_search). These are persisted by
            // tool-call pipelines, not user-managed resources - surfacing
            // them through interface.list pollutes the agent's context with
            // dozens of irrelevant entries per conversation (prod incident
            // 2026-05-22: tenant 5 saw 78 interfaces, 50 of which were
            // legacy web_search rows from previous chats).
            allInterfaces = allInterfaces.stream()
                .filter(i -> {
                    String t = i.getInterfaceType();
                    return t == null || "html".equals(t);
                })
                .toList();

            List<String> allowedInterfaceIds = getAllowedInterfaceIds(context);
            if (allowedInterfaceIds != null) {
                allInterfaces = allInterfaces.stream()
                    .filter(i -> i.getId() != null && allowedInterfaceIds.contains(i.getId().toString()))
                    .toList();
                log.info("Agent restriction: filtered interfaces to {}/{} allowed",
                    allInterfaces.size(), allowedInterfaceIds.size());
            }

            // Text search: case-insensitive substring over name + description, applied
            // BEFORE pagination so total/hasMore reflect the filtered set.
            if (hasQuery(query)) {
                allInterfaces = allInterfaces.stream()
                    .filter(i -> matchesQuery(query, i.getName(), i.getDescription()))
                    .toList();
            }

            long total = allInterfaces.size();

            List<Map<String, Object>> summaries = allInterfaces.stream()
                .skip(bounds.offset()).limit(bounds.limit())
                .map(entity -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", entity.getId().toString());
                    m.put("name", entity.getName());
                    m.put("type", entity.getInterfaceType() != null ? entity.getInterfaceType() : "html");
                    m.put("description", entity.getDescription() != null ? entity.getDescription() : "");
                    m.put("isActive", entity.getIsActive());
                    return m;
                }).toList();

            return ToolExecutionResult.success(
                    AgentListEnvelope.paginateProjection(summaries, bounds, total, spec));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to list interfaces: " + e.getMessage());
        }
    }

    // ==================== Update ====================

    private ToolExecutionResult executeUpdate(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        UUID id = getUuidParam(parameters, "interface_id");
        if (id == null) {
            String rawValue = getStringParam(parameters, "interface_id");
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, rawValue == null || rawValue.isBlank()
                    ? INTERFACE_ID_MISSING
                    : interfaceNotFoundHint(rawValue));
        }

        List<String> allowedInterfaceIds = getAllowedInterfaceIds(context);
        if (allowedInterfaceIds != null && !allowedInterfaceIds.contains(id.toString())) {
            log.info("Agent restriction: interface {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This interface is not in your approved interface list.");
        }

        String name = getStringParam(parameters, "name");
        String description = getStringParam(parameters, "description");
        String htmlTemplate = getStringParam(parameters, "html_template");
        String cssTemplate = getStringParam(parameters, "css_template");
        String jsTemplate = getStringParam(parameters, "js_template");

        @SuppressWarnings("unchecked")
        Map<String, Object> slideData = (Map<String, Object>) parameters.get("slide_data");

        // Reject empty updates BEFORE incrementing the rate-limit counter - otherwise a
        // no-op call silently burns one of the 3 allowed updates for this interface.
        if (name == null && description == null && htmlTemplate == null
                && cssTemplate == null && jsTemplate == null && slideData == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "No fields to update. Provide at least one of: " +
                "name, description, html_template, css_template, js_template, slide_data.");
        }

        String updateKey = tenantId + ":" + id;
        var limitResult = updateLimiter.checkLimit(updateKey, MAX_CONSECUTIVE_UPDATES,
            "STOP: You have updated this interface " + MAX_CONSECUTIVE_UPDATES + " times already. " +
            "The interface is COMPLETE and visible to the user. " +
            "DO NOT call interface(action='update') again. " +
            "Instead, ask the user: 'The interface is ready. Would you like any changes?'");
        if (limitResult.isPresent()) return limitResult.get();

        int currentCount = updateLimiter.getCount(updateKey);

        try {
            // Round-13B: thread (orgId, orgRole) so the lookup + deny-list both
            // route through the #150 strict-isolation pair. The MCP tool fleet
            // runs inside an org workspace via ToolExecutionContext, so the
            // org-aware overloads are the correct path.
            String orgIdForUpdate = context != null ? context.orgId() : null;
            String orgRoleForUpdate = context != null ? context.orgRole() : null;

            // Check if this is a slide interface
            Optional<InterfaceEntity> existingOpt = interfaceService.getInterface(id, tenantId, orgIdForUpdate);
            if (existingOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Interface not found: " + id);
            }
            InterfaceEntity existing = existingOpt.get();
            boolean isSlideInterface = "slide".equals(existing.getInterfaceType());

            InterfaceEntity result;

            if (isSlideInterface && slideData != null) {
                // Slide-specific update
                result = interfaceService.updateSlideData(id, tenantId, name, description, slideData);
            } else {
                // HTML interface update - org-aware overload (#150) threads
                // (orgId, orgRole) into the strict-isolation finder + deny-list.
                result = interfaceService.updateInterface(id, tenantId, orgIdForUpdate, orgRoleForUpdate,
                    name, description, htmlTemplate, cssTemplate, jsTemplate,
                    null, null, null, null, null, null, null);
            }

            if (result == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to update interface: service returned null");
            }

            Map<String, Object> responseMap = new LinkedHashMap<>();
            responseMap.put("id", result.getId().toString());
            responseMap.put("name", result.getName());
            responseMap.put("description", result.getDescription() != null ? result.getDescription() : "");
            responseMap.put("status", "UPDATED");
            responseMap.put("updateCount", currentCount);
            responseMap.put("maxUpdates", MAX_CONSECUTIVE_UPDATES);

            if (isSlideInterface) {
                int slideCount = 0;
                if (result.getData() != null && result.getData().get("slides") instanceof List<?> slides) {
                    slideCount = slides.size();
                }
                responseMap.put("type", "slide");
                responseMap.put("slide_count", slideCount);
                responseMap.put("display", Map.of("type", "slide", "id", result.getId().toString(), "title", result.getName()));
                responseMap.put("marker", "[visualize:slide:" + result.getId() + "]");
                responseMap.put("message", "Slide deck '" + result.getName() + "' updated successfully (" + slideCount + " slides, update " + currentCount + "/" + MAX_CONSECUTIVE_UPDATES + ").");
            } else {
                responseMap.put("message", "Interface '" + result.getName() + "' updated successfully (update " + currentCount + "/" + MAX_CONSECUTIVE_UPDATES + ").");
            }
            responseMap.put("STOP", "DO NOT call interface(action='update') again unless the user explicitly requests changes.");
            responseMap.put("TASK_COMPLETE", true);
            responseMap.put("nextAction", "Tell the user: 'The interface is ready and displayed. Let me know if you want any changes.'");

            String interfaceId = result.getId().toString();
            String displayTitle = result.getName();
            String vizType = isSlideInterface ? "slide" : "interface";
            Map<String, Object> metadata = Map.of("visualization", Map.of("type", vizType, "id", interfaceId, "title", displayTitle));

            return ToolExecutionResult.success(responseMap, metadata);
        } catch (com.apimarketplace.interfaces.service.InterfaceImmutableException e) {
            // Match the workflow-side error shape: RESOURCE_CONFLICT + structured code +
            // an agent-callable next_action. The agent cannot make HTTP calls, so the
            // hint must reference a tool action (`interface(action='create', …)`) not a
            // REST path.
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("code", "INTERFACE_IMMUTABLE");
            meta.put("next_action",
                "If you need an editable copy, call interface(action='create', ...) with the desired "
                + "html/css/js - the acquired interface stays as installed. Otherwise, tell the user "
                + "the acquired application's interface cannot be modified.");
            return ToolExecutionResult.failure(
                    com.apimarketplace.agent.tools.ToolErrorCode.RESOURCE_CONFLICT,
                    e.getMessage(),
                    meta);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to update interface: " + e.getMessage());
        }
    }

    // ==================== Patch ====================

    /**
     * Surgical search/replace on ONE template ({@code html} | {@code css} | {@code js})
     * - the "edit" model coding agents use, so changing a few lines doesn't require
     * re-sending the whole template. Delegates the actual matching to
     * {@link InterfaceTemplatePatcher} (all-or-nothing) and the persistence to
     * {@code InterfaceService.patchInterface} (same guards as a full update).
     */
    private ToolExecutionResult executePatch(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        UUID id = getUuidParam(parameters, "interface_id");
        if (id == null) {
            String rawValue = getStringParam(parameters, "interface_id");
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, rawValue == null || rawValue.isBlank()
                    ? INTERFACE_ID_MISSING
                    : interfaceNotFoundHint(rawValue));
        }

        List<String> allowedInterfaceIds = getAllowedInterfaceIds(context);
        if (allowedInterfaceIds != null && !allowedInterfaceIds.contains(id.toString())) {
            log.info("Agent restriction: interface {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This interface is not in your approved interface list.");
        }

        String target = getStringParam(parameters, "target");
        if (target == null || target.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "target is required for patch: 'html', 'css', or 'js' (which template to edit).");
        }
        String normalizedTarget = target.trim().toLowerCase();
        if (!normalizedTarget.equals("html") && !normalizedTarget.equals("css") && !normalizedTarget.equals("js")) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Invalid target '" + target + "'. Must be one of: html, css, js.");
        }

        List<InterfaceTemplatePatcher.Edit> edits = parseEdits(parameters.get("edits"));
        if (edits == null || edits.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "edits is required for patch: a non-empty list of {old, new} objects. " +
                "Each 'old' must be the EXACT current text to replace (copy it verbatim, whitespace included). " +
                "Example: edits=[{old:'<h1>Hello</h1>', new:'<h1>Welcome</h1>'}]");
        }

        boolean replaceAll = parseBool(parameters.get("replace_all"));
        String patchKey = tenantId + ":" + id;

        try {
            String orgId = context != null ? context.orgId() : null;
            String orgRole = context != null ? context.orgRole() : null;

            // Load + reject non-patchable targets BEFORE touching the rate-limit counter,
            // so a wrong id or a slide deck (which write nothing) never burns a slot - the
            // same "no-persist, no-cost" rule that refunds a failed match below. (Loading
            // first also lets us reject slide decks with a clear redirect instead of a
            // confusing NOT_FOUND from the patcher.)
            Optional<InterfaceEntity> existingOpt = interfaceService.getInterface(id, tenantId, orgId);
            if (existingOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Interface not found: " + id);
            }
            if ("slide".equals(existingOpt.get().getInterfaceType())) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "patch is not supported for slide decks. Use interface(action='update', interface_id='" + id +
                    "', slide_data={...}) to change slides.");
            }

            // Rate-limit on a generous counter, separate from update. Only a real patch
            // attempt on a valid interface counts; a non-matching edit is refunded below.
            var limitResult = patchLimiter.checkLimit(patchKey, MAX_CONSECUTIVE_PATCHES,
                "STOP: You have patched this interface " + MAX_CONSECUTIVE_PATCHES + " times. " +
                "If it still isn't right, ask the user what they want changed rather than continuing to edit.");
            if (limitResult.isPresent()) return limitResult.get();
            int currentCount = patchLimiter.getCount(patchKey);

            InterfaceEntity result = interfaceService.patchInterface(id, tenantId, orgId, orgRole,
                normalizedTarget, edits, replaceAll);
            if (result == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to patch interface: service returned null");
            }

            String interfaceId = result.getId().toString();
            String displayTitle = result.getName();

            Map<String, Object> responseMap = new LinkedHashMap<>();
            responseMap.put("id", interfaceId);
            responseMap.put("name", result.getName());
            responseMap.put("status", "PATCHED");
            responseMap.put("target", normalizedTarget);
            responseMap.put("edits_applied", edits.size());
            responseMap.put("patchCount", currentCount);
            responseMap.put("maxPatches", MAX_CONSECUTIVE_PATCHES);
            responseMap.put("display", Map.of("type", "interface", "id", interfaceId, "title", displayTitle, "name", result.getName()));
            responseMap.put("marker", "[visualize:interface:" + interfaceId + "]");
            responseMap.put("message", "Interface '" + result.getName() + "' patched (" + edits.size() +
                " edit(s) on " + normalizedTarget + ", patch " + currentCount + "/" + MAX_CONSECUTIVE_PATCHES + ").");
            responseMap.put("nextAction", "The change is displayed to the user. Patch again only if more changes are needed.");

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("visualization", Map.of("type", "interface", "id", interfaceId, "title", displayTitle));
            // Red/green diff card of the applied edits (same `diff` shape repo edit/write/diff
            // emits → rendered by the frontend DiffView). Reuses the existing metadata pipeline.
            Map<String, Object> diff = buildPatchDiff(result.getName(), normalizedTarget, edits);
            if (diff != null) metadata.put("diff", diff);
            return ToolExecutionResult.success(responseMap, metadata);
        } catch (InterfaceTemplatePatcher.PatchException e) {
            // A non-matching edit wrote NOTHING - treat it as a no-op and refund the
            // slot so the agent can re-get and retry. Without this, mis-copied 'old'
            // text (the most common recoverable case) would burn the cap without a
            // single successful edit, defeating the "iterate freely" rationale.
            patchLimiter.decrement(patchKey);
            return patchMatchFailure(e, id);
        } catch (com.apimarketplace.interfaces.service.InterfaceImmutableException e) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("code", "INTERFACE_IMMUTABLE");
            meta.put("next_action",
                "If you need an editable copy, call interface(action='create', ...) with the desired " +
                "html/css/js - the acquired interface stays as installed. Otherwise, tell the user " +
                "the acquired application's interface cannot be modified.");
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_CONFLICT, e.getMessage(), meta);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to patch interface: " + e.getMessage());
        }
    }

    /**
     * Build a red/green diff card from the applied patch edits - one hunk per edit (the
     * {@code old} text as deletions, the {@code new} text as additions). Dependency-free and
     * faithful to exactly what the agent changed. The shape matches what {@code repo}
     * edit/write/diff emit (frontend DiffView): {@code { files: [{ path, status, additions,
     * deletions, language, unifiedDiff }] }}.
     */
    private static Map<String, Object> buildPatchDiff(String name, String target,
                                                      List<InterfaceTemplatePatcher.Edit> edits) {
        if (edits == null || edits.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int additions = 0, deletions = 0, i = 1;
        for (InterfaceTemplatePatcher.Edit e : edits) {
            String[] oldLines = splitLines(e.oldText());
            String[] newLines = splitLines(e.newText());
            sb.append("@@ ").append(target).append(" edit ").append(i++).append(" @@\n");
            for (String l : oldLines) { sb.append('-').append(l).append('\n'); deletions++; }
            for (String l : newLines) { sb.append('+').append(l).append('\n'); additions++; }
        }
        String safeName = (name == null || name.isBlank()) ? "interface" : name;
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", safeName + "." + target);
        file.put("status", "modified");
        file.put("additions", additions);
        file.put("deletions", deletions);
        file.put("language", target);
        file.put("unifiedDiff", maskInterfaceSecrets(sb.toString()));
        return Map.of("files", List.of(file));
    }

    /**
     * Best-effort masking of token-shaped secrets that could ride a patched template into
     * the diff card / persisted chat - parity with the {@code repo} tool's masking. Targets
     * high-signal PREFIXED tokens + Bearer + connection-string passwords only, so ordinary
     * HTML/CSS/JS source is never corrupted. Not a guarantee for arbitrary secrets.
     */
    private static String maskInterfaceSecrets(String s) {
        if (s == null || s.isEmpty()) return s;
        return s
            .replaceAll("sk_live_[A-Za-z0-9]+", "sk_live_***")
            .replaceAll("sk_test_[A-Za-z0-9]+", "sk_test_***")
            .replaceAll("rk_(live|test)_[A-Za-z0-9]+", "rk_$1_***")
            .replaceAll("whsec_[A-Za-z0-9]+", "whsec_***")
            .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}", "$1***")
            .replaceAll("\\b(A[KS]IA)[0-9A-Z]{16}\\b", "$1***")
            .replaceAll("\\bAIza[0-9A-Za-z_-]{20,}", "AIza***")
            .replaceAll("\\bsk-(ant-|proj-)?[A-Za-z0-9_-]{16,}", "sk-$1***")
            .replaceAll("(?i)\\b([a-z][a-z0-9+.-]*://[^\\s:@/]*:)([^\\s@/]{1,256})@", "$1***@");
    }

    /** Split into lines, dropping a single trailing newline so "a\nb\n" → [a, b] (no phantom blank). */
    private static String[] splitLines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        String t = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        return t.split("\n", -1);
    }

    /**
     * Turn a {@link InterfaceTemplatePatcher.PatchException} into an agent-recoverable
     * failure: a structured {@code code}, the offending edit index, and a next-action
     * that points the agent at {@code interface(action='get')} to re-read the exact
     * current content (the only way it can build a verbatim {@code old} string).
     */
    private ToolExecutionResult patchMatchFailure(InterfaceTemplatePatcher.PatchException e, UUID id) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("code", "PATCH_" + e.code().name());
        if (e.editIndex() >= 0) meta.put("edit_index", e.editIndex());
        if (e.matchCount() > 0) meta.put("match_count", e.matchCount());
        String getCall = "interface(action='get', interface_id='" + id + "')";
        // Every recoverable patch error names the next callable step, not just what went wrong.
        switch (e.code()) {
            case NOT_FOUND -> meta.put("next_action",
                "Call " + getCall + " to see the EXACT current content, then retry with 'old' copied verbatim " +
                "(indentation and whitespace must match). If the template has no such text yet (e.g. empty css/js), " +
                "use interface(action='update') to set it instead.");
            case AMBIGUOUS -> meta.put("next_action",
                "Call " + getCall + ", then add surrounding lines to 'old' to make it unique (preferred - edits only " +
                "the one you mean). Set replace_all=true ONLY if you intend to change ALL " + e.matchCount() + " occurrences.");
            case EMPTY_OLD -> meta.put("next_action",
                "Put the exact current text to replace in 'old' (copy it from " + getCall + ").");
            case NO_OP -> meta.put("next_action",
                "'old' and 'new' are identical - set 'new' to the intended replacement, or drop this edit.");
            default -> { /* EMPTY_EDITS is rejected earlier in executePatch - no extra hint needed */ }
        }
        return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, e.getMessage(), meta);
    }

    /**
     * Parse the {@code edits} param into {@link InterfaceTemplatePatcher.Edit} records.
     * Liberal in what it accepts (LLM-friendly): each item is an object whose
     * find/replace pair may be keyed {@code old}/{@code new} (preferred), or the aliases
     * {@code old_string}/{@code new_string} or {@code search}/{@code replace}. A non-list
     * input, or items that aren't objects, yield {@code null}/skipped so the caller emits
     * the "edits is required" guidance.
     */
    private List<InterfaceTemplatePatcher.Edit> parseEdits(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<InterfaceTemplatePatcher.Edit> edits = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String oldText = firstNonNull(map, "old", "old_string", "search");
            String newText = firstNonNull(map, "new", "new_string", "replace");
            // newText may legitimately be "" (delete) - only oldText is mandatory here;
            // the patcher enforces the rest (empty-old, no-op, not-found, ambiguous).
            edits.add(new InterfaceTemplatePatcher.Edit(oldText, newText == null ? "" : newText));
        }
        return edits.isEmpty() ? null : edits;
    }

    private static String firstNonNull(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v instanceof String s) return s;
        }
        return null;
    }

    private static boolean parseBool(Object raw) {
        if (raw instanceof Boolean b) return b;
        return raw != null && "true".equalsIgnoreCase(String.valueOf(raw));
    }

    // ==================== Delete ====================

    private ToolExecutionResult executeDelete(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        UUID id = getUuidParam(parameters, "interface_id");
        if (id == null) {
            String rawValue = getStringParam(parameters, "interface_id");
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, rawValue == null || rawValue.isBlank()
                    ? INTERFACE_ID_MISSING
                    : interfaceNotFoundHint(rawValue));
        }

        List<String> allowedInterfaceIds = getAllowedInterfaceIds(context);
        if (allowedInterfaceIds != null && !allowedInterfaceIds.contains(id.toString())) {
            log.info("Agent restriction: interface {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This interface is not in your approved interface list.");
        }

        try {
            // Round-13B: thread (orgId, orgRole) so the lookup + delete both
            // route through the #150 strict-isolation pair. The MCP tool fleet
            // runs inside an org workspace via ToolExecutionContext.
            String orgIdForDelete = context != null ? context.orgId() : null;
            String orgRoleForDelete = context != null ? context.orgRole() : null;

            Optional<InterfaceEntity> existingOpt = interfaceService.getInterface(id, tenantId, orgIdForDelete);
            if (existingOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Interface not found: " + id);
            }
            String deletedName = existingOpt.get().getName();

            interfaceService.deleteInterface(id, tenantId, orgIdForDelete, orgRoleForDelete);
            return ToolExecutionResult.success(Map.of(
                "id", id.toString(), "name", deletedName, "status", "DELETED",
                "message", "You successfully deleted interface '" + deletedName + "'. To see remaining interfaces: interface(action='list')"
            ));
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to delete interface: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private List<String> getAllowedInterfaceIds(ToolExecutionContext context) {
        if (context == null || context.variables() == null) return null;
        Object allowed = context.variables().get("allowedInterfaceIds");
        if (allowed instanceof List) return (List<String>) allowed;
        return null;
    }
}
