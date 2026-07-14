package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.AgentListEnvelope;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.agent.tools.common.ToolRateLimiter;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * CRUD module for the agent tool - agent-service native version.
 * Uses AgentService and SkillService directly (no HTTP hop via AgentClient).
 * Handles: create, get, list, update, delete actions.
 */
@Slf4j
@Component
public class AgentCrudModule implements ToolModule {

    private final AgentService agentService;
    private final SkillService skillService;
    private final AgentWebhookTokenService webhookTokenService;
    private final AgentDefaultsConfig agentDefaults;
    private final ModelCatalogService modelCatalogService;
    private final RestTemplate restTemplate;
    private final String triggerServiceUrl;
    private final String webhookBaseUrl;

    public AgentCrudModule(AgentService agentService, SkillService skillService,
                           AgentWebhookTokenService webhookTokenService,
                           AgentDefaultsConfig agentDefaults,
                           ModelCatalogService modelCatalogService,
                           RestTemplate restTemplate,
                           @Value("${services.trigger-service.url:http://localhost:8091}") String triggerServiceUrl,
                           @Value("${agent.webhook.base-url:http://localhost:8080}") String webhookBaseUrl) {
        this.agentService = agentService;
        this.skillService = skillService;
        this.webhookTokenService = webhookTokenService;
        this.agentDefaults = agentDefaults;
        this.modelCatalogService = modelCatalogService;
        this.restTemplate = restTemplate;
        this.triggerServiceUrl = triggerServiceUrl;
        this.webhookBaseUrl = webhookBaseUrl;
    }

    private static final int MAX_CONSECUTIVE_UPDATES = 3;
    private final ToolRateLimiter updateLimiter = new ToolRateLimiter();

    /**
     * Resolves the per-turn create cap for agent resources via
     * {@link com.apimarketplace.agent.config.GuardOverrides#resolve} with precedence:
     * caller-agent override (V100 column) → conversation-scope chatConfig
     * (via {@code __chatMaxPerResourcePerTurn__} credential) → YAML default.
     *
     * <p>The cap is uniform across resource types (agent/skill/sub_agent/interface/
     * workflow/table) and applied separately per type - i.e. "up to N agents AND
     * up to N skills AND up to N …".
     *
     * <p>Soft-fail on any lookup error - a transient agent-service issue must never
     * short-circuit the guard, so we degrade to the YAML default.
     */
    int resolveMaxPerResourcePerTurn(ToolExecutionContext context) {
        int fallback = agentDefaults.getMaxPerResourcePerTurn();
        Map<String, Object> credentials = context != null ? context.credentials() : null;
        Integer agentOverride = null;
        if (credentials != null) {
            Object raw = credentials.get("__agentId__");
            if (raw != null) {
                try {
                    UUID callerId = UUID.fromString(raw.toString());
                    Optional<AgentEntity> entityOpt = agentService.findById(callerId);
                    agentOverride = entityOpt.map(AgentEntity::getMaxPerResourcePerTurn).orElse(null);
                } catch (Exception e) {
                    log.debug("[AGENT_CREATE] Failed to resolve per-agent maxPerResourcePerTurn, falling back: {}", e.toString());
                }
            }
        }
        return com.apimarketplace.agent.config.GuardOverrides.resolve(
            agentOverride, credentials,
            com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN,
            fallback);
    }
    /**
     * Create-limiter TTL. Each counter entry expires {@value} minutes after the FIRST
     * create in its key - this is a <em>fixed</em> window starting from the first
     * create, not a true sliding-last-N-minutes window (see
     * {@code ToolRateLimiter.CounterEntry.isExpired}). In practice this is enough to
     * prevent the session-wide lockout described in bug #4, because a well-behaved
     * agent only burst-creates in short bursts.
     *
     * <p><strong>Horizontal-scaling / restart caveat:</strong> the underlying
     * {@link com.apimarketplace.agent.tools.common.ToolRateLimiter} is an in-memory
     * {@code ConcurrentHashMap}. When agent-service runs with multiple pods, the cap
     * is per-pod, and it is also wiped on service restart (so a crash-loop effectively
     * bypasses the cap). A follow-up must migrate this to a Redis-backed counter if
     * the cap is critical; currently it is a best-effort safety net.</p>
     */
    private static final long CREATE_LIMITER_TTL_MINUTES = 5;

    private final ToolRateLimiter createLimiter = new ToolRateLimiter(CREATE_LIMITER_TTL_MINUTES);

    private static final Set<String> HANDLED_ACTIONS = Set.of("create", "get", "list", "update", "delete");

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of(); // Definitions centralized in AgentToolsProvider
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
        var accessDenied = com.apimarketplace.agent.config.ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "agent", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        return Optional.of(switch (action) {
            case "create" -> executeCreate(parameters, tenantId, context);
            case "get" -> executeGet(parameters, tenantId, context);
            case "list" -> executeList(parameters, tenantId, context);
            case "update" -> executeUpdate(parameters, tenantId, context);
            case "delete" -> executeDelete(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Create ====================

    /**
     * Validate the optional {@code compaction_after_turns} tool param BEFORE any
     * create/update, so an invalid cadence fails fast (a post-create throw would
     * leave an orphaned agent). A present, non-null value must coerce to an integer
     * {@code >= 1}; an absent key or an explicit null (clear → inherit) pass.
     */
    private Optional<ToolExecutionResult> validateCompactionCadence(Map<String, Object> p) {
        if (p.containsKey("compaction_after_turns")) {
            Object raw = p.get("compaction_after_turns");
            Integer cadence = coerceInt(raw);
            if (raw != null && (cadence == null || cadence < 1)) {
                return Optional.of(ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "'compaction_after_turns' must be an integer >= 1 (or omit it to inherit)."));
            }
        }
        return Optional.empty();
    }

    /** Coerce a tool param to Boolean (native Boolean or "true"/"false" String); null otherwise. */
    private static Boolean coerceBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s.trim())) return Boolean.FALSE;
        }
        return null;
    }

    /** Coerce a tool param to Integer (native Number or numeric String); null otherwise. */
    private static Integer coerceInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.valueOf(s.trim()); } catch (NumberFormatException ignored) { /* not numeric */ }
        }
        return null;
    }

    private ToolExecutionResult executeCreate(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String name = getStringParam(p, "name");
        String description = getStringParam(p, "description");
        String systemPrompt = getStringParam(p, "system_prompt");
        String avatar = getStringParam(p, "avatar");
        String modelProvider = getStringParam(p, "model_provider");
        String modelName = getStringParam(p, "model_name");
        BigDecimal temperature = getDecimalParam(p, "temperature");
        Integer maxTokens = getIntParam(p, "max_tokens");
        Integer maxIterations = getIntParam(p, "max_iterations");
        Integer executionTimeout = getIntParam(p, "execution_timeout");
        Integer inactivityTimeout = getIntParam(p, "inactivity_timeout");
        String toolsMode = getStringParam(p, "tools_mode");
        Object toolsList = p.get("tools");
        Object workflowsList = p.get("workflows");
        Object applicationsList = p.get("applications");
        Object tablesList = p.get("tables");
        Object interfacesList = p.get("interfaces");
        Object agentsList = p.get("agents");
        Boolean webSearch = (Boolean) p.get("web_search");
        String tableAccessModeParam = getStringParam(p, "table_access_mode");
        String workflowAccessModeParam = getStringParam(p, "workflow_access_mode");
        String interfaceAccessModeParam = getStringParam(p, "interface_access_mode");
        String agentAccessModeParam = getStringParam(p, "agent_access_mode");
        String applicationAccessModeParam = getStringParam(p, "application_access_mode");
        String skillAccessModeParam = getStringParam(p, "skill_access_mode");
        String fileAccessModeParam = getStringParam(p, "file_access_mode");
        // Explicit GRANT scope (none/all/custom). Authoritative when provided - the only way to
        // express grant='all' (the list params can only express none/custom). Omitted = derive from list.
        String workflowsGrantParam = getStringParam(p, "workflows_grant");
        String applicationsGrantParam = getStringParam(p, "applications_grant");
        String tablesGrantParam = getStringParam(p, "tables_grant");
        String interfacesGrantParam = getStringParam(p, "interfaces_grant");
        String agentsGrantParam = getStringParam(p, "agents_grant");
        UUID workflowId = getUuidParam(p, "workflow_id");
        Long dataSourceId = getLongParam(p, "datasource_id");
        UUID conversationId = getUuidParam(p, "conversation_id");
        Boolean isPublic = (Boolean) p.get("is_public");
        Boolean isActive = (Boolean) p.get("is_active");
        Object skillIdsList = p.get("skill_ids");

        // Webhook config
        Boolean webhookEnabled = (Boolean) p.get("webhook_enabled");
        Boolean webhookMemory = (Boolean) p.get("webhook_memory");

        // Schedule config
        String scheduleCron = getStringParam(p, "schedule_cron");
        String scheduleTimezone = getStringParam(p, "schedule_timezone");
        Integer scheduleMaxExecutions = getIntParam(p, "schedule_max_executions");
        String schedulePromptParam = getStringParam(p, "schedule_prompt");
        Boolean scheduleMemory = (Boolean) p.get("schedule_memory");

        // Secure defaults: resource lists always default to [] (no access) when null.
        // Prevents security bypass via explicit null (e.g. {workflows: null}).
        // Skills remain unrestricted by default (skillAccessMode stays null).
        if (workflowsList == null) workflowsList = List.of();
        if (applicationsList == null) applicationsList = List.of();
        if (tablesList == null) tablesList = List.of();
        if (interfacesList == null) interfacesList = List.of();
        if (agentsList == null) agentsList = List.of();
        // web_search defaults to true when not specified
        if (webSearch == null) webSearch = true;

        if (name == null || name.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'name' is REQUIRED for agent creation.\n\n" +
                "Use agent(action='help') to see all available parameters.");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'system_prompt' is REQUIRED for agent creation.\n\n" +
                "MINIMAL EXAMPLE (model defaults to platform default - pass model_provider/model_name only to override):\n" +
                "agent(action='create', params={\n" +
                "  name: 'Assistant',\n" +
                "  system_prompt: 'You are a helpful assistant.'\n" +
                "})");
        }

        // Validate (provider, model_name) against the admin-filtered catalog.
        // Resolve defaults here so the check matches what AgentService will
        // actually persist (null or blank → dynamic default from provider list).
        // If the requested pair is invalid, silently substitute the catalog
        // default (model #1 of provider #1 - see ModelCatalogService.recalculate*)
        // and surface the substitution in the response so the LLM knows what was
        // actually created. Only fail when the catalog itself is empty.
        String effectiveProvider = (modelProvider != null && !modelProvider.isBlank())
                ? modelProvider : resolveDefaultProvider();
        String effectiveModelName = (modelName != null && !modelName.isBlank())
                ? modelName : resolveDefaultModel();
        ModelSubstitution modelSubstitution = null;
        if (!modelCatalogService.isModelAvailable(effectiveProvider, effectiveModelName)) {
            String fallbackProvider = modelCatalogService.getEffectiveDefaultProvider();
            String fallbackModel = modelCatalogService.getEffectiveDefaultModel();
            if (fallbackProvider == null || fallbackModel == null
                    || !modelCatalogService.isModelAvailable(fallbackProvider, fallbackModel)) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, formatModelNotAvailable(effectiveProvider, effectiveModelName));
            }
            // ModelSubstitution.requested must reflect what the LLM ACTUALLY
            // typed - not the yml-default we silently inferred for the missing
            // half. Otherwise the response shows a model name the LLM never
            // asked for and the agent gets confused about why the platform
            // "rejected" a value it never sent. Use a `(none)` placeholder for
            // truly unspecified halves.
            String requestedProviderRaw = (modelProvider != null && !modelProvider.isBlank()) ? modelProvider : "(none)";
            String requestedModelRaw = (modelName != null && !modelName.isBlank()) ? modelName : "(none)";
            modelSubstitution = new ModelSubstitution(requestedProviderRaw, requestedModelRaw, fallbackProvider, fallbackModel);
            modelProvider = fallbackProvider;
            modelName = fallbackModel;
            log.info("[AGENT_CREATE] Substituted unavailable model {}/{} → {}/{}",
                    requestedProviderRaw, requestedModelRaw, fallbackProvider, fallbackModel);
        }

        // 5-minute fixed-window creation cap keyed on turnId (unique per user message).
        // See CREATE_LIMITER_TTL_MINUTES - caveats (per-pod, per-restart) documented there.
        String turnId = context != null ? getTurnId(context.credentials()) : null;
        if (turnId != null) {
            int maxCreates = resolveMaxPerResourcePerTurn(context);
            String createKey = tenantId + ":" + turnId;
            var limitResult = createLimiter.checkLimit(createKey, maxCreates,
                "LIMIT REACHED: You have already created " + maxCreates
                + " agents in the last " + CREATE_LIMITER_TTL_MINUTES + " minutes.\n\n" +
                "WHAT TO DO:\n" +
                "1. Use agent(action='list') to see existing agents\n" +
                "2. Use agent(action='update', agent_id='...') to modify an existing one\n" +
                "3. Use agent(action='delete', agent_id='...') to free a slot - deleted agents are refunded\n" +
                "4. Wait a few minutes for the window to reset\n" +
                "5. Ask the user which agent they want to use or modify\n\n" +
                "DO NOT keep creating more agents. Work with what exists.");
            if (limitResult.isPresent()) return limitResult.get();

            log.info("[AGENT_CREATE] Creating agent {}/{} in turn {}",
                createLimiter.getCount(createKey), maxCreates, turnId);
        }

        try {
            Map<String, Object> toolsConfig = buildToolsConfig(toolsMode, toolsList, workflowsList, applicationsList, tablesList, interfacesList, agentsList, webSearch, maxIterations, tableAccessModeParam, workflowAccessModeParam, interfaceAccessModeParam, agentAccessModeParam, applicationAccessModeParam, skillAccessModeParam, workflowsGrantParam, applicationsGrantParam, tablesGrantParam, interfacesGrantParam, agentsGrantParam, fileAccessModeParam);
            Map<String, Object> config = buildConfig(maxIterations);
            String orgId = context != null ? context.orgId() : null;

            // V350 - reject an invalid compaction cadence before creating the agent
            // (a post-create throw in setCompactionOverrides would orphan the agent).
            Optional<ToolExecutionResult> compactionErr = validateCompactionCadence(p);
            if (compactionErr.isPresent()) return compactionErr.get();

            // Direct service call - no HTTP hop
            avatar = validateAvatarParam(avatar); // throws IllegalArgumentException -> clean tool error (caught below)
            AgentEntity result = agentService.createAgent(
                tenantId, name, description, systemPrompt,
                modelProvider != null ? modelProvider : resolveDefaultProvider(),
                modelName != null ? modelName : resolveDefaultModel(),
                temperature != null ? temperature : BigDecimal.valueOf(agentDefaults.getTemperature()),
                maxTokens != null ? maxTokens : agentDefaults.getMaxTokens(),
                maxIterations, executionTimeout,
                toolsConfig.isEmpty() ? null : toolsConfig,
                workflowId, dataSourceId, conversationId,
                config.isEmpty() ? null : config,
                avatar, // avatarUrl - null => AgentService assigns a random preset
                isPublic != null ? isPublic : Boolean.FALSE,
                isActive != null ? isActive : Boolean.TRUE,
                orgId,
                getDecimalParam(p, "credit_budget"),
                getStringParam(p, "budget_reset_mode")
            );

            // V340 - opt-in shared-backlog participation. Default is false (the
            // column default), so only act when the caller explicitly asks for true.
            if (Boolean.TRUE.equals(p.get("backlog_enabled"))) {
                result = agentService.setBacklogEnabled(result.getId(), tenantId, orgId, true);
            }

            // V350 - per-agent compaction enable + cadence override (present ⇒ set,
            // absent ⇒ inherit). Dedicated setter, like backlog above.
            if (p.containsKey("compaction_enabled") || p.containsKey("compaction_after_turns")) {
                result = agentService.setCompactionOverrides(
                    result.getId(), tenantId, orgId,
                    p.containsKey("compaction_enabled"), coerceBool(p.get("compaction_enabled")),
                    p.containsKey("compaction_after_turns"), coerceInt(p.get("compaction_after_turns")));
            }

            // Per-agent inactivity watchdog window (dedicated setter, like backlog/compaction above)
            // so createAgent's positional signature is untouched.
            if (p.containsKey("inactivity_timeout")) {
                result = agentService.setInactivityTimeout(result.getId(), tenantId, orgId, inactivityTimeout);
            }

            // Auto-grant: agent gets access to the sub-agent it just created
            if (context != null) {
                ToolAccessControl.grantCreatedResource(context.credentials(), "agent", result.getId().toString());
            }

            // Assign skills if provided
            int skillsAssigned = assignSkillsToAgent(result.getId(), tenantId, skillIdsList);

            // Create webhook if requested
            String webhookUrl = null;
            String webhookCurl = null;
            if (Boolean.TRUE.equals(webhookEnabled)) {
                try {
                    AgentWebhookTokenEntity wh = webhookTokenService.createOrUpdateWebhook(
                            result.getId(), "POST", "none", null,
                            Boolean.TRUE.equals(webhookMemory));
                    webhookUrl = webhookBaseUrl + "/webhook/agent/" + wh.getToken();
                    webhookCurl = "curl -X POST " + webhookUrl
                            + " -H \"Content-Type: application/json\""
                            + " -d '{\"message\": \"Hello\"}'";
                    log.info("[AGENT_CREATE] Webhook created for agent {}: {}", result.getId(), webhookUrl);
                } catch (Exception e) {
                    log.warn("[AGENT_CREATE] Failed to create webhook for agent {}: {}", result.getId(), e.getMessage());
                }
            }

            // Create schedule if cron provided
            Map<String, Object> scheduleInfo = null;
            if (scheduleCron != null && !scheduleCron.isBlank()) {
                try {
                    Map<String, Object> schedBody = new LinkedHashMap<>();
                    schedBody.put("agentEntityId", result.getId().toString());
                    schedBody.put("tenantId", tenantId);
                    // Stamp organization_id on the schedule row so org-teammates
                    // see it + the schedule's V213 column reflects the workspace.
                    // Daemon thread - no PR16 forwarder, must pass explicitly.
                    String schedOrgId = context != null ? context.orgId() : null;
                    if (schedOrgId != null && !schedOrgId.isBlank()) {
                        schedBody.put("organizationId", schedOrgId);
                    }
                    schedBody.put("cronExpression", scheduleCron);
                    schedBody.put("timezone", scheduleTimezone != null ? scheduleTimezone : "UTC");
                    schedBody.put("maxExecutions", scheduleMaxExecutions);
                    // Only send schedule_prompt / with_memory when the caller actually
                    // provided them, so a cron-only update preserves the stored
                    // prompt/memory instead of blanking them (prod incident 2026-06-14).
                    if (schedulePromptParam != null) {
                        schedBody.put("schedulePrompt", schedulePromptParam);
                    }
                    if (scheduleMemory != null) {
                        schedBody.put("withMemory", scheduleMemory);
                    }
                    schedBody.put("name", result.getName() + " Schedule");

                    // 2026-05-21 - full scope headers (X-User-ID + X-Organization-ID
                    // + X-Organization-Role) for trigger-service strict-isolation + canAccess.
                    HttpHeaders headers = buildScopeHeaders(tenantId, context);
                    ResponseEntity<Map> schedResp = restTemplate.exchange(
                            triggerServiceUrl + "/api/internal/trigger/schedules/agent",
                            HttpMethod.POST, new HttpEntity<>(schedBody, headers), Map.class);
                    if (schedResp.getBody() != null) {
                        scheduleInfo = schedResp.getBody();
                        log.info("[AGENT_CREATE] Schedule created for agent {}: cron={}", result.getId(), scheduleCron);
                    }
                } catch (Exception e) {
                    log.warn("[AGENT_CREATE] Failed to create schedule for agent {}: {}", result.getId(), e.getMessage());
                }
            }

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("id", result.getId().toString());
            resultMap.put("name", result.getName());
            resultMap.put("status", "CREATED");
            if (description != null) resultMap.put("description", description);
            resultMap.put("model_provider", result.getModelProvider());
            resultMap.put("model_name", result.getModelName());
            if (modelSubstitution != null) {
                resultMap.put("model_substituted", modelSubstitution.toResponseMap());
            }
            resultMap.put("temperature", result.getTemperature());
            resultMap.put("max_tokens", result.getMaxTokens());
            resultMap.put("is_active", result.getIsActive() != null ? result.getIsActive() : true);
            resultMap.put("tools_mode", toolsMode != null ? toolsMode : "all");
            resultMap.put("resources", resolveResources(result.getToolsConfig()));
            if (result.getToolsConfig() != null) {
                resultMap.put("tools_config", summarizeToolsConfig(result.getToolsConfig()));
            }
            if (result.getConversationId() != null) {
                resultMap.put("conversation_id", result.getConversationId().toString());
            }
            if (maxIterations != null) {
                resultMap.put("max_iterations", maxIterations);
            }
            if (skillsAssigned > 0) {
                resultMap.put("skills_assigned", skillsAssigned);
            }
            // Budget response shape (§5 AGENT_BUDGET_HIERARCHY.md) - always nested under
            // "budget" so frontend/LLM don't branch on unlimited vs limited.
            resultMap.put("budget", buildBudgetResponse(result));
            // Webhook info
            if (webhookUrl != null) {
                resultMap.put("webhook_url", webhookUrl);
                resultMap.put("webhook_curl", webhookCurl);
                resultMap.put("webhook_memory", Boolean.TRUE.equals(webhookMemory));
            }

            // Schedule info
            if (scheduleInfo != null) {
                resultMap.put("schedule_cron", scheduleCron);
                resultMap.put("schedule_timezone", scheduleInfo.getOrDefault("timezone", "UTC"));
                resultMap.put("schedule_memory", Boolean.TRUE.equals(scheduleMemory));
                resultMap.put("schedule_enabled", scheduleInfo.getOrDefault("enabled", true));
            }

            // DOC-5: warn when credit_budget is physically unreachable given max_iterations.
            // A single LLM turn consumes >1 credit in practice, so credit_budget <= max_iterations
            // means the agent will hit BUDGET_EXHAUSTED before completing its first iteration.
            Integer effectiveMaxIter = result.getMaxIterations() != null
                    ? result.getMaxIterations() : agentDefaults.getMaxIterations();
            BigDecimal effectiveBudget = result.getCreditBudget();
            List<String> warnings = new ArrayList<>();
            if (effectiveBudget != null
                    && effectiveBudget.compareTo(BigDecimal.valueOf(effectiveMaxIter)) <= 0) {
                warnings.add("credit_budget=" + effectiveBudget.stripTrailingZeros().toPlainString()
                        + " is <= max_iterations=" + effectiveMaxIter
                        + ". Each iteration consumes at least one credit, so this agent will almost certainly"
                        + " stop with BUDGET_EXHAUSTED before finishing its first turn."
                        + " Consider raising credit_budget (typical floor: 5× max_iterations) or lowering max_iterations.");
            }
            if (!warnings.isEmpty()) resultMap.put("warnings", warnings);

            StringBuilder msg = new StringBuilder();
            msg.append("Agent '").append(result.getName()).append("' created successfully (ID: ").append(result.getId()).append(").");
            if (skillsAssigned > 0) msg.append(" ").append(skillsAssigned).append(" skill(s) assigned.");
            if (webhookUrl != null) msg.append(" Webhook ready at: ").append(webhookUrl);
            if (scheduleInfo != null) msg.append(" Schedule set: ").append(scheduleCron).append(".");
            if (!warnings.isEmpty()) msg.append(" (").append(warnings.size()).append(" warning(s) - see 'warnings'.)");
            resultMap.put("message", msg.toString());

            // Add creation limit info
            if (turnId != null) {
                String createKey = tenantId + ":" + turnId;
                int createCount = createLimiter.getCount(createKey);
                resultMap.put("creates_in_window", createCount + "/" + resolveMaxPerResourcePerTurn(context)
                        + " (window=" + CREATE_LIMITER_TTL_MINUTES + "min)");
            }

            resultMap.put("marker", "[visualize:agent:" + result.getId() + "]");

            Map<String, Object> metadata = Map.of("visualization",
                Map.of("type", "agent", "id", result.getId().toString(), "title", result.getName()));
            return ToolExecutionResult.success(resultMap, metadata);
        } catch (com.apimarketplace.auth.client.entitlement.LimitExceededException e) {
            refundCreateSlot(tenantId, turnId);
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Validation failures (temperature/max_iterations/execution_timeout bounds,
            // duplicate name, blank tenant, …). Surface the message verbatim so the LLM
            // sees exactly which bound it violated and can self-correct on the next call.
            refundCreateSlot(tenantId, turnId);
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, e.getMessage());
        } catch (Exception e) {
            // Refund the create-slot when persistence fails so failed attempts don't count toward the cap.
            refundCreateSlot(tenantId, turnId);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create agent: " + e.getMessage());
        }
    }

    /**
     * Validate a supplied {@code avatar} value for create/update. Null/blank passes through
     * (create -> random preset; update -> unchanged). A preset must be a known id, optionally
     * recolored with {@code ?c1=RRGGBB&c2=RRGGBB} and/or decorated with a tool badge
     * {@code ?tool=<id>} (colors first when combined); an http(s) URL is accepted as-is.
     * Anything else throws so the agent gets an actionable message instead of a broken avatar.
     */
    static String validateAvatarParam(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            return null;
        }
        String v = avatar.trim();
        if (v.matches("(?i)https?://\\S+")) { // require a host after the scheme (rejects a bare "https://")
            return v;
        }
        if (v.startsWith("preset:")) {
            int q = v.indexOf('?');
            String base = q < 0 ? v : v.substring(0, q);
            if (!com.apimarketplace.agent.service.AgentService.isKnownAvatarPreset(base)) {
                throw new IllegalArgumentException("Unknown avatar preset '" + base + "'. Valid presets: "
                        + String.join(", ", com.apimarketplace.agent.service.AgentService.avatarPresetIds())
                        + ". Optionally recolor with '?c1=RRGGBB&c2=RRGGBB' and/or add a tool badge with"
                        + " '?tool=<tool>'.");
            }
            if (q >= 0) {
                // Param names and tool ids are lowercase (what the frontend parser reads back);
                // only the hex digits are case-insensitive.
                String query = v.substring(q + 1);
                if (!query.matches("(c1=[0-9a-fA-F]{6}&c2=[0-9a-fA-F]{6}(&tool=[a-z0-9-]+)?|tool=[a-z0-9-]+)")) {
                    throw new IllegalArgumentException("Avatar customization must be '?c1=RRGGBB&c2=RRGGBB',"
                            + " '?tool=<tool>', or '?c1=RRGGBB&c2=RRGGBB&tool=<tool>' (colors are two 6-digit"
                            + " hex without '#', tool ids are lowercase, colors come before tool), e.g."
                            + " 'preset:blue?c1=FF6600&c2=003366&tool=wrench'.");
                }
                java.util.regex.Matcher toolMatcher =
                        java.util.regex.Pattern.compile("(?:^|&)tool=([a-z0-9-]+)").matcher(query);
                if (toolMatcher.find()) {
                    String tool = toolMatcher.group(1);
                    if (!com.apimarketplace.agent.service.AgentService.isKnownAvatarTool(tool)) {
                        throw new IllegalArgumentException("Unknown avatar tool '" + tool + "'. Valid tools: "
                                + String.join(", ", com.apimarketplace.agent.service.AgentService.avatarToolIds())
                                + ".");
                    }
                }
            }
            return v;
        }
        throw new IllegalArgumentException("avatar must be a preset id ('preset:<color>', optionally with"
                + " '?c1=RRGGBB&c2=RRGGBB' and/or '?tool=<tool>') or an http(s) image URL.");
    }

    private void refundCreateSlot(String tenantId, String turnId) {
        if (turnId != null) {
            createLimiter.decrement(tenantId + ":" + turnId);
        }
    }

    // ==================== Get ====================

    private static final String AGENT_ID_ERROR = """
        agent_id is required and must be a valid UUID.
        - To LIST existing agents: agent(action='list')
        - To CREATE new agent: agent(action='create', name='...', system_prompt='...')
        """;

    private ToolExecutionResult executeGet(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID id = getUuidParam(p, "agent_id");
        if (id == null) return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, AGENT_ID_ERROR);

        List<String> allowedAgentIds = getAllowedAgentIds(context);
        if (allowedAgentIds != null && !allowedAgentIds.contains(id.toString())) {
            log.info("Agent restriction: agent {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This agent is not in your approved agent list.");
        }

        try {
            // Thread ctx.orgId() so the strict-isolation predicate matches
            // org-scoped agents (`organization_id` non-null). Without orgId,
            // the underlying isInStrictScope(tenantId, null, …) treats null
            // as "personal workspace" → rejects rows with non-null org_id →
            // "Agent not found" even when the agent IS visible to the caller
            // (mirrors agent.list). Prod fire 2026-05-20 21:21 UTC on conv
            // c67d368a: agent.list returned d83dcc52 but agent.get on the
            // same id said "Agent not found". Same root cause as the round-2
            // 2026-05-16 delete-path fix at L882.
            //
            // Fallback to the 2-arg overload when context is null so unit
            // tests that pre-date this fix keep working unchanged (they stub
            // only the 2-arg form). Prod callers always carry context.
            Optional<AgentEntity> opt = (context != null && context.orgId() != null)
                ? agentService.getAgent(id, tenantId, context.orgId(), context.orgRole())
                : agentService.getAgent(id, tenantId);
            if (opt.isEmpty()) return ToolExecutionResult.failure(ToolErrorCode.AGENT_NOT_FOUND, "Agent not found: " + id);
            AgentEntity entity = opt.get();

            Map<String, Object> agentMap = new LinkedHashMap<>();
            agentMap.put("id", entity.getId().toString());
            agentMap.put("name", entity.getName());
            agentMap.put("description", entity.getDescription() != null ? entity.getDescription() : "");
            agentMap.put("system_prompt", entity.getSystemPrompt() != null ? entity.getSystemPrompt() : "");
            agentMap.put("model_provider", entity.getModelProvider() != null ? entity.getModelProvider() : resolveDefaultProvider());
            agentMap.put("model_name", entity.getModelName() != null ? entity.getModelName() : resolveDefaultModel());
            agentMap.put("temperature", entity.getTemperature() != null ? entity.getTemperature() : agentDefaults.getTemperature());
            agentMap.put("max_tokens", entity.getMaxTokens() != null ? entity.getMaxTokens() : agentDefaults.getMaxTokens());
            agentMap.put("max_iterations", entity.getMaxIterations() != null ? entity.getMaxIterations() : agentDefaults.getMaxIterations());
            agentMap.put("execution_timeout", entity.getExecutionTimeout() != null ? entity.getExecutionTimeout() : agentDefaults.getExecutionTimeout());
            agentMap.put("is_public", entity.getIsPublic() != null ? entity.getIsPublic() : false);
            agentMap.put("is_active", entity.getIsActive() != null ? entity.getIsActive() : true);

            if (entity.getToolsConfig() != null) {
                agentMap.put("tools_config", entity.getToolsConfig());
            }
            if (entity.getConfig() != null) {
                agentMap.put("config", entity.getConfig());
            }
            if (entity.getWorkflowId() != null) {
                agentMap.put("workflow_id", entity.getWorkflowId().toString());
            }
            if (entity.getDataSourceId() != null) {
                agentMap.put("datasource_id", entity.getDataSourceId());
            }
            if (entity.getConversationId() != null) {
                agentMap.put("conversation_id", entity.getConversationId().toString());
            }
            agentMap.put("budget", buildBudgetResponse(entity));
            agentMap.put("resources", resolveResources(entity.getToolsConfig()));

            Map<String, Object> metadata = Map.of(
                "label", entity.getName(),
                "visualization", Map.of("type", "agent", "id", entity.getId().toString(), "title", entity.getName()));
            return ToolExecutionResult.success(agentMap, metadata);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to get agent: " + e.getMessage());
        }
    }

    // ==================== List ====================

    private ToolExecutionResult executeList(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        // agent.list = STANDARD caps. `query` (name/description substring) is the one
        // refinement filter, so the `refine` hint suggests it on large result sets.
        String query = getStringParam(p, "query");
        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.STANDARD, "agents", "agents", "agents")
                .withSuggestedFilters(List.of("query"));
        AgentListEnvelope.Bounds bounds;
        try {
            Set<String> activeFilters = hasQuery(query) ? Set.of("query") : Set.of();
            bounds = AgentListEnvelope.readBounds(p, spec, activeFilters);
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            String orgId = context != null ? context.orgId() : null;
            String orgRole = context != null ? context.orgRole() : null;
            List<AgentEntity> allAgents = agentService.listAgents(tenantId, orgId, orgRole);

            List<String> allowedAgentIds = getAllowedAgentIds(context);
            if (allowedAgentIds != null) {
                allAgents = allAgents.stream()
                    .filter(a -> a.getId() != null && allowedAgentIds.contains(a.getId().toString()))
                    .toList();
                log.info("Agent restriction: filtered agents to {}/{} allowed",
                    allAgents.size(), allowedAgentIds.size());
            }

            // Text search: case-insensitive substring over name + description, applied
            // BEFORE pagination so total/hasMore reflect the filtered set.
            if (hasQuery(query)) {
                allAgents = allAgents.stream()
                    .filter(a -> matchesQuery(query, a.getName(), a.getDescription()))
                    .toList();
            }

            long total = allAgents.size();

            List<Map<String, Object>> summaries = allAgents.stream()
                .skip(bounds.offset()).limit(bounds.limit())
                .map(agent -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", agent.getId().toString());
                    map.put("name", agent.getName());
                    map.put("description", agent.getDescription() != null ? agent.getDescription() : "");
                    map.put("model_provider", agent.getModelProvider() != null ? agent.getModelProvider() : "");
                    map.put("model_name", agent.getModelName() != null ? agent.getModelName() : "");
                    map.put("is_active", agent.getIsActive() != null ? agent.getIsActive() : true);
                    map.put("resources", resolveResources(agent.getToolsConfig()));
                    return map;
                }).toList();

            return ToolExecutionResult.success(
                    AgentListEnvelope.paginateProjection(summaries, bounds, total, spec));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to list agents: " + e.getMessage());
        }
    }

    // ==================== Update ====================

    private ToolExecutionResult executeUpdate(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID id = getUuidParam(p, "agent_id");
        if (id == null) return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, AGENT_ID_ERROR);

        List<String> allowedAgentIds = getAllowedAgentIds(context);
        if (allowedAgentIds != null && !allowedAgentIds.contains(id.toString())) {
            log.info("Agent restriction: agent {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This agent is not in your approved agent list.");
        }

        String updateKey = tenantId + ":" + id;
        var limitResult = updateLimiter.checkLimit(updateKey, MAX_CONSECUTIVE_UPDATES,
            "STOP: You have updated this agent " + MAX_CONSECUTIVE_UPDATES + " times already. " +
            "The agent configuration is COMPLETE. " +
            "DO NOT call agent(action='update') again. " +
            "Instead, ask the user: 'The agent is configured. Would you like any changes?'");
        if (limitResult.isPresent()) return limitResult.get();

        int currentCount = updateLimiter.getCount(updateKey);

        String name = getStringParam(p, "name");
        String description = getStringParam(p, "description");
        String systemPrompt = getStringParam(p, "system_prompt");
        String avatar = getStringParam(p, "avatar");
        String modelProvider = getStringParam(p, "model_provider");
        String modelName = getStringParam(p, "model_name");
        BigDecimal temperature = getDecimalParam(p, "temperature");
        Integer maxTokens = getIntParam(p, "max_tokens");
        Integer maxIterations = getIntParam(p, "max_iterations");
        Integer executionTimeout = getIntParam(p, "execution_timeout");
        Integer inactivityTimeout = getIntParam(p, "inactivity_timeout");
        String toolsMode = getStringParam(p, "tools_mode");
        Object toolsList = p.get("tools");
        Object workflowsList = p.get("workflows");
        Object applicationsList = p.get("applications");
        Object tablesList = p.get("tables");
        Object interfacesList = p.get("interfaces");
        Object agentsList = p.get("agents");
        Boolean webSearch = (Boolean) p.get("web_search");
        String tableAccessModeParam = getStringParam(p, "table_access_mode");
        String workflowAccessModeParam = getStringParam(p, "workflow_access_mode");
        String interfaceAccessModeParam = getStringParam(p, "interface_access_mode");
        String agentAccessModeParam = getStringParam(p, "agent_access_mode");
        String applicationAccessModeParam = getStringParam(p, "application_access_mode");
        String skillAccessModeParam = getStringParam(p, "skill_access_mode");
        String fileAccessModeParam = getStringParam(p, "file_access_mode");
        // Explicit GRANT scope (none/all/custom). Authoritative when provided - the only way to
        // express grant='all' (the list params can only express none/custom). Omitted = derive from list.
        String workflowsGrantParam = getStringParam(p, "workflows_grant");
        String applicationsGrantParam = getStringParam(p, "applications_grant");
        String tablesGrantParam = getStringParam(p, "tables_grant");
        String interfacesGrantParam = getStringParam(p, "interfaces_grant");
        String agentsGrantParam = getStringParam(p, "agents_grant");
        UUID workflowId = getUuidParam(p, "workflow_id");
        Long dataSourceId = getLongParam(p, "datasource_id");
        UUID conversationId = getUuidParam(p, "conversation_id");
        Boolean isPublic = (Boolean) p.get("is_public");
        Boolean isActive = (Boolean) p.get("is_active");
        Object skillIdsList = p.get("skill_ids");

        // Webhook config
        Boolean webhookEnabled = (Boolean) p.get("webhook_enabled");
        Boolean webhookMemory = (Boolean) p.get("webhook_memory");

        // Schedule config
        String scheduleCron = getStringParam(p, "schedule_cron");
        String scheduleTimezone = getStringParam(p, "schedule_timezone");
        Integer scheduleMaxExecutions = getIntParam(p, "schedule_max_executions");
        String schedulePromptParam = getStringParam(p, "schedule_prompt");
        Boolean scheduleMemory = (Boolean) p.get("schedule_memory");

        // Fetch existing for name fallback (AgentService.updateAgent always sets name).
        // Thread ctx.orgId() - without it the 2-arg back-compat overload fires
        // strict-isolation with orgId=null → rejects org-scoped agents → false
        // "Agent not found". Same fix shape as L494 (executeGet) and L882
        // (executeDelete, round-2 2026-05-16). Prod fire 2026-05-20 21:21 UTC.
        // Fallback to 2-arg when context is null so test stubs that pre-date
        // this fix keep working unchanged.
        String callerOrgId = context != null ? context.orgId() : null;
        Optional<AgentEntity> existingOpt = (callerOrgId != null)
            ? agentService.getAgent(id, tenantId, callerOrgId, context.orgRole())
            : agentService.getAgent(id, tenantId);
        if (existingOpt.isEmpty()) return ToolExecutionResult.failure(ToolErrorCode.AGENT_NOT_FOUND, "Agent not found: " + id);
        AgentEntity existing = existingOpt.get();

        // Validate model pair when either side is being changed. Null or blank
        // side resolves to the existing agent's value so partial updates (e.g.
        // only model_name) are still checked as a complete (provider, model)
        // pair. Blank strings normalised to null so a stray empty field from
        // the LLM never reaches the catalog lookup.
        boolean providerProvided = modelProvider != null && !modelProvider.isBlank();
        boolean modelProvided = modelName != null && !modelName.isBlank();
        ModelSubstitution updateSubstitution = null;
        if (providerProvided || modelProvided) {
            String targetProvider = providerProvided ? modelProvider : existing.getModelProvider();
            String targetModelName = modelProvided ? modelName : existing.getModelName();
            if (!modelCatalogService.isModelAvailable(targetProvider, targetModelName)) {
                String fallbackProvider = modelCatalogService.getEffectiveDefaultProvider();
                String fallbackModel = modelCatalogService.getEffectiveDefaultModel();
                if (fallbackProvider == null || fallbackModel == null
                        || !modelCatalogService.isModelAvailable(fallbackProvider, fallbackModel)) {
                    return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, formatModelNotAvailable(targetProvider, targetModelName));
                }
                updateSubstitution = new ModelSubstitution(targetProvider, targetModelName, fallbackProvider, fallbackModel);
                modelProvider = fallbackProvider;
                modelName = fallbackModel;
                log.info("[AGENT_UPDATE] Substituted unavailable model {}/{} → {}/{} for agent {}",
                        updateSubstitution.requestedProvider(), updateSubstitution.requestedModel(),
                        fallbackProvider, fallbackModel, id);
            }
        }

        try {
            Map<String, Object> toolsConfigPatch = buildToolsConfig(toolsMode, toolsList, workflowsList, applicationsList, tablesList, interfacesList, agentsList, webSearch, maxIterations, tableAccessModeParam, workflowAccessModeParam, interfaceAccessModeParam, agentAccessModeParam, applicationAccessModeParam, skillAccessModeParam, workflowsGrantParam, applicationsGrantParam, tablesGrantParam, interfacesGrantParam, agentsGrantParam, fileAccessModeParam);
            Map<String, Object> config = buildConfig(maxIterations);

            // Patch is forwarded as-is to AgentService.updateAgent - the service does
            // the merge against existing AND normalizes (5 internal keys → []) in one
            // chokepoint. Keeping the merge here would duplicate logic and let the
            // REST PUT path drift again. Pass null when patch is empty so the service
            // leaves toolsConfig untouched.
            Map<String, Object> mergedToolsConfig = toolsConfigPatch.isEmpty() ? null : toolsConfigPatch;

            UUID resolvedConversationId = conversationId != null ? conversationId : existing.getConversationId();
            UUID resolvedWorkflowId = workflowId != null ? workflowId : existing.getWorkflowId();
            BigDecimal resolvedCreditBudget = p.containsKey("credit_budget") ? getDecimalParam(p, "credit_budget") : existing.getCreditBudget();

            // Direct service call - no HTTP hop. callerOrgId already resolved above
            // for the existing-agent fetch (L662), reuse the same value here so the
            // pre-mutation read and the write are guaranteed to target the same scope.
            // V350 - reject an invalid compaction cadence before the update.
            Optional<ToolExecutionResult> compactionErr = validateCompactionCadence(p);
            if (compactionErr.isPresent()) return compactionErr.get();

            // Audit 2026-05-16 round-2 originally added this for org-teammate updates.
            avatar = validateAvatarParam(avatar); // throws IllegalArgumentException -> clean tool error (caught below)
            AgentEntity result = agentService.updateAgent(
                id, tenantId,
                name != null ? name : existing.getName(),
                description, systemPrompt,
                modelProvider, modelName,
                temperature, maxTokens,
                maxIterations, executionTimeout,
                mergedToolsConfig,
                resolvedWorkflowId, dataSourceId, resolvedConversationId,
                config.isEmpty() ? null : config,
                avatar, // avatarUrl - null => unchanged (patch semantics)
                isPublic, isActive,
                resolvedCreditBudget,
                getStringParam(p, "budget_reset_mode"),
                null, // guardOverrides
                callerOrgId
            );

            // V340 - patch the opt-in shared-backlog flag when the caller sent it
            // (present ⇒ set to the given value; absent ⇒ leave unchanged).
            if (p.get("backlog_enabled") instanceof Boolean backlogEnabledParam) {
                result = agentService.setBacklogEnabled(id, tenantId, callerOrgId, backlogEnabledParam);
            }

            // V350 - patch the per-agent compaction enable + cadence override when sent.
            if (p.containsKey("compaction_enabled") || p.containsKey("compaction_after_turns")) {
                result = agentService.setCompactionOverrides(
                    id, tenantId, callerOrgId,
                    p.containsKey("compaction_enabled"), coerceBool(p.get("compaction_enabled")),
                    p.containsKey("compaction_after_turns"), coerceInt(p.get("compaction_after_turns")));
            }

            // Patch the per-agent inactivity watchdog window when sent (present => set, absent => unchanged).
            if (p.containsKey("inactivity_timeout")) {
                result = agentService.setInactivityTimeout(id, tenantId, callerOrgId, inactivityTimeout);
            }

            // Assign skills if provided
            int skillsAssigned = assignSkillsToAgent(id, tenantId, skillIdsList);

            // Handle webhook
            String webhookUrl = null;
            if (Boolean.TRUE.equals(webhookEnabled)) {
                try {
                    AgentWebhookTokenEntity wh = webhookTokenService.createOrUpdateWebhook(
                            id, "POST", "none", null, Boolean.TRUE.equals(webhookMemory));
                    webhookUrl = webhookBaseUrl + "/webhook/agent/" + wh.getToken();
                } catch (Exception e) {
                    log.warn("[AGENT_UPDATE] Failed to create webhook: {}", e.getMessage());
                }
            } else if (Boolean.FALSE.equals(webhookEnabled)) {
                try { webhookTokenService.deleteWebhook(id); } catch (Exception ignored) {}
            }

            // Handle schedule - mirror the webhook pattern: create/update when cron
            // is provided and non-blank, DELETE when explicitly set to empty/blank.
            if (scheduleCron != null && !scheduleCron.isBlank()) {
                try {
                    Map<String, Object> schedBody = new LinkedHashMap<>();
                    schedBody.put("agentEntityId", id.toString());
                    schedBody.put("tenantId", tenantId);
                    // Stamp organization_id on the schedule (V213 column) so org-teammates
                    // see it + schedule fires inherit org context. Audit 2026-05-16.
                    String schedOrgId = context != null ? context.orgId() : null;
                    if (schedOrgId != null && !schedOrgId.isBlank()) {
                        schedBody.put("organizationId", schedOrgId);
                    }
                    schedBody.put("cronExpression", scheduleCron);
                    schedBody.put("timezone", scheduleTimezone != null ? scheduleTimezone : "UTC");
                    schedBody.put("maxExecutions", scheduleMaxExecutions);
                    // Only send schedule_prompt / with_memory when the caller actually
                    // provided them, so a cron-only update preserves the stored
                    // prompt/memory instead of blanking them (prod incident 2026-06-14).
                    if (schedulePromptParam != null) {
                        schedBody.put("schedulePrompt", schedulePromptParam);
                    }
                    if (scheduleMemory != null) {
                        schedBody.put("withMemory", scheduleMemory);
                    }
                    schedBody.put("name", result.getName() + " Schedule");
                    // 2026-05-21 - full scope headers (see buildScopeHeaders javadoc).
                    HttpHeaders headers = buildScopeHeaders(tenantId, context);
                    restTemplate.exchange(triggerServiceUrl + "/api/internal/trigger/schedules/agent",
                            HttpMethod.POST, new HttpEntity<>(schedBody, headers), Map.class);
                } catch (Exception e) {
                    log.warn("[AGENT_UPDATE] Failed to create schedule: {}", e.getMessage());
                }
            } else if (scheduleCron != null) {
                // scheduleCron was explicitly passed as empty/blank → remove the schedule
                try {
                    // Audit 2026-05-17 round-3 + 2026-05-21 - InternalTriggerController.deleteAgentSchedule
                    // is scope-aware (X-User-ID + X-Organization-ID + X-Organization-Role).
                    // Use the shared header builder so all 3 schedule fan-out sites stay aligned.
                    HttpHeaders delHeaders = buildScopeHeaders(tenantId, context);
                    restTemplate.exchange(
                            triggerServiceUrl + "/api/internal/trigger/schedules/by-agent/" + id,
                            HttpMethod.DELETE, new HttpEntity<>(delHeaders), Void.class);
                    log.info("[AGENT_UPDATE] Deleted schedule for agent {}", id);
                } catch (Exception e) {
                    log.warn("[AGENT_UPDATE] Failed to delete schedule: {}", e.getMessage());
                }
            }

            Map<String, Object> responseMap = new LinkedHashMap<>();
            responseMap.put("id", result.getId().toString());
            responseMap.put("name", result.getName());
            responseMap.put("status", "UPDATED");
            responseMap.put("model_provider", result.getModelProvider());
            responseMap.put("model_name", result.getModelName());
            if (updateSubstitution != null) {
                responseMap.put("model_substituted", updateSubstitution.toResponseMap());
            }
            responseMap.put("temperature", result.getTemperature());
            responseMap.put("max_tokens", result.getMaxTokens());
            responseMap.put("is_active", result.getIsActive());
            responseMap.put("resources", resolveResources(result.getToolsConfig()));
            if (result.getToolsConfig() != null) {
                responseMap.put("tools_config", summarizeToolsConfig(result.getToolsConfig()));
            }
            if (skillsAssigned > 0) {
                responseMap.put("skills_assigned", skillsAssigned);
            }
            responseMap.put("budget", buildBudgetResponse(result));
            if (webhookUrl != null) {
                responseMap.put("webhook_url", webhookUrl);
                responseMap.put("webhook_curl", "curl -X POST " + webhookUrl
                        + " -H \"Content-Type: application/json\" -d '{\"message\": \"Hello\"}'");
            }
            if (scheduleCron != null && !scheduleCron.isBlank()) {
                responseMap.put("schedule_cron", scheduleCron);
            }
            responseMap.put("updateCount", currentCount);
            responseMap.put("maxUpdates", MAX_CONSECUTIVE_UPDATES);

            // DOC-5: warn when credit_budget is physically unreachable given max_iterations.
            Integer effectiveMaxIter = result.getMaxIterations() != null
                    ? result.getMaxIterations() : agentDefaults.getMaxIterations();
            BigDecimal effectiveBudget = result.getCreditBudget();
            List<String> warnings = new ArrayList<>();
            if (effectiveBudget != null
                    && effectiveBudget.compareTo(BigDecimal.valueOf(effectiveMaxIter)) <= 0) {
                warnings.add("credit_budget=" + effectiveBudget.stripTrailingZeros().toPlainString()
                        + " is <= max_iterations=" + effectiveMaxIter
                        + ". Each iteration consumes at least one credit, so this agent will almost certainly"
                        + " stop with BUDGET_EXHAUSTED before finishing its first turn."
                        + " Consider raising credit_budget (typical floor: 5× max_iterations) or lowering max_iterations.");
            }
            if (!warnings.isEmpty()) responseMap.put("warnings", warnings);

            StringBuilder updateMsg = new StringBuilder();
            updateMsg.append("Agent '").append(result.getName()).append("' updated successfully (update ").append(currentCount).append("/").append(MAX_CONSECUTIVE_UPDATES).append(").");
            if (webhookUrl != null) updateMsg.append(" Webhook: ").append(webhookUrl);
            if (scheduleCron != null) updateMsg.append(" Schedule: ").append(scheduleCron);
            if (!warnings.isEmpty()) updateMsg.append(" (").append(warnings.size()).append(" warning(s) - see 'warnings'.)");
            responseMap.put("message", updateMsg.toString());
            responseMap.put("STOP", "DO NOT call agent(action='update') again unless the user explicitly requests changes.");
            responseMap.put("TASK_COMPLETE", true);
            responseMap.put("nextAction", "Tell the user: 'The agent is configured and ready. Let me know if you want any changes.'");

            responseMap.put("marker", "[visualize:agent:" + result.getId() + "]");

            Map<String, Object> metadata = Map.of("visualization",
                Map.of("type", "agent", "id", result.getId().toString(), "title", result.getName()));
            return ToolExecutionResult.success(responseMap, metadata);
        } catch (IllegalArgumentException e) {
            // Validation failures (temperature/max_iterations/execution_timeout bounds, …).
            // Surface the message verbatim so the LLM can self-correct on the next call.
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to update agent: " + e.getMessage());
        }
    }

    // ==================== Delete ====================

    private ToolExecutionResult executeDelete(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID id = getUuidParam(p, "agent_id");
        if (id == null) return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, AGENT_ID_ERROR);

        List<String> allowedAgentIds = getAllowedAgentIds(context);
        if (allowedAgentIds != null && !allowedAgentIds.contains(id.toString())) {
            log.info("Agent restriction: agent {} not in allowed list", id);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "This agent is not in your approved agent list.");
        }

        try {
            // Audit 2026-05-16 round-2: thread ctx.orgId() so org-teammates
            // can delete shared agents via the MCP `agent(action='delete')`
            // path. Without the callerOrgId param, the 2-arg back-compat
            // overload fires strict-tenant → teammate gets "Agent tenant mismatch".
            String callerOrgId = context != null ? context.orgId() : null;
            String orgRole = context != null ? context.orgRole() : null;
            Optional<AgentEntity> existingOpt = agentService.getAgent(id, tenantId, callerOrgId);
            String deletedName = existingOpt.map(AgentEntity::getName).orElse("Unknown");

            agentService.deleteAgent(id, tenantId, orgRole, callerOrgId);

            // Refund the create-slot for this turn. Without this, a caller that
            // does create → delete → create to recycle an agent would exhaust
            // the per-message cap even though no agent is alive from earlier
            // iterations. See TASK_TEST_ERRORS.md finding #4.
            String turnId = context != null ? getTurnId(context.credentials()) : null;
            refundCreateSlot(tenantId, turnId);

            return ToolExecutionResult.success(Map.of(
                "id", id.toString(), "name", deletedName, "status", "DELETED",
                "message", "Agent '" + deletedName + "' deleted successfully. To see remaining agents: agent(action='list')"
            ), Map.of("label", deletedName));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to delete agent: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private List<String> getAllowedAgentIds(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        return ToolAccessControl.getAllowedIds(context.credentials(), "agent");
    }

    private int assignSkillsToAgent(UUID agentId, String tenantId, Object skillIdsList) {
        if (skillIdsList == null) return 0;
        if (!(skillIdsList instanceof List<?> list)) return 0;

        List<SkillService.SkillAssignment> assignments = new ArrayList<>();
        for (Object item : list) {
            String skillIdStr = item instanceof String s ? s : item.toString();
            try {
                UUID skillId = UUID.fromString(skillIdStr);
                assignments.add(new SkillService.SkillAssignment(skillId));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid skill UUID in agent create/update: {}", skillIdStr);
            }
        }
        if (assignments.isEmpty()) return 0;

        try {
            skillService.setAgentSkills(agentId, tenantId, assignments);
            return assignments.size();
        } catch (Exception e) {
            log.warn("Failed to assign skills to agent {}: {}", agentId, e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> buildToolsConfig(String toolsMode, Object toolsList,
            Object workflowsList, Object applicationsList, Object tablesList,
            Object interfacesList, Object agentsList, Boolean webSearch, Integer maxIterations,
            String tableAM, String workflowAM, String interfaceAM, String agentAM, String applicationAM, String skillAM,
            String workflowsGrant, String applicationsGrant, String tablesGrant, String interfacesGrant, String agentsGrant,
            String fileAM) {
        Map<String, Object> toolsConfig = new LinkedHashMap<>();
        // tools_mode controls MCP/catalog tool access (all/none/custom) - separate from resource access
        if (toolsMode != null) toolsConfig.put("mode", toolsMode);
        // NOTE: tools_config.tools[] MUST keep api_tools.id UUIDs verbatim. The runtime
        // executor (CatalogExecuteModule.checkToolRestriction) compares incoming
        // tool_id values - UUIDs from catalog.search - against this list, and
        // CatalogSearchModule.fetchToolsByIds does UUID.fromString on each entry.
        // Rewriting to "apiSlug:toolSlug" here breaks BOTH the allowlist check AND
        // the tool info fetch (400 on non-UUID). The fleet frontend resolves UUIDs
        // for display separately via toolUuidMap (Delta A). See audit 4/10 commit
        // 3791d8830 - Delta B revert.
        if (toolsList != null) toolsConfig.put("tools", toolsList);
        // Resource lists: absent = not in patch (preserved on merge), [] = no access, [ids] = specific access.
        // In executeCreate, absent lists default to [] (secure-by-default).
        // Skills and catalog remain always accessible (not resource-gated).
        if (workflowsList != null) toolsConfig.put("workflows", workflowsList);
        if (applicationsList != null) toolsConfig.put("applications", applicationsList);
        if (tablesList != null) toolsConfig.put("tables", tablesList);
        if (interfacesList != null) toolsConfig.put("interfaces", interfacesList);
        if (agentsList != null) toolsConfig.put("agents", agentsList);
        if (webSearch != null) toolsConfig.put("webSearch", webSearch);
        if (maxIterations != null) toolsConfig.put("maxIterations", maxIterations);
        // Access modes - store when provided so merge can revert read→write
        if (tableAM != null) toolsConfig.put("tableAccessMode", tableAM);
        if (workflowAM != null) toolsConfig.put("workflowAccessMode", workflowAM);
        if (interfaceAM != null) toolsConfig.put("interfaceAccessMode", interfaceAM);
        if (agentAM != null) toolsConfig.put("agentAccessMode", agentAM);
        if (applicationAM != null) toolsConfig.put("applicationAccessMode", applicationAM);
        if (skillAM != null) toolsConfig.put("skillAccessMode", skillAM);
        // Files read/write axis (no grant). 'read' blocks create_folder/move_to_folder.
        if (fileAM != null) toolsConfig.put("fileAccessMode", fileAM);
        // Explicit GRANT sentinels - authoritative when a VALID value (none/all/custom) is provided.
        // AgentService.normalizeToolsConfig preserves them ('all'/'none' reset the id list to [],
        // 'custom' keeps it); an absent/invalid grant is dropped here so normalize derives it from
        // the list (empty=none, non-empty=custom) - never stores a garbage sentinel like "bogus".
        if (isValidGrant(workflowsGrant)) toolsConfig.put("workflowsGrant", workflowsGrant);
        if (isValidGrant(applicationsGrant)) toolsConfig.put("applicationsGrant", applicationsGrant);
        if (isValidGrant(tablesGrant)) toolsConfig.put("tablesGrant", tablesGrant);
        if (isValidGrant(interfacesGrant)) toolsConfig.put("interfacesGrant", interfacesGrant);
        if (isValidGrant(agentsGrant)) toolsConfig.put("agentsGrant", agentsGrant);
        return toolsConfig;
    }

    /** A grant sentinel is only honored when it is one of the three valid scopes. */
    private static boolean isValidGrant(String grant) {
        return "none".equals(grant) || "all".equals(grant) || "custom".equals(grant);
    }

    private Map<String, Object> buildConfig(Integer maxIterations) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (maxIterations != null) config.put("maxIterations", maxIterations);
        return config;
    }

    /**
     * Compact echo of toolsConfig for CREATE/UPDATE responses. The caller just SET
     * this config, so echoing the full map back (custom lists can carry hundreds of
     * ids) is pure noise in both the chat display and the LLM context. The summary
     * keeps what is actionable: the catalogue {@code mode}, each family's grant, and
     * the SIZE of each non-empty custom list. The FULL map stays available via
     * {@code action=get} (round-trip editing reads it there). Documented in
     * AgentHelpModule (actions.create/get + response_shape.tools_config) - keep in
     * lockstep.
     */
    private static Map<String, Object> summarizeToolsConfig(Map<String, Object> toolsConfig) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Object mode = toolsConfig.get("mode");
        if (mode != null) summary.put("mode", mode);
        for (String family : List.of("workflows", "tables", "interfaces", "agents", "applications")) {
            Object grant = toolsConfig.get(family + "Grant");
            if (grant != null) summary.put(family + "Grant", grant);
            if (toolsConfig.get(family) instanceof List<?> ids && !ids.isEmpty()) {
                summary.put(family + "_count", ids.size());
            }
        }
        if (toolsConfig.get("tools") instanceof List<?> tools && !tools.isEmpty()) {
            summary.put("tools_count", tools.size());
        }
        if (toolsConfig.containsKey("webSearch")) {
            summary.put("webSearch", toolsConfig.get("webSearch"));
        }
        return summary;
    }

    /**
     * Resolves the effective resources an agent has access to for LLM display.
     * Secure-by-default + GRANT-aware: each family's `<family>Grant` sentinel drives the
     * summary - 'all' → "<resource> (all)", 'custom' → "<resource>" (when its id list is
     * non-empty), 'none'/absent/unknown → omitted. tools_mode (all/none/custom) only
     * controls MCP/catalog tools, NOT these internal resources.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveResources(Map<String, Object> toolsConfig) {
        if (toolsConfig == null) {
            return Map.of("resources", List.of(), "mcp_tools_mode", "all");
        }

        // MCP/catalog tool access mode (separate from resource access)
        String mcpMode = (String) toolsConfig.getOrDefault("mode", "all");

        // Build the effective resource list, grant-first (see addResourceEntry):
        // grant 'all' → "<resource> (all)"; 'custom'/non-empty list → "<resource>";
        // 'none'/absent/unknown → omitted (no access). The list is only the custom payload.
        List<String> enabledResources = new java.util.ArrayList<>();
        addResourceEntry(enabledResources, toolsConfig, "workflows", "workflow");
        addResourceEntry(enabledResources, toolsConfig, "tables", "table");
        addResourceEntry(enabledResources, toolsConfig, "interfaces", "interface");
        addResourceEntry(enabledResources, toolsConfig, "agents", "agent");
        addResourceEntry(enabledResources, toolsConfig, "applications", "application");
        if (Boolean.TRUE.equals(toolsConfig.get("webSearch"))) enabledResources.add("web_search");
        // Skills and catalog are always available (not resource-gated)
        enabledResources.add("skill");
        enabledResources.add("catalog");

        return Map.of("resources", enabledResources, "mcp_tools_mode", mcpMode);
    }

    /**
     * Build the unified agent budget response shape (§5 of AGENT_BUDGET_HIERARCHY.md).
     *
     * <p>Shape - always a nested {@code budget} map with the same keys regardless of the agent's
     * budget state. Callers should MERGE this under key {@code "budget"} on the response.
     * <ul>
     *   <li>{@code unlimited}: true if {@code credit_budget IS NULL} (unlimited agent)</li>
     *   <li>{@code total}: the configured {@code credit_budget}, or {@code null} when unlimited</li>
     *   <li>{@code consumed}: lifetime {@code credits_consumed} after any automatic reset -
     *       includes BOTH the agent's own LLM spend AND the cascade charge from descendant
     *       sub-agents (the cascade bumps this column via {@code settleReservationChain})</li>
     *   <li>{@code consumed_from_subagents}: subset of {@code consumed} that came from the
     *       sub-agent cascade (§4.3). Always {@code <= consumed}. Zero on agents with no
     *       descendants. Observability-only - written exclusively by
     *       {@code BudgetReservationService.settleReservationChain}.</li>
     *   <li>{@code consumed_own}: derived {@code consumed - consumed_from_subagents} - the
     *       agent's own LLM spend (not charged by descendants). Exposed so callers never have
     *       to do the subtraction themselves and can split the two views in one read.</li>
     *   <li>{@code reserved_for_subagents}: credits currently held by cascade reservations
     *       for in-flight descendants (§4.2). Zero on unlimited agents and on idle agents
     *       (by design - refund + debit are one atomic tx in {@code settleReservationChain}).
     *       <strong>NOT</strong> the same thing as {@code consumed_from_subagents}: this is a
     *       transient in-flight counter that drops back to zero once the sub-agent terminates,
     *       whereas {@code consumed_from_subagents} is a permanent lifetime accumulator.</li>
     *   <li>{@code free}: {@code total - consumed - reserved_for_subagents}, clamped at zero.
     *       {@code null} for unlimited agents (no binding cap).</li>
     *   <li>{@code reset_mode}: {@code cumulative | weekly | monthly} - how the consumed counter rolls</li>
     *   <li>{@code last_reset}: ISO-8601 timestamp of the last automatic reset - OMITTED when null
     *       (e.g. a brand-new cumulative agent that has never been reset)</li>
     * </ul>
     *
     * <p>The shape is identical across create/get/update so the frontend doesn't branch on
     * action, and the LLM sees a consistent {@code budget.*} accessor surface in the tool
     * documentation. The authoritative LLM-visible contract lives in
     * {@link AgentHelpModule#buildResponseShape()} (tool-help {@code response_shape.budget}) -
     * it MUST stay in lockstep with the keys returned here. When adding/removing a key in
     * this method, update the help module in the same commit or an LLM consulting the tool
     * help will see a stale accessor surface.
     */
    private Map<String, Object> buildBudgetResponse(AgentEntity entity) {
        Map<String, Object> budget = new LinkedHashMap<>();
        BigDecimal total = entity.getCreditBudget();
        BigDecimal consumed = entity.getCreditsConsumed() != null ? entity.getCreditsConsumed() : BigDecimal.ZERO;
        BigDecimal reserved = entity.getCreditsReserved() != null ? entity.getCreditsReserved() : BigDecimal.ZERO;
        BigDecimal consumedFromSubagents = entity.getCreditsConsumedFromSubagents() != null
            ? entity.getCreditsConsumedFromSubagents()
            : BigDecimal.ZERO;
        // Clamp defensively: the invariant consumed_from_subagents <= consumed is enforced at
        // the write site (settleReservationChain bumps both in one UPDATE), but a partial reset
        // or manual DB fix-up could violate it. We'd rather surface 0 than a negative own spend.
        BigDecimal consumedOwn = consumed.subtract(consumedFromSubagents);
        if (consumedOwn.signum() < 0) consumedOwn = BigDecimal.ZERO;

        if (total == null) {
            // Unlimited agent - still expose the nested shape so the LLM/frontend never needs
            // to branch on whether the key exists. reserved_for_subagents is hard-coded to
            // ZERO here (not `reserved`) because tryReserveOne() no-ops credits_reserved when
            // credit_budget IS NULL - an unlimited agent never holds cascade reservations, so
            // whatever stale value might sit on the entity is semantically meaningless. Do
            // NOT "fix" this to read `reserved` from the entity - §4.3 cascade contract.
            budget.put("unlimited", true);
            budget.put("total", null);
            budget.put("consumed", consumed);
            budget.put("consumed_from_subagents", consumedFromSubagents);
            budget.put("consumed_own", consumedOwn);
            budget.put("reserved_for_subagents", BigDecimal.ZERO);
            budget.put("free", null);
        } else {
            BigDecimal free = total.subtract(consumed).subtract(reserved);
            if (free.signum() < 0) free = BigDecimal.ZERO;
            budget.put("unlimited", false);
            budget.put("total", total);
            budget.put("consumed", consumed);
            budget.put("consumed_from_subagents", consumedFromSubagents);
            budget.put("consumed_own", consumedOwn);
            budget.put("reserved_for_subagents", reserved);
            budget.put("free", free);
        }
        budget.put("reset_mode", entity.getBudgetResetMode());
        if (entity.getBudgetLastReset() != null) {
            budget.put("last_reset", entity.getBudgetLastReset().toString());
        }
        return budget;
    }

    /**
     * Adds a resource entry to the LLM-facing summary, GRANT-first:
     * - grant 'all'                       → "resource (all)" (full access)
     * - grant 'custom' / non-empty list   → "resource"       (specific ids)
     * - grant 'none' / empty / absent / unknown → not added  (no access, deny-by-default)
     */
    private void addResourceEntry(List<String> result, Map<String, Object> config, String key, String resourceName) {
        // GRANT-aware (the per-family <key>Grant sentinel is authoritative and takes
        // precedence over the id list - for grant='all' the list is intentionally [],
        // so reading the list alone would wrongly report "no access"):
        //   'all'    → "<resource> (all)"  (full access)
        //   'custom' → "<resource>"        (specific ids in the list)
        //   'none'   → omitted             (explicit deny)
        // No grant key (legacy / derive-from-list) → fall back to the list: non-empty
        // = specific access, empty/absent = no access. Mirrors normalizeToolsConfig
        // (write side) + AgentConfigProvider is*None/is*All predicates (read side).
        Object grant = config.get(key + "Grant");
        if (grant instanceof String g) {
            if ("all".equals(g)) { result.add(resourceName + " (all)"); return; }
            if ("none".equals(g)) { return; }
            // 'custom' falls through to the list check below
        }
        Object val = config.get(key);
        if (val instanceof List<?> list && !list.isEmpty()) {
            result.add(resourceName);
        }
        // empty list OR absent key → no access → don't add
    }

    /**
     * Build a rich error message when the LLM picks a (provider, model) pair
     * that isn't in the admin-filtered catalog. The message embeds the full
     * catalog grouped by provider so a single round-trip is enough for the
     * LLM to self-correct - it does NOT need to call {@code action='help'}
     * again. Matches the pattern used by other tool-failure messages in this
     * module (explicit, prescriptive, example-driven).
     */
    /**
     * Resolve the default model from the AI provider catalog.
     * Falls back to AgentDefaultsConfig if the catalog is empty.
     */
    private String resolveDefaultModel() {
        String effective = modelCatalogService.getEffectiveDefaultModel();
        return effective != null ? effective : "unknown";
    }

    /**
     * Resolve the default provider from the AI provider catalog.
     * Returns "unknown" only if no providers are configured at all.
     */
    private String resolveDefaultProvider() {
        String effective = modelCatalogService.getEffectiveDefaultProvider();
        return effective != null ? effective : "unknown";
    }

    /**
     * Tracks a (requested → fallback) model swap so the LLM sees in the response
     * exactly what was created/updated, instead of silently believing it got the
     * model it asked for. Surfaced as {@code model_substituted} in the result.
     */
    record ModelSubstitution(String requestedProvider, String requestedModel,
                             String actualProvider, String actualModel) {
        Map<String, String> toResponseMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("requested", requestedProvider + "/" + requestedModel);
            m.put("actual", actualProvider + "/" + actualModel);
            m.put("reason", "Requested model is not available for this tenant - fell back to the catalog default.");
            return m;
        }
    }

    private String formatModelNotAvailable(String requestedProvider, String requestedModelName) {
        List<AvailableModel> available = modelCatalogService.listAvailableModels();

        StringBuilder sb = new StringBuilder();
        sb.append("ERROR: Model '").append(requestedProvider).append("/").append(requestedModelName)
          .append("' is not available for this tenant.\n\n");

        if (available.isEmpty()) {
            sb.append("No models are currently enabled on this platform. ")
              .append("This is an admin-side configuration issue - you cannot resolve it from this tool. ")
              .append("Stop retrying and tell the user the platform admin needs to configure at least one model.");
            return sb.toString();
        }
        sb.append("Call agent(action='help_models') to see the live catalog, or simply omit ")
          .append("model_provider/model_name on your next call to use the platform default.\n\n");

        // Group by provider, preserving the order returned by listAvailableModels()
        // (which itself comes from getModelsWithOverrides() sorted by displayOrder).
        Map<String, List<AvailableModel>> byProvider = new LinkedHashMap<>();
        for (AvailableModel am : available) {
            byProvider.computeIfAbsent(am.provider(), k -> new ArrayList<>()).add(am);
        }

        // Did the requested model_name exist under a DIFFERENT provider?
        // Give a targeted hint - this is the most common mistake (LLM pairs
        // gpt-5 with 'anthropic' because of training-data noise).
        if (requestedModelName != null) {
            for (AvailableModel am : available) {
                if (am.modelId().equals(requestedModelName) && !am.provider().equals(requestedProvider)) {
                    sb.append("HINT: '").append(requestedModelName)
                      .append("' belongs to provider '").append(am.provider())
                      .append("', not '").append(requestedProvider).append("'.\n\n");
                    break;
                }
            }
        }

        sb.append("AVAILABLE (provider → model_name):\n");
        for (Map.Entry<String, List<AvailableModel>> e : byProvider.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" → ");
            StringBuilder modelList = new StringBuilder();
            for (AvailableModel am : e.getValue()) {
                if (modelList.length() > 0) modelList.append(", ");
                modelList.append(am.modelId());
                if (am.tier() != null && !am.tier().isBlank() && !"mid".equals(am.tier())) {
                    modelList.append(" (").append(am.tier()).append(")");
                }
            }
            sb.append(modelList).append("\n");
        }
        sb.append("\nRetry with one of the pairs above. See agent(action='help') for more details.");
        return sb.toString();
    }

    /**
     * Build the scope-aware HttpHeaders for cross-service trigger-service calls.
     *
     * <p>2026-05-21 follow-up to commits d4ad7ad27 + 39f94d911 - trigger-service
     * `/api/internal/trigger/schedules/*` endpoints have moved to strict-isolation
     * + canAccess role checks. Without X-User-ID + X-Organization-ID +
     * X-Organization-Role on the outbound RestTemplate exchange, non-OWNER org
     * members hit "Access restricted" on agent.create/update/delete schedule
     * fan-out. Mirrors the helper in {@code AgentConversationModule.buildScopeHeaders}.
     */
    private HttpHeaders buildScopeHeaders(String tenantId, ToolExecutionContext context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set("X-User-ID", tenantId);
        }
        if (context != null) {
            String orgId = context.orgId();
            if (orgId != null && !orgId.isBlank()) {
                headers.set("X-Organization-ID", orgId);
            }
            String orgRole = context.orgRole();
            if (orgRole != null && !orgRole.isBlank()) {
                headers.set("X-Organization-Role", orgRole);
            }
        }
        return headers;
    }
}
