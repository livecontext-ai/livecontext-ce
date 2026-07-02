package com.apimarketplace.monolith.chat;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
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

    @PostMapping(path = "/api/internal/chat/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chatSync(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        request.setUserId(userId);
        request.setOrgId(organizationId);
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

        // Source-type-scoped gate (cloud parity): FREE monthly workflow credits
        // must not admit a scheduled/webhook chat turn. No-op in CE unlimited
        // mode where the check always allows.
        if (!creditClient.checkCredits(userId,
                com.apimarketplace.common.credit.CreditConsumptionClient.SOURCE_TYPE_CHAT_CONVERSATION)) {
            // Persist the attempt + a typed error message in the conversation so the
            // user sees the schedule was skipped, instead of an empty conv that
            // looks broken. Mirrors the cloud InternalChatController fix so CE
            // schedule/webhook/task/widget runs leave the same audit trail.
            String errorContent = "[Error] Insufficient credits - this scheduled run was skipped. "
                + "Top up your wallet to resume scheduled execution.";
            messageService.persistAttemptAndError(conversationId, request.getMessage(), errorContent);
            // Also record a FAILED execution row for Agent Performance / Agent
            // Fleet visibility (stop reason BUDGET_EXHAUSTED). Mirror of cloud.
            observabilityClient.recordFailureAsync(userId, organizationId,
                request.getAgentId(), request.getSource(), conversationId,
                "BUDGET_EXHAUSTED", "Insufficient credits",
                request.getMessage(), errorContent,
                request.getProvider(), request.getModel());
            // Mirror of cloud: flash the Fleet view so the throttled fire is
            // visible (frontend reducer bumps completionSeq → metrics refetch).
            agentService.publishFleetFailureNoExecution(
                request.getAgentId(), request.getModel(),
                request.getSource(), request.getTaskId(),
                "FAILED", 0L);
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("success", false, "error", "Insufficient credits", "conversationId", conversationId));
        }

        MessageDto userMessage = new MessageDto();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(request.getMessage());
        userMessage.setTimestamp(Instant.now().toString());
        messageService.addMessage(conversationId, userMessage);

        Map<String, Object> result = agentService.executeSync(request, conversationId);
        return ResponseEntity.ok(result);
    }

}
