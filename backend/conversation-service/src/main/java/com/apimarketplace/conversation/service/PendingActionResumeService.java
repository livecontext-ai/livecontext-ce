package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.MessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for resuming pending actions when their blockers are resolved.
 *
 * Called when:
 * - Credentials are configured (via API endpoint or event)
 * - User provides required input
 * - External dependency becomes available
 *
 * This service:
 * 1. Finds conversations waiting for the resolved condition
 * 2. Injects a system message to restore context
 * 3. Re-triggers the pending tool call
 * 4. Clears the pending action
 */
@Service
public class PendingActionResumeService {

    private static final Logger logger = LoggerFactory.getLogger(PendingActionResumeService.class);

    private final PendingActionService pendingActionService;
    private final MessageService messageService;

    public PendingActionResumeService(PendingActionService pendingActionService,
                                       MessageService messageService) {
        this.pendingActionService = pendingActionService;
        this.messageService = messageService;
    }

    /**
     * Called when a credential is configured.
     * Finds and resumes all conversations waiting for this credential.
     *
     * @param credentialType The type of credential (e.g., "gmail", "tavily")
     * @param userId The user who configured the credential
     * @return Number of conversations resumed
     */
    @Transactional
    public int onCredentialConfigured(String credentialType, String userId) {
        logger.info("Credential configured: {} for user {}", credentialType, userId);

        List<String> waitingConversations = pendingActionService.findConversationsWaitingForCredential(credentialType);

        int resumed = 0;
        for (String conversationId : waitingConversations) {
            try {
                if (resumeConversation(conversationId, "credential:" + credentialType)) {
                    resumed++;
                }
            } catch (Exception e) {
                logger.error("Failed to resume conversation {}: {}", conversationId, e.getMessage());
            }
        }

        logger.info("Resumed {} conversations for credential {}", resumed, credentialType);
        return resumed;
    }

    /**
     * Resume a specific conversation's pending action.
     *
     * @param conversationId The conversation to resume
     * @param resolvedCondition The condition that was resolved
     * @return true if resumed successfully
     */
    @Transactional
    public boolean resumeConversation(String conversationId, String resolvedCondition) {
        logger.info("Resuming conversation {} - resolved: {}", conversationId, resolvedCondition);

        var pendingActionOpt = pendingActionService.getPendingAction(conversationId);
        if (pendingActionOpt.isEmpty()) {
            logger.warn("No pending action found for conversation {}", conversationId);
            return false;
        }

        Map<String, Object> pendingAction = pendingActionOpt.get();

        // Inject system message to restore context
        injectResumeContext(conversationId, pendingAction, resolvedCondition);

        // Clear the pending action
        pendingActionService.clearPendingAction(conversationId);

        logger.info("Conversation {} ready to resume", conversationId);
        return true;
    }

    /**
     * Inject a system message that provides context for resumption.
     */
    private void injectResumeContext(String conversationId, Map<String, Object> pendingAction, String resolvedCondition) {
        String originalRequest = PendingActionService.extractOriginalRequest(pendingAction);
        Map<String, Object> toolCall = PendingActionService.extractToolCall(pendingAction);
        String contextSummary = (String) pendingAction.get("context_summary");

        StringBuilder contextMessage = new StringBuilder();
        contextMessage.append("[CONTEXT RESTORED]\n");
        contextMessage.append("The user's original request was: \"").append(originalRequest).append("\"\n");

        if (contextSummary != null) {
            contextMessage.append("Context: ").append(contextSummary).append("\n");
        }

        if (toolCall != null) {
            String toolName = (String) toolCall.get("name");
            contextMessage.append("The pending action was: call tool '").append(toolName).append("'\n");
        }

        contextMessage.append("The blocker (").append(resolvedCondition).append(") has been resolved.\n");
        contextMessage.append("Please retry the pending action now that credentials are available.");

        // Save system message
        MessageDto systemMessage = new MessageDto();
        systemMessage.setConversationId(conversationId);
        systemMessage.setRole("system");
        systemMessage.setContent(contextMessage.toString());
        systemMessage.setTimestamp(Instant.now().toString());

        try {
            messageService.addMessage(conversationId, systemMessage);
            logger.debug("Injected resume context into conversation {}", conversationId);
        } catch (Exception e) {
            logger.error("Failed to inject resume context: {}", e.getMessage());
        }
    }

    /**
     * Manually trigger resume for a conversation (can be called from API).
     *
     * @param conversationId The conversation ID
     * @return The pending action that was cleared, or null if none
     */
    @Transactional
    public Map<String, Object> manualResume(String conversationId) {
        var pendingActionOpt = pendingActionService.getPendingAction(conversationId);
        if (pendingActionOpt.isEmpty()) {
            return null;
        }

        Map<String, Object> pendingAction = pendingActionOpt.get();
        String waitingFor = (String) pendingAction.get("waiting_for");

        resumeConversation(conversationId, waitingFor + " (manual)");

        return pendingAction;
    }
}
