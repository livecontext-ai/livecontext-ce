package com.apimarketplace.monolith.chat;

import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.controller.internal.InternalChatController;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.service.ConversationExecutionLockService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.ai.AgentObservabilityClient;
import com.apimarketplace.conversation.service.ai.ConversationAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Monolith replacement for conversation-service internal sync chat.
 *
 * CE excludes conversation-service internal controllers because the async
 * controller path depends on cloud streaming infrastructure. Agent tasks,
 * schedules, and webhooks still use ConversationClient#sendChatSync, so the
 * monolith must expose the sync contract locally.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithInternalChatController {

    private final MessageService messageService;
    private final ConversationAgentService agentService;
    private final CreditConsumptionClient creditClient;
    private final AgentObservabilityClient observabilityClient;
    private final ConversationQueryService conversationQueryService;
    private final ConversationExecutionLockService executionLockService;

    /**
     * Reachable only in-process: MonolithSecurityFilter 404s any non-loopback caller on this path,
     * so every header below was set by a service caller (ConversationClient#sendChatSync), not by
     * an end user. That caller always sets X-User-ID, and X-Organization-ID when it has the
     * resource's workspace. The two role headers are only present when it passed no workspace and
     * OrgContextHeaderForwarder copied them from the inbound request it is serving (the org role
     * also from the async org context): most scheduled, webhook and task turns pass a workspace
     * and therefore run with no org role and no platform roles. They are read here so a body can never supply them.
     */
    @PostMapping(path = "/api/internal/chat/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chatSync(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestHeader(value = "X-User-Roles", required = false) String userRoles) {

        // Authorization context comes from the headers only (MonolithChatController parity);
        // ChatRequest no longer binds orgId / orgRole / userRoles from the JSON body.
        request.setUserId(userId);
        request.setOrgId(organizationId);
        request.setOrgRole(orgRole);
        request.setUserRoles(userRoles);
        String conversationId = request.getConversationId();

        log.info("[CE] Internal sync chat - user: {} (org: {}), conversation: {}, source: {}",
            userId, organizationId, conversationId, request.getSource());

        // conversationId is required by every downstream branch - even the 402 path
        // now writes user+assistant messages into the conversation so the CE user
        // can see why the schedule produced no output. Check this first so the 402
        // short-circuit below has a real conversation to write to. Verdict change
        // (blank-conv-id + zero credits) is 402 → 400; kept aligned with the cloud
        // InternalChatController so CE and cloud return identical responses for the
        // same request shape.
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", "conversationId is required"));
        }

        // Every branch below writes into the conversation (the 402 audit trail included), so the
        // caller must own it or share its workspace. 404, not 403: no existence disclosure.
        if (!conversationQueryService.isConversationInStrictScope(conversationId, userId, organizationId)) {
            log.warn("[CE] Internal sync chat refused - user {} (org: {}) may not write conversation {}",
                userId, organizationId, conversationId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "error", "Conversation not found"));
        }

        // One turn at a time per conversation (cloud parity): the agent tasks of one agent share a
        // conversation, so two task runs at once would interleave their messages and each run on
        // the other's half-written history. Same lock service, same 409 as the cloud
        // InternalChatController; the 402 audit trail below is written under the lock too.
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
        // Source-type-scoped gate (cloud parity): FREE monthly workflow credits
        // must not admit a scheduled/webhook chat turn, and the AI allowance may fund
        // one when the model is open to the free tier (V494). No-op in CE unlimited
        // mode where the check always allows, but kept identical to the cloud gate so
        // the two editions cannot answer the same request differently.
        if (!creditClient.checkCredits(userId,
                com.apimarketplace.common.credit.CreditConsumptionClient.SOURCE_TYPE_CHAT_CONVERSATION,
                request.getProvider(), request.getModel())) {
            // Persist the attempt + a typed error message in the conversation so the
            // user sees the schedule was skipped, instead of an empty conv that
            // looks broken. Mirrors the cloud InternalChatController fix so CE
            // schedule/webhook/task/widget runs leave the same audit trail.
            String errorContent = "[Error] " + ChatCreditRefusal.MESSAGE + " - this scheduled run was skipped. "
                + "Top up your wallet to resume scheduled execution.";
            messageService.persistAttemptAndError(conversationId, request.getMessage(), errorContent,
                InternalChatController.restrictedOrNull(request));
            // Also record a FAILED execution row for Agent Performance / Agent
            // Fleet visibility (stop reason BUDGET_EXHAUSTED). Mirror of cloud.
            observabilityClient.recordFailureAsync(userId, organizationId,
                request.getAgentId(), request.getSource(), conversationId,
                "BUDGET_EXHAUSTED", ChatCreditRefusal.MESSAGE,
                request.getMessage(), errorContent,
                request.getProvider(), request.getModel(),
                // A refused task run is still the run its task points at (cloud parity).
                ConversationAgentService.callerExecutionIdOrNew(request));
            // Mirror of cloud: flash the Fleet view so the throttled fire is
            // visible (frontend reducer bumps completionSeq → metrics refetch).
            agentService.publishFleetFailureNoExecution(
                request.getAgentId(), request.getModel(),
                request.getSource(), request.getTaskId(),
                "FAILED", 0L);
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("success", false, "error", ChatCreditRefusal.MESSAGE, "conversationId", conversationId));
        }

        MessageDto userMessage = new MessageDto();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(request.getMessage());
        userMessage.setTimestamp(Instant.now().toString());
        // LC-066 cloud parity: a delegated task written from a restricted execution carries Gmail /
        // Drive content, so its turn is stored RESTRICTED, exactly as InternalChatController does.
        userMessage.setDataSensitivity(InternalChatController.restrictedOrNull(request));
        messageService.addMessage(conversationId, userMessage);

        Map<String, Object> result = agentService.executeSync(request, conversationId);
        return ResponseEntity.ok(result);
    }

}
