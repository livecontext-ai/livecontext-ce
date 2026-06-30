package com.apimarketplace.conversation.service;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationSharingService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationSharingService.class);

    private final ConversationRepository conversationRepository;
    private final ConversationMapper conversationMapper;

    public ConversationSharingService(ConversationRepository conversationRepository,
                                      ConversationMapper conversationMapper) {
        this.conversationRepository = conversationRepository;
        this.conversationMapper = conversationMapper;
    }

    /**
     * PR28 - authorize a sharing-related action on the given conversation. Pre-PR28
     * the service gated on {@code conversation.getUserId().equals(userId)} ONLY,
     * meaning an org teammate of the owner could neither share nor modify a
     * conversation tagged for their workspace. Post-PR28: caller is authorized if
     * EITHER (a) they own the conversation OR (b) the conversation is tagged for an
     * org and the caller's active workspace matches that org.
     *
     * <p>Throws IllegalArgumentException (mapped to 404 in the controller) on failure
     * so a non-member never learns whether the conversation exists.
     */
    @TolerantScope(reason = "PR28 sharing rights - owner can always toggle sharing on their own conversation regardless of active workspace; org teammate can toggle sharing on org-tagged conversation when active workspace matches. Strict-isolation would lock an owner out of disabling sharing on a conv they tagged with an org but later switched away from.")
    private void assertAuthorized(Conversation conversation, String userId, String organizationId) {
        if (!ScopeGuard.isInOwnerOrOrgScope(
                userId, organizationId,
                conversation.getUserId(), conversation.getOrganizationId())) {
            throw new IllegalArgumentException("Not authorized to modify this conversation");
        }
    }

    /** Back-compat - defaults to personal scope (orgId=null → owner-only check). */
    public ConversationDto enableSharing(String conversationId, String userId, String shareMode, Boolean memoryEnabled) {
        return enableSharing(conversationId, userId, null, shareMode, memoryEnabled);
    }

    @Transactional
    public ConversationDto enableSharing(String conversationId, String userId, String organizationId,
                                          String shareMode, Boolean memoryEnabled) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));

        assertAuthorized(conversation, userId, organizationId);

        if (conversation.getShareToken() == null) {
            conversation.setShareToken(generateShareToken());
        }

        conversation.setShareMode(shareMode != null ? shareMode : "read");
        if (memoryEnabled != null) {
            conversation.setMemoryEnabled(memoryEnabled);
        }

        Conversation saved = conversationRepository.save(conversation);
        logger.info("Enabled sharing for conversation {} with mode '{}' by user {} (org={})",
                conversationId, shareMode, userId, organizationId);

        return conversationMapper.toDto(saved);
    }

    /** Back-compat. */
    public ConversationDto updateShareSettings(String conversationId, String userId,
                                                String shareMode, Boolean memoryEnabled) {
        return updateShareSettings(conversationId, userId, null, shareMode, memoryEnabled);
    }

    @Transactional
    public ConversationDto updateShareSettings(String conversationId, String userId, String organizationId,
                                                String shareMode, Boolean memoryEnabled) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));

        assertAuthorized(conversation, userId, organizationId);

        if (shareMode != null) {
            conversation.setShareMode(shareMode);
        }
        if (memoryEnabled != null) {
            conversation.setMemoryEnabled(memoryEnabled);
        }

        Conversation saved = conversationRepository.save(conversation);
        logger.info("Updated share settings for conversation {} by user {} (org={})",
                conversationId, userId, organizationId);

        return conversationMapper.toDto(saved);
    }

    /** Back-compat. */
    public void disableSharing(String conversationId, String userId) {
        disableSharing(conversationId, userId, null);
    }

    @Transactional
    public void disableSharing(String conversationId, String userId, String organizationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));

        assertAuthorized(conversation, userId, organizationId);

        // Keep the token so re-enabling preserves the same link (matches SharedLink registry pattern)
        conversation.setShareMode("off");

        conversationRepository.save(conversation);
        logger.info("Disabled sharing for conversation {} by user {} (org={})",
                conversationId, userId, organizationId);
    }

    public Optional<Conversation> findByShareToken(String shareToken) {
        return conversationRepository.findByShareToken(shareToken);
    }

    private static String generateShareToken() {
        return "cs_" + UUID.randomUUID().toString().replace("-", "");
    }
}
