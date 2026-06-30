package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides agent configuration from orchestrator-service.
 * Fetches agent details (systemPrompt, model, temperature, etc.) by agentId.
 */
@Slf4j
@Service
public class AgentConfigProvider {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${orchestrator.service.url:http://localhost:8099}")
    private String orchestratorUrl;

    @Value("${services.agent-service.url:http://localhost:8090}")
    private String agentServiceUrl;

    public AgentConfigProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Tools/workflows restriction configuration parsed from toolsConfig JSONB.
     * Format: {"mode":"all|none|custom", "tools":["uuid1",...], "workflows":["wf-uuid1",...]}
     */
    public record ToolsConfig(String mode, List<String> tools, List<String> workflows, List<String> applications,
                              List<String> tables, List<String> interfaces, List<String> agents, Boolean webSearch,
                              // Per-resource access modes: "read" or "write" (default)
                              String tableAccessMode, String workflowAccessMode, String interfaceAccessMode,
                              String agentAccessMode, String applicationAccessMode, String skillAccessMode,
                              // Files attached to the agent (opt-in allow-list); absent/empty = full org access.
                              List<String> files,
                              // Per-family GRANT sentinel: "none" | "all" | "custom" | null.
                              // AUTHORITATIVE - no list fallback. The grant alone decides access:
                              // none=no access, all=unrestricted, custom=scoped to the family's id
                              // list. The id list is consulted ONLY as the "custom" payload, never to
                              // decide none/all/custom. An ABSENT grant (null) resolves to "none"
                              // (deny) as a safety net - never to the list, never to "all".
                              String workflowsGrant, String tablesGrant, String interfacesGrant,
                              String agentsGrant, String applicationsGrant,
                              // Files read/write axis (no grant - files are opt-in scoping). null ⇒ "write"
                              // (default). 'read' lets the agent list/get/view but blocks create_folder/move_to_folder.
                              // Appended last so positional constructors stay append-only.
                              String fileAccessMode) {
        /** Back-compat constructor (pre-files); files defaults to null = unrestricted, grants to null ⇒ "none" (deny). */
        public ToolsConfig(String mode, List<String> tools, List<String> workflows, List<String> applications,
                           List<String> tables, List<String> interfaces, List<String> agents, Boolean webSearch,
                           String tableAccessMode, String workflowAccessMode, String interfaceAccessMode,
                           String agentAccessMode, String applicationAccessMode, String skillAccessMode) {
            this(mode, tools, workflows, applications, tables, interfaces, agents, webSearch,
                 tableAccessMode, workflowAccessMode, interfaceAccessMode, agentAccessMode,
                 applicationAccessMode, skillAccessMode, null);
        }

        /** Back-compat constructor (pre-grants); the 5 family grants default to null ⇒ "none" (deny). */
        public ToolsConfig(String mode, List<String> tools, List<String> workflows, List<String> applications,
                           List<String> tables, List<String> interfaces, List<String> agents, Boolean webSearch,
                           String tableAccessMode, String workflowAccessMode, String interfaceAccessMode,
                           String agentAccessMode, String applicationAccessMode, String skillAccessMode,
                           List<String> files) {
            this(mode, tools, workflows, applications, tables, interfaces, agents, webSearch,
                 tableAccessMode, workflowAccessMode, interfaceAccessMode, agentAccessMode,
                 applicationAccessMode, skillAccessMode, files, null, null, null, null, null, null);
        }

        /** Files are opt-in: only a non-empty allow-list scopes the agent. */
        public boolean isFilesScoped() {
            return files != null && !files.isEmpty();
        }

        public boolean isToolsNone() {
            return "none".equals(mode);
        }

        public boolean isToolsCustom() {
            return "custom".equals(mode);
        }

        public boolean isToolsAll() {
            return mode == null || "all".equals(mode);
        }

        // GRANT sentinel - AUTHORITATIVE, no backward-compatibility fallback.
        // For the 5 internal resource families {workflows, tables, interfaces,
        // agents, applications} the per-family `<family>Grant` ∈ {none|all|custom}
        // is the SINGLE source of truth: "none" = no access, "all" = unrestricted,
        // "custom" = scoped to the family's id list. The id lists are consulted
        // ONLY as the "custom" payload (see ToolAccessControl.getAllowedIds) - never
        // to decide none/all/custom.
        // The companion full-backfill migration writes an explicit grant on EVERY
        // agent row and AgentService.normalizeToolsConfig sets one on every persist,
        // so a row without a grant should never exist at runtime. "none" is DENY-BY-
        // DEFAULT: it is the complement of all/custom, so an absent grant AND any
        // unrecognised value (e.g. a malformed "bogus") both resolve to "none" (deny) -
        // never to the list, never to "all". A row that somehow escaped the backfill, or
        // carries a junk grant, can only LOSE access, never silently gain it (preserves
        // the security rule "absent/unknown ≠ all" and keeps this read side from failing
        // OPEN). Companion AgentConfigProviderTest pins this contract.
        public boolean isWorkflowsNone() {
            return !isWorkflowsAll() && !isWorkflowsCustom();
        }

        public boolean isWorkflowsCustom() {
            return "custom".equals(workflowsGrant);
        }

        public boolean isWorkflowsAll() {
            return "all".equals(workflowsGrant);
        }

        public boolean isApplicationsNone() {
            return !isApplicationsAll() && !isApplicationsCustom();
        }

        public boolean isApplicationsCustom() {
            return "custom".equals(applicationsGrant);
        }

        public boolean isApplicationsAll() {
            return "all".equals(applicationsGrant);
        }

        public boolean isTablesNone() {
            return !isTablesAll() && !isTablesCustom();
        }

        public boolean isTablesCustom() {
            return "custom".equals(tablesGrant);
        }

        public boolean isTablesAll() {
            return "all".equals(tablesGrant);
        }

        public boolean isInterfacesNone() {
            return !isInterfacesAll() && !isInterfacesCustom();
        }

        public boolean isInterfacesCustom() {
            return "custom".equals(interfacesGrant);
        }

        public boolean isInterfacesAll() {
            return "all".equals(interfacesGrant);
        }

        public boolean isAgentsNone() {
            return !isAgentsAll() && !isAgentsCustom();
        }

        public boolean isAgentsCustom() {
            return "custom".equals(agentsGrant);
        }

        public boolean isAgentsAll() {
            return "all".equals(agentsGrant);
        }

        public boolean isWebSearchDisabled() {
            return Boolean.FALSE.equals(webSearch);
        }

        /**
         * Convert to Map&lt;String, Object&gt; compatible with {@link com.apimarketplace.agent.config.AgentModuleResolver}.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            if (mode != null) map.put("mode", mode);
            if (tables != null) map.put("tables", tables);
            if (interfaces != null) map.put("interfaces", interfaces);
            if (agents != null) map.put("agents", agents);
            if (workflows != null) map.put("workflows", workflows);
            if (applications != null) map.put("applications", applications);
            if (tools != null) map.put("tools", tools);
            if (files != null) map.put("files", files);
            if (webSearch != null) map.put("webSearch", webSearch);
            // Per-family grant sentinel - emit only when present so the runtime map
            // stays self-describing and AgentModuleResolver (which reads the raw map)
            // can drive access by the grant. Absent grant ⇒ key omitted ⇒ resolves to
            // "none" (deny) downstream, never to the list and never to "all".
            if (workflowsGrant != null) map.put("workflowsGrant", workflowsGrant);
            if (tablesGrant != null) map.put("tablesGrant", tablesGrant);
            if (interfacesGrant != null) map.put("interfacesGrant", interfacesGrant);
            if (agentsGrant != null) map.put("agentsGrant", agentsGrant);
            if (applicationsGrant != null) map.put("applicationsGrant", applicationsGrant);
            return map;
        }
    }

    /**
     * Agent configuration holder.
     */
    public record AgentConfig(
        String agentId,
        String name,
        String systemPrompt,
        String modelProvider,
        String modelName,
        Double temperature,
        Integer maxTokens,
        Integer maxIterations,
        ToolsConfig toolsConfig,
        Double creditBudget,
        Double creditsConsumed,
        // Per-agent guard overrides (V100) - nullable. Parsed from the same
        // /api/internal/agents/by-config/{id} response. Null ⇒ use platform default.
        Integer maxPerResourcePerTurn,
        Integer loopIdenticalStop,
        Integer loopConsecutiveStop,
        // Per-agent reasoning effort for bridge/CLI providers
        // (minimal|low|medium|high|xhigh). Null ⇒ inherit per-model default.
        String reasoningEffort
    ) {
        /** Back-compat constructor (no reasoningEffort) for existing call/test sites. */
        public AgentConfig(String agentId, String name, String systemPrompt, String modelProvider,
                           String modelName, Double temperature, Integer maxTokens, Integer maxIterations,
                           ToolsConfig toolsConfig, Double creditBudget, Double creditsConsumed,
                           Integer maxPerResourcePerTurn, Integer loopIdenticalStop, Integer loopConsecutiveStop) {
            this(agentId, name, systemPrompt, modelProvider, modelName, temperature, maxTokens, maxIterations,
                 toolsConfig, creditBudget, creditsConsumed, maxPerResourcePerTurn, loopIdenticalStop,
                 loopConsecutiveStop, null);
        }

        public boolean hasSystemPrompt() {
            return systemPrompt != null && !systemPrompt.isBlank();
        }

        public boolean hasModel() {
            return modelName != null && !modelName.isBlank();
        }

        public boolean hasProvider() {
            return modelProvider != null && !modelProvider.isBlank();
        }

        public boolean hasToolsConfig() {
            return toolsConfig != null;
        }
    }

    /**
     * Fetch agent configuration from orchestrator-service.
     *
     * @param agentId  The agent ID
     * @param tenantId The tenant ID for authorization
     * @return AgentConfig if found, null otherwise
     */
    /**
     * Fetch the pre-rendered task summary section for an agent's system prompt.
     * <p>
     * Returns a ready-to-append Markdown block (with a leading "## Tasks" header) when the
     * agent has pending/in-progress tasks, claimable backlog items, or recent completions
     * from its outbox. Returns an empty string when the agent has no tasks - callers should
     * skip injection in that case.
     * <p>
     * Never throws: any RPC/deserialisation error is logged and returns an empty string.
     *
     * @param agentId   Agent UUID (string form)
     * @param tenantId  Tenant to scope the query
     * @return Markdown prompt section, or "" if no tasks / on error
     */
    public String getTaskSummarySection(String agentId, String tenantId) {
        if (agentId == null || agentId.isBlank()) {
            return "";
        }
        try {
            String url = agentServiceUrl + "/api/internal/agents/" + agentId + "/task-summary";
            HttpHeaders headers = new HttpHeaders();
            if (tenantId != null) headers.set("X-User-ID", tenantId);
            // 2026-05-21 - forward org context so task summary reflects the
            // caller's workspace (team agents not in personal scope).
            OrgContextHeaderForwarder.forward(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return "";
            }
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("hasTasks").asBoolean(false)) {
                return "";
            }
            return body.path("promptSection").asText("");
        } catch (Exception e) {
            log.debug("Failed to fetch task summary for agent {}: {}", agentId, e.getMessage());
            return "";
        }
    }

    /**
     * Per-agent compaction overrides (enable + cadence). Both nullable: {@code null}
     * means "agent does not override this tier" so the caller falls back to the
     * conversation / YAML tiers (see {@code CompactionConfigResolver}).
     */
    public record CompactionOverride(Boolean enabled, Integer afterTurns) {
        static final CompactionOverride NONE = new CompactionOverride(null, null);
    }

    /**
     * Lightweight fetch of <em>only</em> the per-agent compaction overrides
     * ({@code compaction_enabled} / {@code compaction_after_turns}) from the same
     * {@code /api/internal/agents/by-config/{id}} payload the chat turn already
     * reads. Kept separate from {@link #getAgentConfig} so the post-turn
     * compaction orchestrator doesn't pay the full parse + INFO log just to read
     * two columns. Never throws: any error / missing agent yields
     * {@link CompactionOverride#NONE} so the caller degrades to the
     * conversation / YAML tiers.
     */
    public CompactionOverride getCompactionOverride(String agentId, String tenantId) {
        if (agentId == null || agentId.isBlank()) {
            return CompactionOverride.NONE;
        }
        try {
            String url = agentServiceUrl + "/api/internal/agents/by-config/" + agentId;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            // Forward the active-workspace context so team agents (not in personal
            // scope) resolve - the orchestrator runs inside TenantResolver.runWithOrgScope.
            OrgContextHeaderForwarder.forward(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return CompactionOverride.NONE;
            }
            JsonNode agent = objectMapper.readTree(response.getBody());
            Boolean enabled = (agent.has("compactionEnabled") && agent.get("compactionEnabled").isBoolean())
                    ? agent.get("compactionEnabled").asBoolean()
                    : null;
            Integer afterTurns = readPositiveInt(agent, "compactionAfterTurns");
            return new CompactionOverride(enabled, afterTurns);
        } catch (Exception e) {
            log.debug("Failed to fetch compaction override for agent {}: {}", agentId, e.getMessage());
            return CompactionOverride.NONE;
        }
    }

    public AgentConfig getAgentConfig(String agentId, String tenantId) {
        return getAgentConfig(agentId, tenantId, null, null);
    }

    public AgentConfig getAgentConfig(String agentId, String tenantId, String organizationId) {
        return getAgentConfig(agentId, tenantId, organizationId, null);
    }

    public AgentConfig getAgentConfig(String agentId, String tenantId, String organizationId, String organizationRole) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }

        try {
            String url = agentServiceUrl + "/api/internal/agents/by-config/" + agentId;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            if (organizationId != null && !organizationId.isBlank()) {
                headers.set("X-Organization-ID", organizationId.trim());
            }
            if (organizationRole != null && !organizationRole.isBlank()) {
                headers.set("X-Organization-Role", organizationRole.trim());
            }

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Failed to fetch agent {}: {}", agentId, response.getStatusCode());
                return null;
            }

            JsonNode agent = objectMapper.readTree(response.getBody());
            return parseAgentConfig(agentId, agent);

        } catch (Exception e) {
            log.warn("Error fetching agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    private AgentConfig parseAgentConfig(String agentId, JsonNode agent) {
        String name = getTextOrNull(agent, "name");
        String systemPrompt = getTextOrNull(agent, "systemPrompt");
        String modelProvider = getTextOrNull(agent, "modelProvider");
        String modelName = getTextOrNull(agent, "modelName");

        Double temperature = null;
        if (agent.has("temperature") && !agent.get("temperature").isNull()) {
            temperature = agent.get("temperature").asDouble();
        }

        Integer maxTokens = null;
        if (agent.has("maxTokens") && !agent.get("maxTokens").isNull()) {
            maxTokens = agent.get("maxTokens").asInt();
        }

        Integer maxIterations = null;
        if (agent.has("maxIterations") && !agent.get("maxIterations").isNull()) {
            maxIterations = agent.get("maxIterations").asInt();
        }

        // Parse toolsConfig JSONB
        ToolsConfig toolsConfig = parseToolsConfig(agent.get("toolsConfig"));

        // Credit budget (for bridge path budget enforcement)
        Double creditBudget = null;
        if (agent.has("creditBudget") && !agent.get("creditBudget").isNull()) {
            creditBudget = agent.get("creditBudget").asDouble();
        }
        Double creditsConsumed = null;
        if (agent.has("creditsConsumed") && !agent.get("creditsConsumed").isNull()) {
            creditsConsumed = agent.get("creditsConsumed").asDouble();
        }

        // Per-agent guard overrides (V100). Null ⇒ fall back to platform default.
        Integer maxPerResourcePerTurn = readPositiveInt(agent, "maxPerResourcePerTurn");
        Integer loopIdenticalStop = readPositiveInt(agent, "loopIdenticalStop");
        Integer loopConsecutiveStop = readPositiveInt(agent, "loopConsecutiveStop");

        log.info("Loaded agent config: id={}, name={}, provider={}, model={}, hasPrompt={}, maxIterations={}, toolsConfig={}, budget={}/consumed={}",
            agentId, name, modelProvider, modelName, systemPrompt != null && !systemPrompt.isBlank(), maxIterations,
            toolsConfig != null ? "mode=" + toolsConfig.mode() : "null",
            creditBudget, creditsConsumed);

        String reasoningEffort = getTextOrNull(agent, "reasoningEffort");

        return new AgentConfig(agentId, name, systemPrompt, modelProvider, modelName, temperature, maxTokens, maxIterations, toolsConfig, creditBudget, creditsConsumed,
            maxPerResourcePerTurn, loopIdenticalStop, loopConsecutiveStop, reasoningEffort);
    }

    /**
     * Read an optional positive integer field from the agent JSON payload.
     * Returns null when the field is absent, null, non-numeric, or ≤ 0 - per
     * V100 column CHECK constraints, valid overrides are always positive.
     */
    private Integer readPositiveInt(JsonNode agent, String field) {
        if (!agent.has(field) || agent.get(field).isNull()) {
            return null;
        }
        JsonNode node = agent.get(field);
        if (!node.isIntegralNumber()) {
            return null;
        }
        int value = node.asInt();
        return value > 0 ? value : null;
    }

    @SuppressWarnings("unchecked")
    private ToolsConfig parseToolsConfig(JsonNode node) {
        if (node == null || node.isNull()) {
            return null; // No restriction = mode "all"
        }

        try {
            // mode is allowed to default to "all" (MCP/catalogue product behavior),
            // but the rest of the record (workflows/tables/.../agents lists) MUST
            // be parsed even when mode is absent - otherwise legacy/V163-backfilled
            // rows that lack `mode` skip the entire applyToolsConfigCredentials
            // pipeline and the runtime falls back to "no restriction" for the 5
            // internal resource categories. Pre-fix this short-circuited with
            // `if (mode == null) return null;` and was the deepest leg of the
            // "absent ≠ all" leak. Companion test:
            // AgentConfigProviderTest#parseToolsConfigWithoutModeStillProduces5InternalLists.
            String mode = getTextOrNull(node, "mode");
            if (mode == null) {
                mode = "all";
            }

            List<String> tools = new ArrayList<>();
            if (node.has("tools") && node.get("tools").isArray()) {
                for (JsonNode toolNode : node.get("tools")) {
                    tools.add(toolNode.asText());
                }
            }

            List<String> workflows = null;
            if (node.has("workflows")) {
                workflows = new ArrayList<>();
                if (node.get("workflows").isArray()) {
                    for (JsonNode wfNode : node.get("workflows")) {
                        workflows.add(wfNode.asText());
                    }
                }
            }

            List<String> applications = null;
            if (node.has("applications")) {
                applications = new ArrayList<>();
                if (node.get("applications").isArray()) {
                    for (JsonNode appNode : node.get("applications")) {
                        applications.add(appNode.asText());
                    }
                }
            }

            List<String> tables = null;
            if (node.has("tables")) {
                tables = new ArrayList<>();
                if (node.get("tables").isArray()) {
                    for (JsonNode tableNode : node.get("tables")) {
                        tables.add(tableNode.asText());
                    }
                }
            }

            List<String> interfaces = null;
            if (node.has("interfaces")) {
                interfaces = new ArrayList<>();
                if (node.get("interfaces").isArray()) {
                    for (JsonNode ifNode : node.get("interfaces")) {
                        interfaces.add(ifNode.asText());
                    }
                }
            }

            List<String> agents = null;
            if (node.has("agents")) {
                agents = new ArrayList<>();
                if (node.get("agents").isArray()) {
                    for (JsonNode agentNode : node.get("agents")) {
                        agents.add(agentNode.asText());
                    }
                }
            }

            // Files attached to the agent (opt-in allow-list). Unlike the 5 internal
            // lists this is NOT subject to the "absent === []" rule: absent/empty means
            // full org-scoped file access; only a non-empty list scopes the agent.
            List<String> files = null;
            if (node.has("files") && node.get("files").isArray()) {
                files = new ArrayList<>();
                for (JsonNode fileNode : node.get("files")) {
                    files.add(fileNode.asText());
                }
            }

            Boolean webSearch = null;
            if (node.has("webSearch") && node.get("webSearch").isBoolean()) {
                webSearch = node.get("webSearch").asBoolean();
            }

            // Per-resource access modes: "read" or "write" (null = write = default)
            String tableAccessMode = getTextOrNull(node, "tableAccessMode");
            String workflowAccessMode = getTextOrNull(node, "workflowAccessMode");
            String interfaceAccessMode = getTextOrNull(node, "interfaceAccessMode");
            String agentAccessMode = getTextOrNull(node, "agentAccessMode");
            String applicationAccessMode = getTextOrNull(node, "applicationAccessMode");
            String skillAccessMode = getTextOrNull(node, "skillAccessMode");
            // Files read/write axis (no grant). null ⇒ "write" (default).
            String fileAccessMode = getTextOrNull(node, "fileAccessMode");

            // Per-family GRANT sentinel: "none" | "all" | "custom" | null (absent).
            // AUTHORITATIVE - the grant alone decides access; the id list is only the
            // "custom" payload. Absent ⇒ null ⇒ the family resolves to "none" (deny),
            // never to the list and never to "all" (a row that escaped the backfill can
            // only LOSE access, never silently gain it).
            String workflowsGrant = getTextOrNull(node, "workflowsGrant");
            String tablesGrant = getTextOrNull(node, "tablesGrant");
            String interfacesGrant = getTextOrNull(node, "interfacesGrant");
            String agentsGrant = getTextOrNull(node, "agentsGrant");
            String applicationsGrant = getTextOrNull(node, "applicationsGrant");

            return new ToolsConfig(mode, tools, workflows, applications, tables, interfaces, agents, webSearch,
                    tableAccessMode, workflowAccessMode, interfaceAccessMode, agentAccessMode, applicationAccessMode, skillAccessMode,
                    files, workflowsGrant, tablesGrant, interfacesGrant, agentsGrant, applicationsGrant, fileAccessMode);
        } catch (Exception e) {
            log.warn("Failed to parse toolsConfig: {}", e.getMessage());
            return null;
        }
    }

    private String getTextOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return (value == null || value.isBlank()) ? null : value;
    }

    // ==================== Agent Skills Summary ====================

    public record SkillSummary(String id, String name, String description, String folderId,
                               boolean isActive, boolean isDefaultActive) {}
    public record FolderSummary(String id, String name, String parentId, boolean isGlobal) {}
    public record AgentSkillsSummary(List<SkillSummary> skills, List<FolderSummary> folders) {}

    /**
     * Fetch lightweight skills summary for an agent from orchestrator-service.
     * Returns null on error (graceful degradation - agent works without skills).
     */
    public AgentSkillsSummary getAgentSkillsSummary(String agentId, String tenantId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }

        try {
            String url = agentServiceUrl + "/api/agents/" + agentId + "/skills/summary";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            // 2026-05-21 - forward org context for skills summary lookup.
            OrgContextHeaderForwarder.forward(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Failed to fetch skills summary for agent {}: {}", agentId, response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            return parseSkillsSummary(root);

        } catch (Exception e) {
            log.warn("Error fetching skills summary for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch lightweight skills summary by skill IDs (not agent-scoped).
     * Used for general chat where user selects skills without an agent.
     */
    public AgentSkillsSummary getSkillsSummaryByIds(List<String> skillIds, String tenantId) {
        if (skillIds == null || skillIds.isEmpty()) {
            return null;
        }

        try {
            String url = agentServiceUrl + "/api/skills/summary";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            // 2026-05-21 - forward org context for cross-skill summary lookup.
            OrgContextHeaderForwarder.forward(headers);

            Map<String, Object> body = Map.of("skillIds", skillIds);
            String requestBody = objectMapper.writeValueAsString(body);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Failed to fetch skills summary by IDs: {}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            return parseSkillsSummary(root);

        } catch (Exception e) {
            log.warn("Error fetching skills summary by IDs: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Available AI Models Catalog (platform-wide) ====================

    /**
     * Flat entry of the platform-wide AI model catalog, mirroring
     * {@code agent-service}'s {@code ModelCatalogService.AvailableModel}.
     * Kept tiny on purpose - the caller embeds this list verbatim in the agent
     * system prompt, so anything heavier than (provider, modelId, tier) would
     * bloat the context window.
     */
    public record AvailableModel(String provider, String modelId, String tier, int displayOrder,
                                 String defaultReasoningEffort, Integer maxOutputTokens) {
        /** Back-compat 4-arg constructor (no per-model default effort) for existing call/test sites. */
        public AvailableModel(String provider, String modelId, String tier, int displayOrder) {
            this(provider, modelId, tier, displayOrder, null, null);
        }

        /** Back-compat 5-arg constructor (effort, no output cap) for existing call/test sites. */
        public AvailableModel(String provider, String modelId, String tier, int displayOrder,
                              String defaultReasoningEffort) {
            this(provider, modelId, tier, displayOrder, defaultReasoningEffort, null);
        }
    }

    /**
     * Cached catalog snapshot with absolute expiry. TTL (5 min) balances two
     * concerns: (a) amortise the agent-service RPC over the typical chat burst,
     * (b) still pick up admin tier changes within minutes without needing
     * cross-service invalidation. Tiers rarely change - a 5 min propagation
     * delay is well below any human-visible SLA for re-tagging a model.
     */
    private record CachedCatalog(List<AvailableModel> snapshot, Instant expiresAt) {
        boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private static final Duration CATALOG_CACHE_TTL = Duration.ofMinutes(5);
    private final AtomicReference<CachedCatalog> catalogCache = new AtomicReference<>();

    /**
     * Tracks whether the last refresh attempt failed so we only WARN once per
     * sustained outage instead of spamming logs on every conversation init.
     * Flips back to {@code false} on the first successful refresh.
     */
    private final AtomicReference<Boolean> catalogFailureLogged = new AtomicReference<>(Boolean.FALSE);

    /**
     * Fetch the platform-wide AI model catalog (cached).
     *
     * <p>The catalog is <strong>not tenant-scoped</strong> - it is controlled by the
     * platform admin via {@code /settings/ai-providers} and shared across all tenants.
     * Conversation-service caches the agent-service response for {@link #CATALOG_CACHE_TTL}
     * to avoid hammering the RPC on every conversation init.
     *
     * <p>Concurrency: thread-safe via {@link AtomicReference} - a thundering-herd
     * expiry will cause at most a handful of duplicate RPCs, which is acceptable
     * given the 5 min window and the endpoint's read-only nature.
     *
     * <p>Never throws: RPC failures return an empty list so the caller (system-prompt
     * injector) can silently skip the section without breaking the conversation.
     * A single WARN is emitted per outage - repeated failures stay silent until the
     * next successful refresh, then the cycle repeats.
     *
     * @return non-null (possibly empty) list of available models
     */
    public List<AvailableModel> getAvailableModels() {
        Instant now = Instant.now();
        CachedCatalog current = catalogCache.get();
        if (current != null && current.isFresh(now)) {
            return current.snapshot();
        }

        // Cache miss or expired - fetch. Multiple threads may race here; the
        // CAS below is optimistic, the worst case is a handful of duplicate RPCs
        // during a thundering-herd expiry. Acceptable given 5 min window.
        List<AvailableModel> fresh = fetchAvailableModelsRemote();
        if (fresh == null) {
            // Fetch failed - serve stale if we have it, otherwise empty.
            // This keeps the agent usable during transient agent-service outages
            // instead of falling back to hallucination.
            if (current != null) {
                log.debug("Serving stale model catalog ({} entries) after refresh failure",
                        current.snapshot().size());
                return current.snapshot();
            }
            return List.of();
        }

        CachedCatalog refreshed = new CachedCatalog(fresh, now.plus(CATALOG_CACHE_TTL));
        catalogCache.set(refreshed);
        // Reset failure flag on successful refresh so the next outage gets a
        // fresh WARN (otherwise long-running processes would permanently mute).
        catalogFailureLogged.set(Boolean.FALSE);
        return fresh;
    }

    /**
     * Look up a model in the cached catalog by {@code modelId} alone, ignoring
     * the provider field. Case-insensitive.
     *
     * <p><b>Why this exists separately from a {@code (provider, modelId)} lookup.</b>
     * The tier attached to a model is a platform-wide capability label - it is
     * defined in {@code ai.pricing.ranking.tiers} keyed by modelId, and the
     * agent-service returns the same tier value for the same modelId regardless
     * of which provider entry the client supplies. Callers that only need the
     * tier (e.g. schema slimming) should use this method so a client sending
     * a stale or mis-mapped {@code provider} value (e.g. {@code google} for a
     * bridge-only model) still resolves to a real tier instead of falling
     * through to the unknown-model default.
     *
     * <p>Callers that need to validate routing (is this model actually callable
     * on this provider?) should keep using the strict {@code (provider, modelId)}
     * lookup - tier equivalence does not imply routing equivalence.
     *
     * <p>Returns {@code null} on catalog miss. Never throws.
     */
    public AvailableModel findAvailableModelByModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        try {
            return getAvailableModels().stream()
                    .filter(m -> modelId.equalsIgnoreCase(m.modelId()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("findAvailableModelByModelId lookup failed for {}: {}", modelId, e.getMessage());
            return null;
        }
    }

    /**
     * Perform the raw RPC to agent-service. Returns {@code null} on failure so
     * {@link #getAvailableModels()} can distinguish "RPC failed" from
     * "RPC returned empty catalog" (both are valid states but the former
     * should keep any existing cache).
     */
    private List<AvailableModel> fetchAvailableModelsRemote() {
        try {
            String url = agentServiceUrl + "/api/internal/agent/models/flat";
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                warnCatalogFailureOnce("status " + response.getStatusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.isArray()) {
                warnCatalogFailureOnce("non-array response body");
                return null;
            }
            List<AvailableModel> out = new ArrayList<>();
            for (JsonNode node : root) {
                String provider = getTextOrNull(node, "provider");
                String modelId = getTextOrNull(node, "modelId");
                if (provider == null || modelId == null) continue;
                String tier = getTextOrNull(node, "tier");
                int displayOrder = node.has("displayOrder") ? node.get("displayOrder").asInt(999) : 999;
                String defaultReasoningEffort = getTextOrNull(node, "defaultReasoningEffort");
                Integer maxOutputTokens = (node.has("maxOutputTokens") && node.get("maxOutputTokens").isIntegralNumber())
                        ? node.get("maxOutputTokens").asInt() : null;
                out.add(new AvailableModel(provider, modelId, tier != null ? tier : "mid", displayOrder,
                        defaultReasoningEffort, maxOutputTokens));
            }
            return out;
        } catch (Exception e) {
            warnCatalogFailureOnce(e.getMessage());
            return null;
        }
    }

    /**
     * Emit a WARN log for the first failure in an outage, then fall back to
     * DEBUG until the next successful refresh resets the flag. Prevents log
     * spam when agent-service is down while still surfacing the incident.
     */
    private void warnCatalogFailureOnce(String reason) {
        if (catalogFailureLogged.compareAndSet(Boolean.FALSE, Boolean.TRUE)) {
            log.warn("Failed to refresh model catalog from agent-service: {}. " +
                    "Falling back to cached/empty list; further failures will be logged at DEBUG.", reason);
        } else {
            log.debug("Model catalog refresh still failing: {}", reason);
        }
    }

    private AgentSkillsSummary parseSkillsSummary(JsonNode root) {
        List<SkillSummary> skills = new ArrayList<>();
        if (root.has("skills") && root.get("skills").isArray()) {
            for (JsonNode node : root.get("skills")) {
                skills.add(new SkillSummary(
                    getTextOrNull(node, "id"),
                    getTextOrNull(node, "name"),
                    getTextOrNull(node, "description"),
                    getTextOrNull(node, "folderId"),
                    node.has("isActive") && node.get("isActive").asBoolean(true),
                    node.has("isDefaultActive") && node.get("isDefaultActive").asBoolean(false)
                ));
            }
        }

        List<FolderSummary> folders = new ArrayList<>();
        if (root.has("folders") && root.get("folders").isArray()) {
            for (JsonNode node : root.get("folders")) {
                folders.add(new FolderSummary(
                    getTextOrNull(node, "id"),
                    getTextOrNull(node, "name"),
                    getTextOrNull(node, "parentId"),
                    node.has("isGlobal") && node.get("isGlobal").asBoolean(false)
                ));
            }
        }

        return new AgentSkillsSummary(skills, folders);
    }

    /**
     * V275/V276 (2026-05-21) - fetch the effective default-active skills for
     * the calling user × workspace. Resolves
     * {@code COALESCE(user_override.active, skill.is_default_active)} server-side;
     * conversation-service just gets the final list to inject in the system
     * prompt. Used by general-chat when no per-conversation
     * {@code defaultSkillIds} arrived from the client.
     */
    public AgentSkillsSummary getDefaultActiveSkillsSummary(String tenantId) {
        try {
            String url = agentServiceUrl + "/api/skills/default-active/summary";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            OrgContextHeaderForwarder.forward(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Failed to fetch effective default-active skills summary: {}",
                    response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            return parseSkillsSummary(root);

        } catch (Exception e) {
            log.warn("Error fetching effective default-active skills summary: {}", e.getMessage());
            return null;
        }
    }
}
