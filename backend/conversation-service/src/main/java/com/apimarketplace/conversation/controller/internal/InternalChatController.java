package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.conversation.controller.v3.chat.ChatStreamInitializer;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.service.ConversationExecutionLockService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.ai.AgentObservabilityClient;
import com.apimarketplace.conversation.service.ai.ConversationAgentService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.apimarketplace.common.credit.ChatCreditRefusal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Internal chat endpoints for service-to-service calls.
 * Bypasses gateway authentication (path starts with /api/internal/).
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/chat")
@RequiredArgsConstructor
public class InternalChatController {

    private final ChatStreamInitializer streamInitializer;
    private final ConversationAgentService agentService;
    private final MessageService messageService;
    private final CreditConsumptionClient creditClient;
    private final AgentObservabilityClient observabilityClient;
    private final ConversationExecutionLockService executionLockService;

    /**
     * Async chat - fires agent execution, returns immediately.
     * Events flow via WebSocket/Redis.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-ID") String userId,
            // PR21 R2 - workspace identity. PR16 forwarders propagate this from
            // upstream service calls (webhook trigger, agent fire). Without this
            // read, conversations created via /api/internal/chat would land with
            // organization_id = NULL even when the caller IS in an org workspace.
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        request.setUserId(userId);
        request.setOrgId(organizationId);
        log.info("Internal chat (async) - User: {} (org: {}), Conversation: {}, Source: {}",
                userId, organizationId, request.getConversationId(), request.getSource());

        // Source-type-scoped gate: on the FREE plan a chat/agent turn draws the
        // monthly credits only on a free-tier model and the PAYG bucket alone
        // otherwise, so the check must not pass on monthly credits for another model
        // (pre-fix, an unscoped total-balance check let the LLM run and pushed the
        // PAYG bucket negative post-flight).
        //
        // The model here is the REQUEST's, which is not always the one the turn runs on:
        // an agent row can pin its own, and that resolution happens downstream in
        // ConversationAgentService. Since V494 the model decides whether the Free monthly
        // credits count (V512), so the two can disagree - stricter when the request names
        // none (they are simply not counted, and a turn they would have paid for is refused),
        // looser when the request names an open model and the agent pins a closed one.
        // The DEBIT is authoritative either way; it re-resolves and draws the buckets the
        // real model allows. Resolving the agent's model here would mean a second lookup
        // on the gate path and a copy of a resolution that belongs downstream.
        if (!creditClient.checkCredits(userId,
                com.apimarketplace.common.credit.CreditConsumptionClient.SOURCE_TYPE_CHAT_CONVERSATION,
                request.getProvider(), request.getModel())) {
            return Mono.just(ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", ChatCreditRefusal.MESSAGE)));
        }

        return streamInitializer.initializeStreamAsync(request, userId);
    }

    /**
     * Synchronous chat - executes agent, waits for completion, returns result directly.
     * Used by webhook (source=WEBHOOK) and schedule (source=SCHEDULE).
     * Same pipeline as async: context building, agent execution, persistence, observability.
     */
    @PostMapping(path = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chatSync(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        request.setUserId(userId);
        request.setOrgId(organizationId);
        String conversationId = request.getConversationId();

        log.info("Internal chat (sync) - User: {} (org: {}), Conversation: {}, Source: {}",
                userId, organizationId, conversationId, request.getSource());

        // conversationId is required by every downstream branch - even the 402 path
        // now writes user+assistant messages into the conversation so the user can
        // see why the schedule produced no output. Check this first so the 402
        // short-circuit below has a real conversation to write to.
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "conversationId is required"));
        }

        try {
            return executionLockService.withConversationLock(conversationId,
                    () -> chatSyncLocked(request, userId, organizationId, conversationId));
        } catch (ConversationExecutionLockService.ConversationExecutionLockTimeoutException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false,
                            "error", "Conversation is busy",
                            "conversationId", conversationId));
        }
    }

    private ResponseEntity<Map<String, Object>> chatSyncLocked(ChatRequest request,
                                                               String userId,
                                                               String organizationId,
                                                               String conversationId) {
        // Source-type-scoped gate (see the async endpoint above): FREE monthly
        // workflow credits must not admit a scheduled/webhook chat turn.
        if (!creditClient.checkCredits(userId,
                com.apimarketplace.common.credit.CreditConsumptionClient.SOURCE_TYPE_CHAT_CONVERSATION,
                request.getProvider(), request.getModel())) {
            // Persist the attempt + a typed error message in the conversation so the
            // user actually sees the schedule was skipped, instead of an empty conv
            // that looks broken. Pre-fix: this branch returned 402 with zero side
            // effects - the user had no way to know their wallet was empty.
            String errorContent = "[Error] " + ChatCreditRefusal.MESSAGE + " - this scheduled run was skipped. "
                    + "Top up your wallet to resume scheduled execution.";
            messageService.persistAttemptAndError(conversationId, request.getMessage(), errorContent);
            // Also record a FAILED execution row so the attempt shows up in Agent
            // Performance / Agent Fleet with stop reason BUDGET_EXHAUSTED. Without
            // this, the conversation shows the error but the agent dashboards stay
            // empty - operator sees no signal that the agent is being throttled.
            // userPrompt + assistant message also threaded so the execution detail
            // view (agent_execution_messages) shows what the schedule asked for and
            // why nothing ran.
            observabilityClient.recordFailureAsync(userId, organizationId,
                    request.getAgentId(), request.getSource(), conversationId,
                    "BUDGET_EXHAUSTED", ChatCreditRefusal.MESSAGE,
                    request.getMessage(), errorContent,
                    request.getProvider(), request.getModel(),
                    // A refused task run is still the run its task points at.
                    com.apimarketplace.conversation.service.ai.ConversationAgentService.callerExecutionIdOrNew(request));
            // Publish synthetic execution_started + completed(FAILED) so the
            // Fleet view flashes the agent with a fast pulse and refetches
            // metrics (the new BUDGET_EXHAUSTED chip appears within the same
            // tick). Without this, the 402 path bypasses executeSync entirely
            // and the Fleet canvas shows nothing - operator can't tell from
            // the UI that the agent is being throttled.
            agentService.publishFleetFailureNoExecution(
                    request.getAgentId(), request.getModel(),
                    request.getSource(), request.getTaskId(),
                    "FAILED", 0L);
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("success", false, "error", ChatCreditRefusal.MESSAGE, "conversationId", conversationId));
        }

        // Save user message to conversation (same as ChatStreamingService does for async)
        MessageDto userMsg = new MessageDto();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(request.getMessage());
        userMsg.setTimestamp(Instant.now().toString());
        messageService.addMessage(conversationId, userMsg);

        // Execute synchronously - blocks until agent completes
        Map<String, Object> result = agentService.executeSync(request, conversationId);

        return ResponseEntity.ok(result);
    }

}
