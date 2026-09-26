package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.prompt.ConversationToolDefinitions;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.agent.tools.authz.AuthorizationSubject;
import com.apimarketplace.agent.tools.authz.AuthorizationAsk;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationGuard;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationPolicy;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationScope;
import com.apimarketplace.agent.tools.remote.ToolServiceTopology;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
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
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    /**
     * {@code @Lazy} for the same reason as the skill provider directly above: both
     * reach back into services this one sits on the dependency path of, so eager
     * injection closes a cycle at context refresh. Stated here because an
     * unexplained {@code @Lazy} reads as cargo, and the next reader would either
     * copy it without knowing why or remove it and break the boot.
     */
    @Lazy
    @Autowired(required = false)
    private com.apimarketplace.agent.tools.memory.MemoryToolsProvider memoryToolsProvider;

    /**
     * The question-to-the-user tool. Local for the same reason the approval gate is: the
     * park it performs lives in this process, and it needs the call's own id and the raw
     * credentials, which only this service has at hand.
     */
    @Lazy
    @Autowired(required = false)
    private com.apimarketplace.agent.tools.askuser.AskUserToolsProvider askUserToolsProvider;

    /** Test seam: wire the question tool without a Spring context. */
    void configureAskUserForTest(com.apimarketplace.agent.tools.askuser.AskUserToolsProvider provider) {
        this.askUserToolsProvider = provider;
    }

    /**
     * Approval gate collaborators. Optional on purpose: when any of them is absent the
     * call behaves exactly as it did before the gate existed (card painted by the result
     * consumer, turn ends, user resumes). That is also what keeps the many direct-{@code new}
     * unit-test constructions of this service valid without wiring a Redis.
     */
    @Autowired(required = false)
    private ToolApprovalGate approvalGate;

    @Autowired(required = false)
    private ApprovalCardPublisher approvalCardPublisher;

    @Autowired(required = false)
    private ApprovalCardExtractor approvalCardExtractor;

    /**
     * Reaches the person through a chat they read, for the runs where the in-app
     * card has nobody in front of it. Optional like the rest: without it an
     * unattended run behaves exactly as before, ending its turn with the request
     * waiting in the app.
     */
    @Autowired(required = false)
    private ChannelAuthorizationClient channelAuthorizationClient;

    /**
     * Rings the bell when an armed agent has nobody to ask.
     *
     * <p>Optional like the rest, and the degradation is honest rather than silent: without
     * it the agent still stops and still says why in its own turn, it is only the person who
     * is not told. Wired in every real deployment by {@code NotificationClientConfig}.
     */
    @Autowired(required = false)
    private NotificationClient notificationClient;

    /** Names the agent on its bell row. Optional: without it the row says "An agent". */
    @Autowired(required = false)
    private com.apimarketplace.agent.repository.AgentRepository agentRepository;

    /** Test seam: wire the bell without a Spring context. */
    void configureNotificationClientForTest(NotificationClient client) {
        this.notificationClient = client;
    }

    /** Test seam: wire the gate collaborators without a Spring context. */
    void configureApprovalGateForTest(ToolApprovalGate gate, ApprovalCardPublisher publisher,
                                      ApprovalCardExtractor extractor) {
        this.approvalGate = gate;
        this.approvalCardPublisher = publisher;
        this.approvalCardExtractor = extractor;
    }

    /** Test seam: wire the out-of-app delivery without a Spring context. */
    void configureChannelAuthorizationForTest(ChannelAuthorizationClient client) {
        this.channelAuthorizationClient = client;
    }

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
        // general conversation - INCLUDING one backed by a CLI through the bridge, which
        // carries a conversation and a live stream like any other. Exempt: workflow run,
        // task, sub-agent, agent-backed chat, headless.
        // Runs BEFORE any local interception so agent(action='execute') is gated too.
        ToolResult authResult = checkToolAuthorization(toolCall, credentials, startTime);
        if (authResult != null) {
            // Park until the user answers. A non-null answer here means "not approved"
            // and IS the pre-gate result, so refusing, timing out or having no gate at all
            // all land on the exact behaviour that shipped before this gate existed.
            ToolResult notApproved = parkForAuthorization(toolCall, tenantId, credentials, authResult, startTime);
            if (notApproved != null) {
                return notApproved;
            }
            log.info("User authorized {} while the call was parked - executing it for real", toolName);
        }

        ToolResult result = dispatch(toolCall, toolDefinition, tenantId, credentials, startTime);

        // === Credential gate ===
        // The catalog pre-flight answers "this needs a connection" as a successful result.
        // Park on it too, and re-dispatch once if the user connects the service, so the
        // agent receives the REAL API response instead of a "please connect" placeholder.
        return parkForCredential(toolCall, toolDefinition, tenantId, credentials, result, startTime);
    }

    /**
     * The actual routing/execution, unchanged - split out so the gates can wrap it.
     * Package-private so a test can stub it and exercise a gate's re-dispatch decision
     * without standing up the downstream HTTP services.
     */
    ToolResult dispatch(ToolCall toolCall, ToolDefinition toolDefinition,
                        String tenantId, Map<String, Object> credentials, long startTime) {
        String toolName = toolCall.toolName();
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

            // Memory tool: same deal, and the rows live in this service's own schema.
            if ("memory".equals(toolName) && memoryToolsProvider != null) {
                Object action = toolCall.arguments() != null ? toolCall.arguments().get("action") : null;
                log.info("Intercepting memory(action='{}') locally via MemoryToolsProvider", action);
                return executeLocalProvider(memoryToolsProvider, toolCall, tenantId, credentials, startTime);
            }

            // ask_user: parks on the approval gate right here. The provider needs the call's
            // own id (the gate key) and when the call began (the park ceilings), neither of
            // which the provider contract carries, so both ride in on the credentials.
            if (com.apimarketplace.agent.tools.askuser.AskUserToolsProvider.TOOL_NAME.equals(toolName)
                    && askUserToolsProvider != null) {
                Map<String, Object> withCall = new HashMap<>(credentials != null ? credentials : Map.of());
                withCall.put(com.apimarketplace.agent.tools.askuser.AskUserToolsProvider.KEY_TOOL_CALL_ID, toolCall.id());
                withCall.put(com.apimarketplace.agent.tools.askuser.AskUserToolsProvider.KEY_CALL_STARTED_EPOCH_MS, startTime);
                log.info("Intercepting ask_user locally via AskUserToolsProvider (call {})", toolCall.id());
                return executeLocalProvider(askUserToolsProvider, toolCall, tenantId, withCall, startTime);
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
     * Hold a sensitive call until the user answers its authorization card.
     *
     * <p>The card is painted HERE rather than by the streaming callback: parking happens
     * inside the tool call, so the result the callback would react to does not exist yet.
     * Whoever paints the card also owns suppressing the callback's copy, which is what
     * {@code META_CARD_EMITTED} does.
     *
     * @return {@code null} when the user approved and the caller must now execute the tool
     *         for real; otherwise the result to hand back (refused, expired, or no gate
     *         available - all identical to the pre-gate behaviour).
     */
    ToolResult parkForAuthorization(ToolCall toolCall, String tenantId, Map<String, Object> credentials,
                                    ToolResult gateResult, long callStartedEpochMs) {
        String gateKey = toolCall.id();
        String streamId = streamIdOf(credentials);
        if (approvalGate == null || approvalCardPublisher == null || !approvalGate.isEnabled()
                || gateKey == null || gateKey.isBlank() || streamId == null) {
            // No gate: leave the result untouched so the callback paints the card as before.
            return gateResult;
        }
        if (isUserPerformedRule(gateResult)) {
            return gateResult;
        }

        String conversationId = conversationIdOf(credentials);
        // Advertise the park BEFORE the card exists. A blocking card the answer side cannot
        // match to a park is a dead end: the user clicks, nothing is released, and no turn
        // restarts either. Failing to advertise means we simply do not claim to be holding.
        ToolApprovalGate.ParkRequest parkRequest = ParkRequests.of(credentials, gateKey, callStartedEpochMs);
        if (!approvalGate.beginPark(parkRequest)) {
            return gateResult;
        }

        // Nobody is watching this run, so the card alone would wait for someone to
        // open the app. Ask the workspace's chat as well, BEFORE painting the card:
        // when the same question is already waiting there from an earlier run, the
        // right move is to paint nothing and say so, rather than add a second card
        // and a second message the person cannot tell apart.
        ChannelAuthorizationClient.Delivery delivery = null;
        if (channelAuthorizationClient != null
                && ToolAuthorizationScope.isUnattended(credentials)) {
            delivery = channelAuthorizationClient.request(
                    tenantId, orgIdOf(credentials), conversationId, gateKey,
                    ruleOf(gateResult), agentIdOf(credentials), summaryOf(gateResult),
                    askFingerprint(toolCall));
            if (delivery.isAlreadyPending()) {
                approvalGate.abandonPark(conversationId, gateKey);
                return alreadyAskedResult(gateResult, delivery);
            }
            if (!delivery.isWaitingOnSomeone()) {
                // Nobody was reached and nobody is watching, so the park would hold this
                // call for its full budget against an answer that cannot arrive, and the
                // agent would then read "your request ran out of time" about a request
                // that was never sent. Stop here instead, tell the agent what is actually
                // true, and put it in the bell, which is the only surface a person looks
                // at for a run they were not present for.
                //
                // The card is deliberately still painted by the result consumer (no
                // authorizationAlreadyPending flag on this path): it costs nothing and it
                // is there if somebody does open the conversation.
                approvalGate.abandonPark(conversationId, gateKey);
                notifyNobodyToAsk(tenantId, credentials, gateResult);
                return unreachableResult(gateResult);
            }
        }

        String buffered = approvalCardPublisher.publishToolAuthorization(
                streamId, conversationId, gateResult.metadata(), true, gateKey);
        if (buffered == null) {
            // Publishing the card FAILED outright (serialisation error, Redis refused the
            // send). Not a delivery receipt: a send with no subscriber still returns
            // normally, so this catches the card that was never sent, not the one nobody
            // heard. Parking on it anyway would hold the call for minutes behind a spinner
            // with nothing to click, and the "already emitted" flag would then stop the
            // result consumer painting the fallback card too - dead air, and no card at all.
            approvalGate.abandonPark(conversationId, gateKey);
            return gateResult;
        }

        ToolApprovalGate.Decision decision = approvalGate.awaitDecision(parkRequest);
        dropSettledCard(streamId, buffered, decision);
        if (decision == ToolApprovalGate.Decision.APPROVED) {
            return null;
        }
        // The card is already on screen - stop the callback from painting a second one.
        return withCardEmitted(waitingElsewhere(gateResult, decision, delivery), decision);
    }

    /**
     * Tell the agent it may not act and has nobody to ask.
     *
     * <p>Distinct from a refusal on purpose. A refusal is an answer, and an agent that reads
     * one has learnt something about what it may do. This is the absence of any way to put
     * the question, which calls for a different next move: state the block, name what would
     * lift it, and stop rather than retry a call whose gate cannot change within the run.
     */
    private static ToolResult unreachableResult(ToolResult gateResult) {
        return withContent(gateResult, structuredGateContent(
                "authorization_unreachable",
                "The action has NOT run. This agent asks permission for sensitive actions, this run "
                        + "has nobody watching it, and this workspace has no chat connected to ask on, "
                        + "so the question could not be put to anyone. Do NOT retry the call: nothing "
                        + "within this run can change that. Finish your turn by saying, in one sentence, "
                        + "what you were about to do and that you need permission for it. Someone can "
                        + "connect a chat with channel(action='discover') then channel(action='connect'), "
                        + "after which a later run asks there instead of stopping."));
    }

    /**
     * Put the block in the bell, because the conversation this run writes to is not read.
     *
     * <p>The whole point of an unattended run is that nobody is in front of it. An agent that
     * stops there has stopped in silence: before this, the only trace was a card painted into
     * a stream with no subscriber and a tool result in a transcript nobody opens. The bell is
     * where a person looks for what happened while they were away.
     *
     * <p>Never throws and never fails the call. The block is already the bad outcome; failing
     * the tool because the bell could not be rung would replace one problem with two.
     */
    private void notifyNobodyToAsk(String tenantId, Map<String, Object> credentials, ToolResult gateResult) {
        if (notificationClient == null || tenantId == null || tenantId.isBlank()) {
            return;
        }
        UUID subjectId = agentUuidOf(credentials);
        if (subjectId == null) {
            // The endpoint requires one, and there is nothing honest to invent: a run with no
            // agent id cannot be pointed at from a bell row. Logged at a level somebody reads,
            // because it means a real block went unreported.
            log.warn("An armed run was blocked with nobody to ask and no agent id to report it "
                    + "against, so no notification was raised");
            return;
        }
        String organizationId = orgIdOf(credentials);
        if (organizationId == null || organizationId.isBlank()) {
            // The endpoint refuses a notification with no workspace ("organizationId required
            // after V261"), and it does so before its own validate(), with a 400 the client
            // swallows. Sending it anyway is the silence this method exists to end.
            log.warn("An armed run of agent {} was blocked with nobody to ask and no workspace to "
                    + "report it in, so no notification was raised", subjectId);
            return;
        }
        try {
            NotificationEmitRequest req = new NotificationEmitRequest();
            req.setTenantId(tenantId);
            req.setOrganizationId(organizationId);
            req.setCategory("AGENT_AUTHORIZATION_UNREACHABLE");
            req.setSeverity("warning");
            req.setSubjectType("AGENT");
            req.setSubjectId(subjectId);
            // Keyed on the agent and the rule, not on the call: an agent that runs hourly
            // hits the same wall every hour, and one bell row per wall is the useful
            // number. The endpoint dedupes on (tenant, category, sourceId).
            req.setSourceId("agent-auth-unreachable:" + subjectId + ":" + ruleOf(gateResult));
            Map<String, Object> payload = new HashMap<>();
            // REQUIRED by the endpoint: it refuses a payload with no status
            // ("payload.status required (V174 contract)") and the refusal is a 400 the client
            // swallows, so the omission costs the whole notification and says nothing anywhere.
            // NotificationContractTest pins this, because a mocked client cannot.
            payload.put("status", "blocked");
            // What the bell shows as the row's title. Its resolver reads payload.subjectName, and
            // with nothing there the row reads "Notification", which names nothing: the person
            // has to know WHICH agent stopped overnight.
            payload.put("subjectName", agentNameOf(subjectId, organizationId));
            payload.put("rule", ruleOf(gateResult));
            payload.put("summary", summaryOf(gateResult));
            req.setPayload(payload);
            req.setOccurredAt(Instant.now());
            notificationClient.emit(req);
        } catch (Exception ex) {
            log.debug("AGENT_AUTHORIZATION_UNREACHABLE emit failed for agent {} (non-critical): {}",
                    subjectId, ex.getMessage());
        }
    }

    /**
     * The agent's display name, best effort, scoped to the workspace it runs in.
     *
     * <p>Read here because this service owns agents; the notification's name is fixed at emit
     * time by design (the bell does not call back across services per row). A failed lookup
     * costs the name, never the notification.
     */
    private String agentNameOf(UUID agentId, String organizationId) {
        if (agentRepository != null) {
            try {
                return agentRepository.findByIdAndOrganizationIdStrict(agentId, organizationId)
                        .map(agent -> agent.getName())
                        .filter(name -> name != null && !name.isBlank())
                        .orElse("An agent");
            } catch (Exception ex) {
                log.debug("Could not read the name of agent {}: {}", agentId, ex.getMessage());
            }
        }
        return "An agent";
    }

    /** The agent's id as a UUID, or null when it is absent or not one. */
    private static UUID agentUuidOf(Map<String, Object> credentials) {
        String agentId = agentIdOf(credentials);
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(agentId.trim());
        } catch (IllegalArgumentException ex) {
            // Not a UUID. Parsing it inside the emit would throw before the send and lose the
            // notification to a catch that logs at debug, which is the same silence twice.
            return null;
        }
    }

    /**
     * Tell the agent its question is now in front of someone, somewhere it cannot see.
     *
     * <p>Only for a park that ended UNANSWERED. A refusal or a stop is already
     * settled and its own wording is the accurate one. Without this the agent ends
     * its turn believing the request simply ran out of time, and its next run asks
     * again: the duplicate is refused, but the agent is then surprised by its own
     * refusal instead of having read, in its own conversation, that it already asked.
     */
    private static ToolResult waitingElsewhere(ToolResult gateResult, ToolApprovalGate.Decision decision,
                                               ChannelAuthorizationClient.Delivery delivery) {
        if (delivery == null || !delivery.isWaitingOnSomeone()
                || decision == ToolApprovalGate.Decision.DENIED
                || decision == ToolApprovalGate.Decision.STOPPED) {
            return gateResult;
        }
        return withContent(gateResult, structuredGateContent(
                "authorization_requested",
                "The action has NOT run. Your request for permission was sent to the user on "
                        + channelName(delivery) + " and is still waiting there. Do NOT ask for it again "
                        + "and do NOT retry the call: while it is waiting, asking again is refused as a "
                        + "duplicate. Say in one sentence that you are waiting for permission, then "
                        + "finish your turn. If they allow it, the action runs on your next run without "
                        + "you asking again. If nobody ever answers, the request expires on its own and "
                        + "a later run may ask once more."));
    }

    /** The turn where the same question is already waiting: say so, and stop. */
    private static ToolResult alreadyAskedResult(ToolResult gateResult,
                                                 ChannelAuthorizationClient.Delivery delivery) {
        String since = delivery.requestedAt() != null ? " (sent " + delivery.requestedAt() + ")" : "";
        ToolResult result = withContent(gateResult, structuredGateContent(
                "authorization_pending",
                "The action has NOT run. You ALREADY asked the user for permission to do exactly this, on "
                        + channelName(delivery) + since + ", and they have not answered yet. No new request "
                        + "was sent. Do NOT ask again and do NOT retry: while that request is waiting, "
                        + "repeating it is refused as a duplicate. Say in one sentence that you are still "
                        + "waiting, then finish your turn. If nobody ever answers, it expires on its own "
                        + "and a later run may ask once more."));
        Map<String, Object> metadata = result.metadata() != null
                ? new HashMap<>(result.metadata()) : new HashMap<>();
        // No card was painted for this one, so the result consumer must not be told
        // one was - it would suppress its own and the app would show nothing at all.
        metadata.put("authorizationAlreadyPending", true);
        return ToolResult.builder()
                .toolCall(result.toolCall())
                .success(result.success())
                .content(result.content())
                .error(result.error())
                .durationMs(result.durationMs())
                .metadata(metadata)
                .build();
    }

    /**
     * Where the question went, as the agent should say it back to the user.
     *
     * <p>Both halves, because they answer different questions. The channel says which app to go
     * and look in; the destination's own title says WHICH conversation there, and a workspace
     * with a bot in three rooms has three of those. Orchestrator resolves that title on every
     * delivery precisely so this sentence can carry it, and dropping it here left the agent
     * saying "I asked on telegram" to somebody who then has to guess where.
     */
    private static String channelName(ChannelAuthorizationClient.Delivery delivery) {
        String channel = blankToNull(delivery.channel());
        String destination = blankToNull(delivery.chatLabel());
        if (channel == null && destination == null) {
            // Delivered by something that named neither. Still true, and still better than a
            // sentence that trails off where the place should be.
            return "their chat channel";
        }
        if (destination == null) {
            return channel;
        }
        if (channel == null) {
            return destination;
        }
        return channel + " (" + destination + ")";
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private static ToolResult withContent(ToolResult result, String content) {
        return ToolResult.builder()
                .toolCall(result.toolCall())
                .success(result.success())
                .content(content)
                .error(result.error())
                .durationMs(result.durationMs())
                .metadata(result.metadata())
                .build();
    }

    /** The shape every gate result uses, so the model reads one contract, not three. */
    private static String structuredGateContent(String status, String message) {
        try {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("status", status);
            structured.put("executed", false);
            structured.put("message", message);
            return SHARED_MAPPER.writeValueAsString(structured);
        } catch (Exception e) {
            // The wording matters more than the shape: a plain string still reaches the model.
            return message;
        }
    }

    private static String ruleOf(ToolResult gateResult) {
        Map<String, Object> metadata = gateResult.metadata();
        Object rule = metadata != null ? metadata.get("rule") : null;
        return rule != null ? String.valueOf(rule) : null;
    }

    /**
     * What makes two asks the same ask: the tool, and the arguments it was called with.
     *
     * <p>Derived from the CALL, not from the display summary the card carries: that
     * summary is only written when {@code summarizeArguments} produces one, and without
     * it the identity would collapse to the rule, so "publish post A" and "publish post
     * B" in one conversation would be treated as a repeat of each other. Keys are sorted
     * so an argument map in a different order is still the same ask.
     */
    private static String askFingerprint(ToolCall toolCall) {
        return AuthorizationAsk.material(toolCall.toolName(), toolCall.arguments());
    }

    private static String summaryOf(ToolResult gateResult) {
        Map<String, Object> metadata = gateResult.metadata();
        Object summary = metadata != null ? metadata.get("argsSummary") : null;
        return summary != null ? String.valueOf(summary) : null;
    }

    private static String agentIdOf(Map<String, Object> credentials) {
        Object agentId = credentials != null ? credentials.get("__agentId__") : null;
        return agentId != null ? String.valueOf(agentId) : null;
    }

    /**
     * Both spellings, like every other reader of this key in this class. A producer
     * using the plain alias would otherwise resolve to no workspace, and the request
     * would be silently skipped with "no channel" rather than delivered.
     */
    private String orgIdOf(Map<String, Object> credentials) {
        return credentialString(credentials, "orgId", "__orgId__");
    }

    /**
     * Hold a call whose result says "this service is not connected yet", then run it again
     * once the user connects, so the agent sees the real API response in the same turn.
     *
     * <p>Re-dispatched exactly ONCE. If the second attempt still reports a missing
     * connection the result is returned untouched, which lets the ordinary card path (and
     * its per-turn dedup) take over rather than looping on a connection that never lands.
     */
    ToolResult parkForCredential(ToolCall toolCall, ToolDefinition toolDefinition,
                                 String tenantId, Map<String, Object> credentials,
                                 ToolResult result, long startTime) {
        // A DIFFERENT key from the authorization park, even though it is the same call: one
        // call can raise both cards in turn, and a duplicate answer to the first (double
        // click, client retry) would otherwise land on the second and release a connect the
        // user has not made yet - re-dispatching straight back into the same missing
        // credential.
        String gateKey = toolCall.id() != null ? toolCall.id() + ":credential" : null;
        String streamId = streamIdOf(credentials);
        if (approvalGate == null || approvalCardPublisher == null || approvalCardExtractor == null
                || !approvalGate.isEnabled() || gateKey == null || gateKey.isBlank() || streamId == null) {
            return result;
        }
        // Only a context where a card is actually raised may hold a call on a human. A
        // scheduled workflow run, a task, a sub-agent and an agent-backed chat are all
        // EXEMPT from the authorization card, and parking them would wait out the full
        // deadline for a click nobody is there to make. The authorization gate gets this
        // check for free (it only runs on a result checkToolAuthorization produced, which
        // is already scope-gated); this one has to ask.
        if (!ToolAuthorizationScope.isCardRaised(credentials)) {
            return result;
        }
        // Only a sensitive rule may park here. That also excludes request_credential, whose
        // entire job is to RAISE a card: parking it would stall the turn waiting for an
        // answer to a question the agent deliberately asked, then hand back the same result.
        //
        // This does NOT re-check the loop's budget, and does not need to: a call with none
        // resolves to a deadline already in the past and beginPark refuses it. So the budget
        // is what really decides, and it is decided elsewhere - which is how the connect card
        // came to be dropped for a granted rule. A grant answers "may I run this", never "is
        // this service connected", but the loop withheld the budget for both. It now keeps it
        // for the calls that can raise a connect card, which is what makes this park
        // reachable at all for those users.
        //
        // THREE places have to agree about a new connect-card producer, and it is easy to
        // stop at two: SENSITIVE_ACTIONS (what this guard reads), canRaiseConnectCard (the
        // budget), and the producer itself must return a SUCCESSFUL result - a failure is
        // never turned into a card, which is why the generation path raises none today.
        if (!ToolAuthorizationGuard.requiresAuthorization(toolCall.toolName(), toolCall.arguments())
                // Same exclusion as the loop's budget and the authorization park: this rule
                // is performed BY the user out of band, so it is never held. Leaving it in
                // would make the three disagree about the one call none of them may hold.
                || ToolAuthorizationPolicy.isUserPerformedRule(toolCall.toolName(), actionOf(toolCall.arguments()))) {
            return result;
        }

        var card = approvalCardExtractor.extract(result)
                .filter(c -> c.kind() == ApprovalCardExtractor.ApprovalCard.Kind.SERVICE)
                .orElse(null);
        if (card == null) {
            return result;
        }

        String conversationId = conversationIdOf(credentials);
        // See parkForAuthorization: the park must be advertised before its card exists, and
        // refusing here is also what stops a SECOND park drawing a card the shared window
        // has already run out of time for.
        ToolApprovalGate.ParkRequest parkRequest = ParkRequests.of(credentials, gateKey, startTime);
        if (!approvalGate.beginPark(parkRequest)) {
            return result;
        }
        String buffered = approvalCardPublisher.publishServiceApproval(streamId, conversationId,
                card.services(), card.reason(), card.needsAttention(), true, gateKey);
        if (buffered == null) {
            // See parkForAuthorization: no card means nothing to answer, so do not hold.
            approvalGate.abandonPark(conversationId, gateKey);
            return result;
        }

        ToolApprovalGate.Decision decision = approvalGate.awaitDecision(parkRequest);
        dropSettledCard(streamId, buffered, decision);
        if (decision != ToolApprovalGate.Decision.APPROVED) {
            return withCardEmitted(result, decision);
        }

        log.info("User connected the service while {} was parked - re-running the call", toolCall.toolName());
        return dispatch(toolCall, toolDefinition, tenantId, credentials, startTime);
    }

    /**
     * True for the one rule whose approval does NOT mean "run the tool". Parking it would
     * hold a call that approving is not supposed to release, so it keeps the non-blocking
     * flow: card, turn ends, user installs, user comes back. The rule itself lives in
     * {@link ToolAuthorizationPolicy} because the agent loop has to exclude it from the
     * park BUDGET for the same reason.
     */
    private static boolean isUserPerformedRule(ToolResult gateResult) {
        Map<String, Object> metadata = gateResult.metadata();
        String rule = metadata != null ? (String) metadata.get("rule") : null;
        if (rule == null) {
            return false;
        }
        int separator = rule.indexOf(':');
        return separator > 0 && ToolAuthorizationPolicy.isUserPerformedRule(
                rule.substring(0, separator), rule.substring(separator + 1));
    }

    private static ToolResult withCardEmitted(ToolResult result, ToolApprovalGate.Decision decision) {
        Map<String, Object> metadata = result.metadata() != null
                ? new HashMap<>(result.metadata()) : new HashMap<>();
        metadata.put(ToolApprovalGate.META_CARD_EMITTED, true);
        metadata.put(ToolApprovalGate.META_DECISION,
                decision.name().toLowerCase(java.util.Locale.ROOT));
        return ToolResult.builder()
                .toolCall(result.toolCall())
                .success(result.success())
                .content(answeredContent(result.content(), decision))
                .error(result.error())
                .durationMs(result.durationMs())
                .metadata(metadata)
                .build();
    }

    /**
     * Tell the model what actually happened when the park ended with an ANSWER.
     *
     * <p>The pre-gate content says the user "has only been asked to authorize it" and that
     * the action will run "if the user approves" - true while a card is waiting, and false
     * the moment it is answered. Left as-is after a refusal, the agent narrates a decision
     * that is still pending and may keep offering to retry something the user turned down.
     * Metadata cannot fix this: the model reads the CONTENT, and only conversation-service
     * reads the metadata.
     *
     * <p>An unanswered end (expired, unavailable) keeps the original wording, because it is
     * still accurate: the card is on screen and nobody has decided.
     */
    private static String answeredContent(String content, ToolApprovalGate.Decision decision) {
        String verdict = switch (decision) {
            case DENIED -> "The user REFUSED this action. It did not run and no result exists. "
                    + "Do not call it again, and do not ask for it again unless the user brings it up. "
                    + "Continue with other work or finish your turn.";
            case STOPPED -> "The user stopped this turn. The action did not run and no result exists. "
                    + "Do not call it again. Finish your turn.";
            default -> null;
        };
        if (verdict == null) {
            return content;
        }
        try {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("status", decision == ToolApprovalGate.Decision.DENIED ? "denied" : "stopped");
            structured.put("executed", false);
            structured.put("message", verdict);
            return SHARED_MAPPER.writeValueAsString(structured);
        } catch (Exception e) {
            // The wording matters more than the shape: a plain string still reaches the model.
            return verdict;
        }
    }

    /** Small, stateless, and only ever used for the two short answers above. */
    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    /**
     * Stop replaying a card the user has DEALT WITH, and keep replaying one still waiting.
     *
     * <p>The distinction is the same one that decides persistence: an answer settles a card,
     * running out of time does not. A settled card left in the replay buffer comes back on
     * the next reload of a turn that is still running, and answering it a second time
     * releases nothing (the park is gone) while quietly queueing a redundant turn.
     */
    private void dropSettledCard(String streamId, String buffered, ToolApprovalGate.Decision decision) {
        // Only an ANSWERED card leaves the buffer. An unanswered one is still the user's to
        // deal with, and the row that would otherwise carry it is written at the end of the
        // whole run - on the bridge, minutes later - so dropping it here would leave a
        // reload in that window with no card at all.
        if (decision == ToolApprovalGate.Decision.APPROVED
                || decision == ToolApprovalGate.Decision.DENIED
                || decision == ToolApprovalGate.Decision.STOPPED) {
            approvalCardPublisher.unbuffer(streamId, buffered);
        }
    }

    /** The {@code action} argument, which together with the tool name names the rule. */
    private static String actionOf(Map<String, Object> arguments) {
        Object action = arguments != null ? arguments.get("action") : null;
        return action != null ? String.valueOf(action).trim() : null;
    }

    private static String conversationIdOf(Map<String, Object> credentials) {
        return ParkRequests.conversationIdOf(credentials);
    }

    private static String streamIdOf(Map<String, Object> credentials) {
        return ParkRequests.streamIdOf(credentials);
    }

    /**
     * Returns a gating {@code ToolResult} (carrying {@code toolAuthorizationRequired}
     * metadata) when this call matches the sensitive-action policy in an interactive
     * chat AND has not yet been authorized; otherwise {@code null} (the call proceeds
     * normally).
     *
     * <p>What happens to that result depends on the caller. {@link #parkForAuthorization}
     * shows its card and HOLDS the call until the user answers, and on an approval the
     * tool runs for real and this result is discarded. It reaches the agent when nobody
     * was in a position to answer in time (gate off, scope raises no card, park out of
     * budget) and ALWAYS for a user-performed rule such as {@code application:acquire},
     * which is never held because the user does the work themselves. Its wording is
     * written for those cases.
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
            // Exempt: workflow run / task / sub-agent / agent-backed chat / headless. NOT the
            // CLI bridge - a claude-code or codex general chat carries a conversation and a
            // live stream, so it raises cards and holds calls exactly like the direct route.
            return null;
        }
        if (isAlreadyAuthorized(credentials, rule, toolCall)) {
            return null; // approved this turn (transient resume) or persisted "always authorize"
        }
        log.info("Tool authorization required for rule={} (toolCallId={}) - pausing for user approval",
                rule, toolCall.id());
        return buildAuthorizationRequiredResult(toolCall, rule, startTime);
    }

    private boolean isAlreadyAuthorized(Map<String, Object> credentials, String rule, ToolCall toolCall) {
        if (credentials == null) {
            return false;
        }
        Object approved = credentials.get("__approvedToolActions__");
        if (!(approved instanceof Collection<?> col)) {
            return false;
        }
        // Three ways in, and the third is why the call is passed: an approval given in a chat is
        // recorded against the ASK, so it covers the call the person was shown and no other call
        // of the same rule. "*" is the conversation-wide toggle; the bare rule is an in-app
        // approval or a remembered "always allow".
        return AuthorizationAsk.authorizes(col, rule,
                AuthorizationAsk.fingerprintOfCall(rule, toolCall.toolName(), toolCall.arguments()));
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
        // What the card is ABOUT, when the rule has something worth naming: which workflow
        // and version is going live, which cron is being armed. Read off the call's own
        // arguments, so it can only describe what would actually run. Null for every rule
        // that names nothing, and the card falls back to its generic copy.
        Map<String, Object> subject = AuthorizationSubject.of(rule, args);
        if (subject != null) {
            metadata.put(AuthorizationSubject.METADATA_KEY, subject);
        }

        // POV-agent content: the user has been asked to authorize this action - do NOT retry/loop.
        // Written for the case where the agent actually RECEIVES this: nobody answered in time
        // (or no card was raised at all), so the turn ends here and the user's later answer
        // starts a new one. When the park is answered in time this content never reaches the
        // agent - the tool runs and its real result is returned instead.
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
                    + "this turn - after installing, the chat resumes with an installation confirmation. Resume the original task: inspect the app with get, then execute using its declared inputs and verify the result. "
                    + "Do NOT call this action again, do NOT claim or assume the app is installed/acquired, "
                    + "and do NOT use it as if it existed. Continue with other work or finish your turn.");
        } else {
            structured.put("message", "The action has NOT run and NO result exists - do not describe, "
                    + "summarize, or invent any outcome. The user was asked to authorize it and no answer "
                    + "reached this call, so waiting will not produce one: do not call this action again, "
                    + "and continue with other work or finish your turn. If they authorize it afterwards, "
                    + "they come back with a new request.");
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
        // What that agent may DO, alongside who it is. This method copies an
        // explicit list of keys, so a grant left out of it simply never leaves
        // this service, and the tool at the other end sees a caller with no
        // stated permissions: exactly the state that means "allowed".
        copyCredential(request, credentials, "enabledModules",
                AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, "enabledModules");
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
        // Derived, never re-listed: a hand-copied list here is how an axis goes inert (see
        // ToolAccessControl.ENFORCED_ACCESS_MODE_CATEGORIES).
        for (String key : ToolAccessControl.ACCESS_MODE_KEYS) {
            copyCredential(request, credentials, key, "__" + key + "__", key);
        }
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
