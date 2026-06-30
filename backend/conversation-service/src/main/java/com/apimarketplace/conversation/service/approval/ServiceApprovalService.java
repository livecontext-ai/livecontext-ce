package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * Service for managing service approvals in conversations.
 *
 * Handles the approval lifecycle for external services (gmail, slack, etc.)
 * that require user consent before the agent can access them.
 *
 * The approved services are stored on the Conversation entity (single source of truth).
 * The inline preview in message history just shows which services were requested,
 * without tracking approval status per message.
 *
 * Single Responsibility: Manage approved services for conversations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceApprovalService {

    private final ConversationRepository conversationRepository;

    /**
     * Check if a service is approved for a conversation.
     *
     * @param conversationId The conversation ID
     * @param serviceType The service type (e.g., "gmail", "slack")
     * @return true if the service is approved
     */
    @Transactional(readOnly = true)
    public boolean isServiceApproved(String conversationId, String serviceType) {
        if (conversationId == null || serviceType == null) {
            return false;
        }

        return conversationRepository.findById(conversationId)
            .map(conv -> conv.isServiceApproved(serviceType))
            .orElse(false);
    }

    /**
     * Get all approved services for a conversation.
     *
     * @param conversationId The conversation ID
     * @return Set of approved service types
     */
    @Transactional(readOnly = true)
    public Set<String> getApprovedServices(String conversationId) {
        if (conversationId == null) {
            return Set.of();
        }

        return conversationRepository.findById(conversationId)
            .map(Conversation::getApprovedServices)
            .orElse(Set.of());
    }

    /**
     * Approve a single service for a conversation.
     *
     * @param conversationId The conversation ID
     * @param serviceType The service type to approve
     * @return true if approval was successful
     */
    @Transactional
    public boolean approveService(String conversationId, String serviceType) {
        return approveServices(conversationId, Set.of(serviceType));
    }

    /**
     * Approve multiple services for a conversation (batch approval).
     *
     * @param conversationId The conversation ID
     * @param serviceTypes The service types to approve
     * @return true if approval was successful
     */
    @Transactional
    public boolean approveServices(String conversationId, Set<String> serviceTypes) {
        if (conversationId == null || serviceTypes == null || serviceTypes.isEmpty()) {
            log.warn("Cannot approve services: invalid parameters");
            return false;
        }

        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            log.warn("Cannot approve services: conversation not found: {}", conversationId);
            return false;
        }

        Conversation conversation = convOpt.get();
        conversation.approveServices(serviceTypes);
        conversationRepository.save(conversation);

        log.info("Approved services {} for conversation {}", serviceTypes, conversationId);
        return true;
    }

    /**
     * Revoke approval for a service.
     *
     * @param conversationId The conversation ID
     * @param serviceType The service type to revoke
     * @return true if revocation was successful
     */
    @Transactional
    public boolean revokeServiceApproval(String conversationId, String serviceType) {
        if (conversationId == null || serviceType == null) {
            return false;
        }

        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            return false;
        }

        Conversation conversation = convOpt.get();
        conversation.revokeServiceApproval(serviceType);
        conversationRepository.save(conversation);

        log.info("Revoked service {} approval for conversation {}", serviceType, conversationId);
        return true;
    }

    /**
     * Clear all service approvals for a conversation.
     *
     * @param conversationId The conversation ID
     */
    @Transactional
    public void clearAllApprovals(String conversationId) {
        if (conversationId == null) {
            return;
        }

        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.setApprovedServices(Set.of());
            conversationRepository.save(conversation);
            log.info("Cleared all service approvals for conversation {}", conversationId);
        });
    }
}
