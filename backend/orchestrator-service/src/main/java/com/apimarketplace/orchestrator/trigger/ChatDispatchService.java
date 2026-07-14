package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ChatDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(ChatDispatchService.class);

    private static final String SESSION_KEY_PREFIX = "chat:endpoint:session:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final TriggerClient triggerClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final ReusableTriggerService triggerService;
    private final ProductionRunResolver productionRunResolver;
    private final CreditConsumptionClient creditClient;
    private final ShareInvocationLimiter shareInvocationLimiter;
    private final AgentDefaultsConfig agentDefaults;
    private final AgentClient agentClient;
    private final RunContextService runContextService;
    private final String conversationServiceUrl;

    public ChatDispatchService(
            TriggerClient triggerClient,
            RedisTemplate<String, Object> redisTemplate,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            WorkflowRepository workflowRepository,
            WorkflowRunRepository runRepository,
            ReusableTriggerService triggerService,
            ProductionRunResolver productionRunResolver,
            CreditConsumptionClient creditClient,
            ShareInvocationLimiter shareInvocationLimiter,
            AgentDefaultsConfig agentDefaults,
            AgentClient agentClient,
            RunContextService runContextService,
            // Standard `services.conversation-url` (helm-injected SERVICES_CONVERSATION_URL), with the
            // legacy `orchestrator.conversation.base-url` fallback for docker-compose / systemd. See
            // ConversationClientConfig for why the non-standard name broke on the k3s migration.
            @Value("${services.conversation-url:${orchestrator.conversation.base-url:http://localhost:8087}}") String conversationServiceUrl) {
        this.triggerClient = triggerClient;
        this.redisTemplate = redisTemplate;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.productionRunResolver = productionRunResolver;
        this.creditClient = creditClient;
        this.shareInvocationLimiter = shareInvocationLimiter;
        this.agentDefaults = agentDefaults;
        this.agentClient = agentClient;
        this.runContextService = runContextService;
        this.conversationServiceUrl = conversationServiceUrl;
    }

    /** The resolved conversation-service base URL (visible for wiring tests). */
    public String getConversationServiceUrl() {
        return conversationServiceUrl;
    }

    public record ChatSession(
            String sessionId,
            String conversationId,
            UUID chatEndpointId,
            String tenantId,
            String ipAddress,
            Instant createdAt
    ) {}

    public record SessionResponse(
            String sessionId,
            String conversationId,
            String welcomeMessage,
            String name,
            Boolean memoryEnabled
    ) {}

    /**
     * Create or resume a chat session.
     */
    public SessionResponse createOrResumeSession(String token, String clientSessionId, String ipAddress) {
        StandaloneChatEndpointDto endpoint = triggerClient.findChatEndpointByToken(token);
        if (endpoint == null) {
            throw new IllegalArgumentException("Chat endpoint not found");
        }

        if (!Boolean.TRUE.equals(endpoint.getIsActive())) {
            throw new IllegalStateException("Chat endpoint is not active");
        }

        // Try to resume existing session
        if (clientSessionId != null && !clientSessionId.isBlank()) {
            ChatSession existing = getSession(clientSessionId);
            if (existing != null && existing.chatEndpointId().equals(endpoint.getId())) {
                refreshSession(clientSessionId);
                triggerClient.logChatAccess(endpoint.getId(), clientSessionId,
                        existing.conversationId(), "session_resumed", ipAddress);

                return new SessionResponse(
                        existing.sessionId(),
                        existing.conversationId(),
                        endpoint.getWelcomeMessage(),
                        endpoint.getName(),
                        endpoint.getMemoryEnabled()
                );
            }
        }

        // Create new session + conversation
        String conversationId = createConversation(endpoint);
        if (conversationId == null) {
            throw new RuntimeException("Failed to create conversation for chat session");
        }

        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(
                sessionId, conversationId, endpoint.getId(),
                endpoint.getTenantId(), ipAddress, Instant.now()
        );

        // Store in Redis
        String key = SESSION_KEY_PREFIX + sessionId;
        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("sessionId", session.sessionId());
        sessionData.put("conversationId", session.conversationId());
        sessionData.put("chatEndpointId", session.chatEndpointId().toString());
        sessionData.put("tenantId", session.tenantId());
        sessionData.put("ipAddress", session.ipAddress() != null ? session.ipAddress() : "");
        sessionData.put("createdAt", session.createdAt().toString());

        redisTemplate.opsForHash().putAll(key, sessionData);
        redisTemplate.expire(key, SESSION_TTL);

        triggerClient.logChatAccess(endpoint.getId(), sessionId, conversationId,
                "session_created", ipAddress);

        logger.info("Created chat session {} for endpoint '{}' with conversation {}",
                sessionId, endpoint.getName(), conversationId);

        return new SessionResponse(
                sessionId, conversationId,
                endpoint.getWelcomeMessage(),
                endpoint.getName(),
                endpoint.getMemoryEnabled()
        );
    }

    /**
     * Send a message through a chat endpoint session.
     * Dispatches to the linked workflow and returns the Response node output (if any).
     */
    public Map<String, Object> sendMessage(String token, String sessionId, String message) {
        StandaloneChatEndpointDto endpoint = triggerClient.findChatEndpointByToken(token);
        if (endpoint == null) {
            throw new IllegalArgumentException("Chat endpoint not found");
        }

        ChatSession session = getSession(sessionId);
        if (session == null || !session.chatEndpointId().equals(endpoint.getId())) {
            throw new IllegalArgumentException("Invalid or expired session");
        }

        // Anti credit-drain: an anonymous public chat link spends the OWNER's LLM credits.
        // Cap invocations per-link and per-owner (workspace) per day. Placed AFTER session
        // validation so an attacker cannot burn the counter (and lock out real users for 24h)
        // with invalid-session requests.
        if (!shareInvocationLimiter.tryAcquire(token, endpoint.getOrganizationId())) {
            throw new ShareInvocationLimitExceededException(
                    "Daily message limit reached for this chat link. Please try again later.");
        }

        refreshSession(sessionId);

        triggerClient.logChatAccess(endpoint.getId(), sessionId,
                session.conversationId(), "message_sent", session.ipAddress());

        // Persist user message to the conversation so history/context is complete
        persistMessage(endpoint.getTenantId(), session.conversationId(), "user", message);

        // Dispatch to workflow and collect response
        Map<String, Object> result = dispatchToWorkflow(endpoint, message);

        // Persist assistant reply (if any) so history reflects the exchange
        Object content = result.get("content");
        if (content instanceof String reply && !reply.isBlank()) {
            persistMessage(endpoint.getTenantId(), session.conversationId(), "assistant", reply);
        }

        return result;
    }

    private void persistMessage(String tenantId, String conversationId, String role, String content) {
        if (conversationId == null || content == null) return;
        try {
            String url = conversationServiceUrl + "/api/conversations/" + conversationId + "/messages";
            Map<String, Object> body = new HashMap<>();
            body.put("role", role);
            body.put("content", content);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-ID", tenantId);
            OrgContextHeaderForwarder.forward(headers);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, request, Map.class);
        } catch (Exception e) {
            logger.warn("Failed to persist {} message to conversation {}: {}",
                    role, conversationId, e.getMessage());
        }
    }

    /**
     * Get conversation history for a session.
     */
    public ResponseEntity<String> getHistory(String token, String sessionId) {
        StandaloneChatEndpointDto endpoint = triggerClient.findChatEndpointByToken(token);
        if (endpoint == null) {
            throw new IllegalArgumentException("Chat endpoint not found");
        }

        ChatSession session = getSession(sessionId);
        if (session == null || !session.chatEndpointId().equals(endpoint.getId())) {
            throw new IllegalArgumentException("Invalid or expired session");
        }

        refreshSession(sessionId);

        String url = conversationServiceUrl + "/api/conversations/" + session.conversationId() + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", endpoint.getTenantId());
        headers.setContentType(MediaType.APPLICATION_JSON);
        OrgContextHeaderForwarder.forward(headers);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            return restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        } catch (Exception e) {
            logger.error("Error fetching history for chat endpoint '{}': {}", endpoint.getName(), e.getMessage());
            return ResponseEntity.status(500).body("{\"error\": \"Failed to fetch history\"}");
        }
    }

    /**
     * Get public config (no secrets).
     */
    public Map<String, Object> getPublicConfig(String token) {
        StandaloneChatEndpointDto endpoint = triggerClient.findChatEndpointByToken(token);
        if (endpoint == null) {
            throw new IllegalArgumentException("Chat endpoint not found");
        }

        Map<String, Object> config = new HashMap<>();
        config.put("name", endpoint.getName());
        config.put("description", endpoint.getDescription());
        config.put("welcomeMessage", endpoint.getWelcomeMessage());
        config.put("memoryEnabled", endpoint.getMemoryEnabled());
        config.put("isActive", endpoint.getIsActive());
        return config;
    }

    private ChatSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;

        String key = SESSION_KEY_PREFIX + sessionId;
        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
        if (data == null || data.isEmpty()) return null;

        return new ChatSession(
                (String) data.get("sessionId"),
                (String) data.get("conversationId"),
                UUID.fromString((String) data.get("chatEndpointId")),
                (String) data.get("tenantId"),
                (String) data.get("ipAddress"),
                Instant.parse((String) data.get("createdAt"))
        );
    }

    private void refreshSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.expire(key, SESSION_TTL);
    }

    private String createConversation(StandaloneChatEndpointDto endpoint) {
        String url = conversationServiceUrl + "/api/conversations";

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("title", "Chat: " + endpoint.getName());
            body.put("model", endpoint.getModel() != null ? endpoint.getModel() : resolveDefaultModel());
            body.put("provider", endpoint.getProvider() != null ? endpoint.getProvider() : resolveDefaultProvider());
            if (endpoint.getWorkflowId() != null) {
                body.put("workflowId", endpoint.getWorkflowId().toString());
            }
            body.put("active", true);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-ID", endpoint.getTenantId());
            OrgContextHeaderForwarder.forward(headers);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object id = response.getBody().get("id");
                if (id != null) {
                    logger.info("Created conversation {} for chat endpoint '{}'", id, endpoint.getName());
                    return id.toString();
                }
            }

            logger.error("Failed to create conversation: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            logger.error("Error creating conversation for chat endpoint: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Dispatch message to the workflow and return the Response node output (if any).
     */
    private Map<String, Object> dispatchToWorkflow(StandaloneChatEndpointDto endpoint, String message) {
        if (endpoint.getWorkflowId() == null) {
            logger.debug("No workflow linked to chat endpoint '{}'", endpoint.getName());
            return Map.of("status", "no_workflow");
        }

        WorkflowEntity workflow = workflowRepository.findById(endpoint.getWorkflowId()).orElse(null);
        if (workflow == null || workflow.getPlan() == null) {
            logger.debug("No workflow or plan found for chat endpoint '{}'", endpoint.getName());
            return Map.of("status", "no_workflow");
        }

        // Centralized: production chat fires ONLY on the workflow's pinned version.
        ProductionRunResolver.Resolution resolution =
            productionRunResolver.resolve(workflow.getId(), com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
        if (!resolution.isFound()) {
            if (resolution.isNotPinned()) {
                logger.warn("Chat endpoint '{}' refused: workflow {} has no pinned version. " +
                    "Pin a production version to enable chat triggers.",
                    endpoint.getName(), workflow.getId());
                return Map.of("status", "not_pinned");
            }
            return Map.of("status", "no_active_run");
        }
        WorkflowRunEntity run = resolution.run().get();

        // PR22 R2 - workspace-scope guard. Mirror WebhookDispatchService.dispatchStandalone:
        // chat endpoint is tagged for a workspace; pinned run is tagged for a workspace.
        // A chat endpoint MUST NOT fire a workflow in a different workspace.
        String endpointOrg = endpoint.getOrganizationId();
        String runOrg = run.getOrgId();
        if (!com.apimarketplace.common.scope.ScopeGuard.crossResourceMatches(endpointOrg, runOrg)) {
            logger.info("Skipping chat dispatch for workflow {} - workspace mismatch "
                + "(endpoint org={}, run org={})", workflow.getId(), endpointOrg, runOrg);
            return Map.of("status", "workspace_mismatch");
        }

        // Skip terminal runs
        if (run.getStatus().isTerminal()) {
            return Map.of("status", "run_terminated");
        }

        // Credit check
        if (!creditClient.checkCredits(run.getTenantId())) {
            logger.warn("Insufficient credits for tenant {}, skipping chat dispatch", run.getTenantId());
            return Map.of("status", "insufficient_credits");
        }

        // Use stored triggerId directly (same as webhook pattern)
        String matchedTriggerId = endpoint.getTriggerId();
        if (matchedTriggerId == null) {
            logger.warn("No triggerId on chat endpoint '{}' - link the endpoint to a workflow with a chat trigger",
                    endpoint.getName());
            return Map.of("status", "no_chat_trigger");
        }

        // Find Response node keys from the run's plan
        Map<String, Object> runPlanMap = run.getPlan() != null ? run.getPlan() : workflow.getPlan();
        WorkflowPlan plan = WorkflowPlan.fromMap(runPlanMap);
        List<String> responseNodeKeys = new ArrayList<>();
        if (plan.getCores() != null) {
            for (Core core : plan.getCores()) {
                if (core.isResponse()) {
                    responseNodeKeys.add(LabelNormalizer.coreKey(core.label()));
                }
            }
        }

        // Execute the trigger (synchronous in AUTO mode).
        // The chat payload is built from explicit puts of known keys, but
        // apply the same defense-in-depth strip used at every other external
        // entry - keeps the contract uniform: only TriggerController sets the
        // PLAN_FROM_PAYLOAD_MARKER, and only after a successful updateRunPlan.
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        payload.put("source", "chat_endpoint");
        payload.put("chatEndpointId", endpoint.getId().toString());
        payload.put("chatEndpointName", endpoint.getName());
        payload = ReusableTriggerService.sanitizePlanMarker(payload);

        TriggerExecutionResult result = triggerService.executeTrigger(run, matchedTriggerId, TriggerType.CHAT, payload);

        if (!result.success()) {
            logger.warn("Workflow trigger execution failed for endpoint '{}': {}", endpoint.getName(), result.message());
            return Map.of("status", "execution_failed", "error", result.message());
        }

        logger.info("Dispatched chat message to workflow run {} epoch {}", result.runId(), result.epoch());

        // Extract Response node output
        // After cycle reset, result.epoch() is the NEXT epoch; step data is in the executed epoch
        if (!responseNodeKeys.isEmpty()) {
            for (String responseKey : responseNodeKeys) {
                // Try current epoch first, then executed epoch (epoch - 1 after reset)
                Optional<Map<String, Object>> output = runContextService.getStepOutput(
                        result.runId(), responseKey, result.epoch(), run.getTenantId());
                if (output.isEmpty() && result.epoch() > 0) {
                    output = runContextService.getStepOutput(
                            result.runId(), responseKey, result.epoch() - 1, run.getTenantId());
                }

                if (output.isPresent()) {
                    Map<String, Object> data = output.get();
                    // Storage wraps actual output inside "output" key
                    String responseMessage = extractResponseMessage(data);
                    if (responseMessage != null) {
                        logger.info("Response from workflow: {}", responseMessage);
                        return Map.of("status", "ok", "content", responseMessage);
                    }
                }
            }
        }

        // Workflow executed but no Response node output found
        return Map.of("status", "ok");
    }

    @SuppressWarnings("unchecked")
    private String extractResponseMessage(Map<String, Object> data) {
        // Try unwrapped "message" first (in case storage is already unwrapped)
        Object direct = data.get("message");
        if (direct != null && !(direct instanceof Map)) {
            return direct.toString();
        }
        // Storage wraps actual output inside "output" key
        Object outputWrapper = data.get("output");
        if (outputWrapper instanceof Map) {
            Object msg = ((Map<String, Object>) outputWrapper).get("message");
            if (msg != null) return msg.toString();
        }
        return null;
    }

    private String resolveDefaultModel() {
        try {
            Map<String, Object> info = agentClient.getModelsInfo();
            String model = (String) info.get("defaultModel");
            if (model != null) return model;
        } catch (Exception e) {
            logger.debug("Failed to fetch models info: {}", e.getMessage());
        }
        return "unknown";
    }

    private String resolveDefaultProvider() {
        try {
            Map<String, Object> info = agentClient.getModelsInfo();
            String provider = (String) info.get("defaultProvider");
            if (provider != null) return provider;
        } catch (Exception e) {
            logger.debug("Failed to fetch models info: {}", e.getMessage());
        }
        return "unknown";
    }
}
