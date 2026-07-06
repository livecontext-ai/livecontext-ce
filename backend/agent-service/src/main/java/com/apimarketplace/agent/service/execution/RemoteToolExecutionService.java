package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.prompt.ConversationToolDefinitions;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationGuard;
import com.apimarketplace.agent.tools.remote.ToolServiceTopology;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ToolExecutionService implementation for agent-service.
 * Delegates tool calls to orchestrator-service via HTTP, following the same
 * pattern as ConversationToolExecutionService in conversation-service.
 *
 * This is marked @Primary to override the default ToolExecutionService from shared-agent-lib
 * (if any exists). When AgentLoopService needs to execute a tool, it goes through this service,
 * which routes the call back to orchestrator's /api/agent-tools/execute endpoint.
 */
@Slf4j
@Service
public class RemoteToolExecutionService implements ToolExecutionService {

    /**
     * Tools that must execute in conversation-service (they need DB access to conversation tables).
     * When __toolCallbackUrl__ is present in credentials AND the tool is in this set,
     * route to conversation-service instead of orchestrator.
     * Derived from shared {@link ConversationToolDefinitions} - single source of truth.
     */
    private static final Set<String> CONVERSATION_LOCAL_TOOLS =
        ConversationToolDefinitions.ALL_CONVERSATION_TOOL_NAMES;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${services.orchestrator-url:http://localhost:8099}")
    private String orchestratorUrl;

    @Value("${services.datasource-url:http://localhost:8088}")
    private String datasourceUrl;

    @Value("${services.interface-url:http://localhost:8089}")
    private String interfaceUrl;

    @Value("${services.catalog-url:http://localhost:8081}")
    private String catalogUrl;

    /**
     * Sub-agent execution handler - intercepts agent(action='execute') locally.
     * @Lazy breaks the circular dependency: AgentLoopService → this → SubAgentExecutionHandler → AgentLoopService
     */
    @Lazy
    @Autowired(required = false)
    private SubAgentExecutionHandler subAgentExecutionHandler;

    /**
     * Local tool providers - handle agent and skill tools directly (no orchestrator hop).
     */
    @Lazy
    @Autowired(required = false)
    private com.apimarketplace.agent.tools.agent.AgentToolsProvider agentToolsProvider;

    @Lazy
    @Autowired(required = false)
    private com.apimarketplace.agent.tools.skill.SkillToolsProvider skillToolsProvider;

    public RemoteToolExecutionService(ObjectMapper objectMapper) {
        this.restTemplate = createToolExecutionRestTemplate();
        this.objectMapper = objectMapper;
    }

    /**
     * Create a RestTemplate with timeouts suitable for tool execution.
     *
     * <p>Read timeout MUST exceed the max per-tool timeout. {@code web_search}
     * is 640 s (the agent_browse path) - the orchestrator's BLPOP for
     * agent_browse is 600 s + cleanup hook (drain1 1 s + sid retry 0.3 s +
     * abort POST ≤15 s + LREM/DEL ≤50 ms + drain2 10 s) = ~26 s worst case
     * = ~626 s end-to-end before the orchestrator returns. We need a hard
     * margin above that or the agent client closes the HTTP connection
     * mid-cleanup, defeating the slot-release fix. 12 min gives ~94 s slack.
     */
    private static RestTemplate createToolExecutionRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofMinutes(12));
        return new RestTemplate(factory);
    }

    @Override
    public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                  String tenantId, Map<String, Object> credentials) {
        long startTime = System.currentTimeMillis();
        String toolName = toolCall.toolName();

        log.info("Executing tool remotely via orchestrator: {} (tenantId: {})", toolName, tenantId);

        // === Tool authorization gate (interactive chat only) ===
        // Sensitive actions (acquire/execute/…) require synchronous user approval in a
        // general conversation. Exempt for workflow/task/sub-agent/CLI-bridge contexts.
        // Runs BEFORE any local interception so agent(action='execute') is gated too.
        ToolResult authResult = checkToolAuthorization(toolCall, credentials, startTime);
        if (authResult != null) {
            return authResult;
        }

        try {
            // Agent tool: handle ALL actions locally (no orchestrator hop)
            if ("agent".equals(toolName)) {
                Object action = toolCall.arguments() != null ? toolCall.arguments().get("action") : null;
                // Sub-agent execute is handled by SubAgentExecutionHandler (needs special streaming/observability)
                if ("execute".equals(action) && subAgentExecutionHandler != null) {
                    log.info("Intercepting agent(action='execute') locally via SubAgentExecutionHandler");
                    return subAgentExecutionHandler.execute(toolCall, tenantId, credentials);
                }
                // All other agent actions: CRUD, help, get_history, share
                if (agentToolsProvider != null) {
                    log.info("Intercepting agent(action='{}') locally via AgentToolsProvider", action);
                    return executeLocalProvider(agentToolsProvider, toolCall, tenantId, credentials, startTime);
                }
            }

            // Skill tool: handle ALL actions locally (no orchestrator hop)
            if ("skill".equals(toolName) && skillToolsProvider != null) {
                Object action = toolCall.arguments() != null ? toolCall.arguments().get("action") : null;
                log.info("Intercepting skill(action='{}') locally via SkillToolsProvider", action);
                return executeLocalProvider(skillToolsProvider, toolCall, tenantId, credentials, startTime);
            }

            // Check if this tool should route to conversation-service via callback URL
            String callbackUrl = credentials != null ? (String) credentials.get("__toolCallbackUrl__") : null;
            if (callbackUrl != null && CONVERSATION_LOCAL_TOOLS.contains(toolName)) {
                return executeViaCallbackUrl(toolCall, tenantId, credentials, callbackUrl, startTime);
            }

            // Route to the owning service (datasource / interface / catalog). agent+skill
            // are already handled locally above; ORCHESTRATOR + AGENT fall through to the
            // core/MCP path below. Owner rules come from the shared ToolServiceTopology.
            ToolServiceTopology.ServiceKey owner = ToolServiceTopology.serviceFor(toolName);
            String ownerUrl = switch (owner) {
                case DATASOURCE -> datasourceUrl;
                case INTERFACE -> interfaceUrl;
                case CATALOG -> catalogUrl;
                default -> null;
            };
            if (ownerUrl != null) {
                log.info("Routing tool '{}' to {}", toolName, owner);
                return executeRemoteCoreTools(toolCall, tenantId, credentials, ownerUrl, startTime);
            }

            // Check if this is a core tool or MCP tool
            boolean isMcpTool = toolDefinition.apiSlug() != null && toolDefinition.toolSlug() != null;

            if (isMcpTool) {
                return executeMcpTool(toolCall, toolDefinition, tenantId, credentials, startTime);
            } else {
                return executeCoreTools(toolCall, tenantId, credentials, startTime);
            }

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("HTTP error executing tool {}: {} - {}", toolName, e.getStatusCode(), e.getMessage());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "remote_tool_error");
            String errorMessage = "HTTP error: " + e.getStatusCode();

            try {
                String responseBody = e.getResponseBodyAsString();
                if (responseBody != null && !responseBody.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
                    if (errorResponse.containsKey("error")) {
                        errorMessage = String.valueOf(errorResponse.get("error"));
                    }
                    Object responseMetadata = errorResponse.get("metadata");
                    if (responseMetadata instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metaMap = (Map<String, Object>) responseMetadata;
                        metadata.putAll(metaMap);
                    }
                }
            } catch (Exception parseError) {
                log.debug("Could not parse HTTP error response: {}", parseError.getMessage());
            }

            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error(errorMessage)
                .durationMs(duration)
                .metadata(metadata)
                .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Remote execution error: " + e.getMessage())
                .durationMs(duration)
                .build();
        }
    }

    @Override
    public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
        // All tools are assumed available when executing remotely
        // Orchestrator validates tool availability on its side
        return true;
    }

    /**
     * Returns a gating {@code ToolResult} (carrying {@code toolAuthorizationRequired}
     * metadata) when this call matches the sensitive-action policy in an interactive
     * chat AND has not yet been authorized; otherwise {@code null} (the call proceeds
     * normally). The streaming callback turns the metadata into the chat authorization
     * card LIVE without pausing the agent loop (async, exactly like the credential card);
     * the tool itself is not executed and the result tells the agent not to retry.
     *
     * <p>Package-private so {@code RemoteToolExecutionServiceTest} can exercise the
     * gate decision directly without triggering downstream HTTP/local execution.
     */
    ToolResult checkToolAuthorization(ToolCall toolCall, Map<String, Object> credentials, long startTime) {
        String rule = ToolAuthorizationGuard.matchedRule(toolCall.toolName(),
                toolCall.arguments());
        if (rule == null) {
            return null; // not a sensitive action
        }
        if (!ToolAuthorizationScopeResolver.isActive(credentials)) {
            return null; // exempt context: workflow / task / sub-agent / CLI-bridge / headless
        }
        if (isAlreadyAuthorized(credentials, rule)) {
            return null; // approved this turn (transient resume) or persisted "always authorize"
        }
        log.info("Tool authorization required for rule={} (toolCallId={}) - pausing for user approval",
                rule, toolCall.id());
        return buildAuthorizationRequiredResult(toolCall, rule, startTime);
    }

    private boolean isAlreadyAuthorized(Map<String, Object> credentials, String rule) {
        if (credentials == null) {
            return false;
        }
        Object approved = credentials.get("__approvedToolActions__");
        // "*" is the conversation-wide blanket grant (chatConfig.autoAuthorizeTools): the user
        // opted into running sensitive actions without being asked for the rest of this conversation.
        return approved instanceof Collection<?> col && (col.contains(rule) || col.contains("*"));
    }

    private ToolResult buildAuthorizationRequiredResult(ToolCall toolCall, String rule, long startTime) {
        Map<String, Object> args = toolCall.arguments();
        String action = (args != null && args.get("action") != null)
                ? String.valueOf(args.get("action")) : null;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolAuthorizationRequired", true);
        metadata.put("rule", rule);
        metadata.put("toolName", toolCall.toolName());
        if (action != null) metadata.put("action", action);
        if (toolCall.id() != null) metadata.put("toolCallId", toolCall.id());
        String argsSummary = summarizeArguments(args);
        if (argsSummary != null) metadata.put("argsSummary", argsSummary);
        // For application:acquire the frontend lets the USER install the app directly via the
        // marketplace install modal - surface the publication id (the acquire arg is application_id)
        // so the card can fetch the publication and open that modal on approve.
        if ("application:acquire".equals(rule) && args != null && args.get("application_id") != null) {
            metadata.put("applicationId", String.valueOf(args.get("application_id")));
        }

        // POV-agent content: the user has been asked to authorize this action - do NOT retry/loop.
        // The run is NOT paused (async); the agent should continue with other work or finish.
        //
        // CRITICAL framing: this result means the action has NOT happened. The tool was NOT
        // executed - nothing ran, no result exists. `success: true` here only means "the
        // authorization request was surfaced correctly", NOT "the action succeeded". The
        // explicit `executed: false` flag exists so the model never mistakes the gate for a
        // completed run and never invents/describes an outcome (which previously caused the
        // agent to narrate fake COMPLETED workflow results).
        Map<String, Object> structured = new HashMap<>();
        structured.put("status", "authorization_required");
        structured.put("rule", rule);
        structured.put("executed", false);
        if ("application:acquire".equals(rule)) {
            // App install is performed OUT OF BAND by the user via the marketplace install
            // modal - it does NOT run in this conversation. After installing, the user starts
            // a fresh request/turn. So the agent must not assume the app exists yet this turn.
            structured.put("message", "The application has NOT been installed. Installing is done by "
                    + "the USER, not by you: an install card was surfaced, and the user installs the "
                    + "application themselves from the marketplace modal. The install does NOT happen in "
                    + "this turn - after installing, the user will come back and ask again (a new turn). "
                    + "Do NOT call this action again, do NOT claim or assume the app is installed/acquired, "
                    + "and do NOT use it as if it existed. Continue with other work or finish your turn.");
        } else {
            structured.put("message", "The action has NOT run yet and NO result exists - do not describe, "
                    + "summarize, or invent any outcome. The user has only been asked to authorize it. "
                    + "Do not call this action again until the user decides; continue with other work or "
                    + "finish your turn. If the user approves, it will run and you will receive the real "
                    + "result then.");
        }

        long duration = System.currentTimeMillis() - startTime;
        try {
            return ToolResult.builder()
                    .toolCall(toolCall)
                    .success(true)
                    .content(objectMapper.writeValueAsString(structured))
                    .durationMs(duration)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            return ToolResult.builder()
                    .toolCall(toolCall)
                    .success(true)
                    .content("This action requires the user's authorization before it can run.")
                    .durationMs(duration)
                    .metadata(metadata)
                    .build();
        }
    }

    private String summarizeArguments(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(args);
            return json.length() > 240 ? json.substring(0, 237) + "..." : json;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Execute a core tool via orchestrator-service's /api/agent-tools/execute endpoint.
     */
    private ToolResult executeCoreTools(ToolCall toolCall, String tenantId,
                                         Map<String, Object> credentials, long startTime) {
        return executeRemoteCoreTools(toolCall, tenantId, credentials, orchestratorUrl, startTime);
    }

    /**
     * Execute a core tool via a remote service's /api/agent-tools/execute endpoint.
     * Used for routing to orchestrator, datasource-service, interface-service, etc.
     */
    private ToolResult executeRemoteCoreTools(ToolCall toolCall, String tenantId,
                                               Map<String, Object> credentials, String baseUrl, long startTime) {
        Map<String, Object> request = new HashMap<>();
        request.put("tool", toolCall.toolName());
        request.put("parameters", toolCall.arguments());
        request.put("toolCallId", toolCall.id());
        if (tenantId != null) {
            request.put("tenantId", tenantId);
        }

        // Forward relevant credentials for context
        if (credentials != null) {
            forwardCredentials(request, credentials);
        }

        String url = baseUrl + "/api/agent-tools/execute";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
            headers.set("X-User-ID", tenantId);
        }
        // Phase 3 of MIGRATION_ORG_ID_NOT_NULL.md - propagate org context from
        // the credentials map (__orgId__ / __orgRole__ convention used by
        // SubAgentExecutionHandler, AgentContextBuilder, AgentNode.applyOrgContext).
        // Without this header, downstream INSERTs in orchestrator/interface/etc.
        // stamp organization_id = NULL, which fails Phase 6 NOT NULL.
        applyOrgHeaders(headers, credentials);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = restTemplate.exchange(
            url, HttpMethod.POST, entity, Map.class);

        long duration = System.currentTimeMillis() - startTime;

        if (response.getBody() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return parseExecutionResponse(toolCall, body, duration);
        } else {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Unexpected response: " + response.getStatusCode())
                .durationMs(duration)
                .build();
        }
    }

    /**
     * Execute an MCP tool via orchestrator-service (which proxies to MCP gateway).
     */
    private ToolResult executeMcpTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                       String tenantId, Map<String, Object> credentials, long startTime) {
        // For MCP tools, delegate through orchestrator which knows how to reach MCP gateway
        Map<String, Object> request = new HashMap<>();
        request.put("tool", toolCall.toolName());
        request.put("parameters", toolCall.arguments());
        request.put("toolCallId", toolCall.id());
        request.put("apiSlug", toolDefinition.apiSlug());
        request.put("toolSlug", toolDefinition.toolSlug());
        request.put("toolId", toolDefinition.id());
        if (tenantId != null) {
            request.put("tenantId", tenantId);
        }
        if (credentials != null) {
            forwardCredentials(request, credentials);
        }

        String url = orchestratorUrl + "/api/agent-tools/execute";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
            headers.set("X-User-ID", tenantId);
        }
        // Phase 3 of MIGRATION_ORG_ID_NOT_NULL.md - propagate org context from
        // the credentials map (__orgId__ / __orgRole__ convention used by
        // SubAgentExecutionHandler, AgentContextBuilder, AgentNode.applyOrgContext).
        // Without this header, downstream INSERTs in orchestrator/interface/etc.
        // stamp organization_id = NULL, which fails Phase 6 NOT NULL.
        applyOrgHeaders(headers, credentials);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = restTemplate.exchange(
            url, HttpMethod.POST, entity, Map.class);

        long duration = System.currentTimeMillis() - startTime;

        if (response.getBody() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return parseExecutionResponse(toolCall, body, duration);
        } else {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Unexpected response: " + response.getStatusCode())
                .durationMs(duration)
                .build();
        }
    }

    /**
     * Execute a conversation-specific tool via conversation-service's callback URL.
     * Used when __toolCallbackUrl__ is present in credentials and the tool is in CONVERSATION_LOCAL_TOOLS.
     */
    private ToolResult executeViaCallbackUrl(ToolCall toolCall, String tenantId,
                                              Map<String, Object> credentials,
                                              String callbackUrl, long startTime) {
        log.info("Executing conversation tool '{}' via callback URL: {}", toolCall.toolName(), callbackUrl);

        Map<String, Object> request = new HashMap<>();
        request.put("tool", toolCall.toolName());
        request.put("toolCallId", toolCall.id());
        request.put("parameters", toolCall.arguments());
        if (tenantId != null) {
            request.put("tenantId", tenantId);
        }
        // Forward conversation-specific credentials
        if (credentials != null) {
            if (credentials.get("conversationId") != null) {
                request.put("conversationId", credentials.get("conversationId"));
            }
            if (credentials.get("turnId") != null) {
                request.put("turnId", credentials.get("turnId"));
            }
            // Forward credential keys needed by conversation tools
            forwardCredentials(request, credentials);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-Tenant-Id", tenantId);
            headers.set("X-User-ID", tenantId);
        }
        // Phase 3 of MIGRATION_ORG_ID_NOT_NULL.md - propagate org context from
        // the credentials map (__orgId__ / __orgRole__ convention used by
        // SubAgentExecutionHandler, AgentContextBuilder, AgentNode.applyOrgContext).
        // Without this header, downstream INSERTs in orchestrator/interface/etc.
        // stamp organization_id = NULL, which fails Phase 6 NOT NULL.
        applyOrgHeaders(headers, credentials);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = restTemplate.exchange(
            callbackUrl, HttpMethod.POST, entity, Map.class);

        long duration = System.currentTimeMillis() - startTime;

        if (response.getBody() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return parseExecutionResponse(toolCall, body, duration);
        } else {
            return ToolResult.builder()
                .toolCall(toolCall)
                .success(false)
                .error("Unexpected response from conversation-service: " + response.getStatusCode())
                .durationMs(duration)
                .build();
        }
    }

    /**
     * Forward relevant credential keys from the agent context to the tool execution request.
     * Follows the same pattern as ConversationToolExecutionService.
     */
    private void forwardCredentials(Map<String, Object> request, Map<String, Object> credentials) {
        // Forward conversation-scoped keys (mirrors ConversationToolExecutionService.executeCoreTools)
        if (credentials.get("conversationId") != null) {
            request.put("conversationId", credentials.get("conversationId"));
        }
        if (credentials.get("turnId") != null) {
            request.put("messageId", credentials.get("turnId"));
            request.put("turnId", credentials.get("turnId"));
        }

        copyCredential(request, credentials, "agentId", "__agentId__", "agentId");
        copyCredential(request, credentials, "allowedToolIds", "__allowedToolIds__", "allowedToolIds");
        copyCredential(request, credentials, "allowedWorkflowIds", "__allowedWorkflowIds__", "allowedWorkflowIds");
        copyCredential(request, credentials, "allowedApplicationIds", "__allowedApplicationIds__", "allowedApplicationIds");
        copyCredential(request, credentials, "allowedTableIds", "__allowedTableIds__", "allowedTableIds");
        copyCredential(request, credentials, "allowedInterfaceIds", "__allowedInterfaceIds__", "allowedInterfaceIds");
        copyCredential(request, credentials, "allowedAgentIds", "__allowedAgentIds__", "allowedAgentIds");
        copyCredential(request, credentials, "allowedFileIds", "__allowedFileIds__", "allowedFileIds");
        copyCredential(request, credentials, "approvedServices", "__approvedServices__", "approvedServices");
        copyCredential(request, credentials, "orgId", "__orgId__", "orgId");
        copyCredential(request, credentials, "orgRole", "__orgRole__", "orgRole");
        copyCredential(request, credentials, "viewingWorkflowId", "__viewingWorkflowId__", "viewingWorkflowId");
        copyCredential(request, credentials, "viewingWorkflowName", "__viewingWorkflowName__", "viewingWorkflowName");
        copyCredential(request, credentials, "streamId", "__streamId__", "streamId");
        copyCredential(request, credentials, "workflowRunId", "__workflowRunId__", "workflowRunId");
        // Hosting workflow node - lets orchestrator tools (browser-agent live
        // view) route run-page events to the right builder node.
        copyCredential(request, credentials, "workflowNodeId", "__workflowNodeId__", "workflowNodeId");

        // Forward access modes (read/write per resource) - strip __ prefix/suffix
        copyCredential(request, credentials, "tableAccessMode", "__tableAccessMode__", "tableAccessMode");
        copyCredential(request, credentials, "workflowAccessMode", "__workflowAccessMode__", "workflowAccessMode");
        copyCredential(request, credentials, "interfaceAccessMode", "__interfaceAccessMode__", "interfaceAccessMode");
        copyCredential(request, credentials, "agentAccessMode", "__agentAccessMode__", "agentAccessMode");
        copyCredential(request, credentials, "applicationAccessMode", "__applicationAccessMode__", "applicationAccessMode");
        copyCredential(request, credentials, "skillAccessMode", "__skillAccessMode__", "skillAccessMode");
        copyCredential(request, credentials, "fileAccessMode", "__fileAccessMode__", "fileAccessMode");
    }

    private void copyCredential(Map<String, Object> request, Map<String, Object> credentials,
                                String plainCredentialKey, String namespacedCredentialKey,
                                String requestKey) {
        Object value = credentials.get(plainCredentialKey);
        if (value == null) {
            value = credentials.get(namespacedCredentialKey);
        }
        if (value != null) {
            request.put(requestKey, value);
        }
    }

    private void applyOrgHeaders(HttpHeaders headers, Map<String, Object> credentials) {
        String orgId = credentialString(credentials, "orgId", "__orgId__");
        String orgRole = credentialString(credentials, "orgRole", "__orgRole__");
        if (orgId != null) {
            headers.set("X-Organization-ID", orgId);
        }
        if (orgRole != null) {
            headers.set("X-Organization-Role", orgRole);
        }
    }

    private String credentialString(Map<String, Object> credentials, String plainCredentialKey,
                                    String namespacedCredentialKey) {
        if (credentials == null) {
            return null;
        }
        Object value = credentials.get(plainCredentialKey);
        if (value == null) {
            value = credentials.get(namespacedCredentialKey);
        }
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ToolResult parseExecutionResponse(ToolCall toolCall, Map<String, Object> response,
                                               long duration) {
        Boolean success = (Boolean) response.get("success");
        String error = (String) response.get("error");

        Object resultObj = response.get("result");
        if (resultObj instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) resultObj;
            if (success == null || success) {
                Boolean nestedSuccess = (Boolean) resultMap.get("success");
                if (nestedSuccess != null && !nestedSuccess) {
                    success = false;
                    if (error == null) {
                        error = (String) resultMap.get("error");
                    }
                }
            }
        }

        if (success == null) {
            success = error == null && !response.containsKey("error");
        }

        String content = null;
        if (success) {
            Object result = response.get("result");
            if (result == null) result = response.get("data");
            if (result == null) result = response.get("output");

            if (result != null) {
                try {
                    content = objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    content = result.toString();
                }
            } else {
                content = "{}";
            }
        } else {
            if (error == null) error = (String) response.get("message");
            if (error == null) error = "Unknown error";
        }

        // Pass through metadata from orchestrator
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "agent_service_remote");
        Object responseMetadata = response.get("metadata");
        if (responseMetadata instanceof Map) {
            metadata.putAll((Map<String, Object>) responseMetadata);
        }

        return ToolResult.builder()
            .toolCall(toolCall)
            .success(success)
            .content(content)
            .error(error)
            .durationMs(duration)
            .metadata(metadata)
            .build();
    }

    // ==================== Local Provider Execution ====================

    /**
     * Execute a tool locally via a ToolsProvider (no HTTP round-trip to orchestrator).
     * Adapts from ToolExecutionService interface to ToolsProvider interface.
     */
    private ToolResult executeLocalProvider(com.apimarketplace.agent.tools.ToolsProvider provider,
                                             ToolCall toolCall, String tenantId,
                                             Map<String, Object> credentials, long startTime) {
        try {
            Map<String, Object> params = toolCall.arguments() != null ? toolCall.arguments() : Map.of();

            var context = new com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext(
                tenantId, credentials != null ? credentials : Map.of(),
                Map.of(), Set.of(), null, null,
                credentialString(credentials, "orgId", "__orgId__"),
                credentialString(credentials, "orgRole", "__orgRole__"));

            var result = provider.execute(toolCall.toolName(), params, context);

            long duration = System.currentTimeMillis() - startTime;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "agent_service_local");
            if (result.metadata() != null) metadata.putAll(result.metadata());

            if (result.success()) {
                String content;
                try {
                    content = objectMapper.writeValueAsString(result.data());
                } catch (Exception e) {
                    content = result.data() != null ? result.data().toString() : "";
                }
                return ToolResult.builder()
                    .toolCall(toolCall).success(true).content(content)
                    .durationMs(duration).metadata(metadata).build();
            } else {
                return ToolResult.builder()
                    .toolCall(toolCall).success(false).error(result.error())
                    .durationMs(duration).metadata(metadata).build();
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Local provider execution error for {}: {}", toolCall.toolName(), e.getMessage(), e);
            return ToolResult.builder()
                .toolCall(toolCall).success(false)
                .error("Local execution error: " + e.getMessage())
                .durationMs(duration).build();
        }
    }
}
