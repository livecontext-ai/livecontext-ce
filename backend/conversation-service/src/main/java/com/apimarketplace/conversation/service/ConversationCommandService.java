package com.apimarketplace.conversation.service;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.exception.ConversationNotFoundException;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Commandes d'ecriture sur les conversations.
 */
@Service
@Transactional
public class ConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCommandService.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationMapper conversationMapper;
    private final WorkflowContextProvider workflowContextProvider;
    private final UserChatDefaultsService userChatDefaultsService;
    // Self-reference so we can call {@link #createConversationInNewTransaction} through the
    // Spring proxy. Without the proxy the class-level {@code @Transactional} short-circuits
    // and the inner REQUIRES_NEW propagation is silently ignored - the whole point of the
    // retry-on-conflict path. {@code @Lazy} breaks the constructor self-reference cycle.
    private final ConversationCommandService self;

    public ConversationCommandService(ConversationRepository conversationRepository,
                                      MessageRepository messageRepository,
                                      ConversationMapper conversationMapper,
                                      WorkflowContextProvider workflowContextProvider,
                                      UserChatDefaultsService userChatDefaultsService,
                                      @Lazy ConversationCommandService self) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationMapper = conversationMapper;
        this.workflowContextProvider = workflowContextProvider;
        this.userChatDefaultsService = userChatDefaultsService;
        this.self = self;
    }

    public ConversationDto createConversation(ConversationDto conversationDto) {
        logger.info("Creating conversation for user: {}", conversationDto.getUserId());

        // Funnel any "primary agent conversation" DTO (agentId set, not a sub-agent,
        // memory on) through the hardened createAgentConversation path so the POST
        // /api/conversations endpoint cannot race around the V115 unique index. Sub-agent
        // (parentConversationId != null) and memory-off (memoryEnabled == FALSE) rows are
        // legitimate N:1 cases and stay on the direct persistence path.
        if (isPrimaryAgentShape(conversationDto)) {
            // PR21 R2 - thread organizationId into the funnel. Pre-R2 this called the
            // 5-arg back-compat shim which discards orgId and defaults to personal scope -
            // a team-workspace user hitting POST /api/conversations with {agentId, ...}
            // ended up with organization_id=NULL despite having sent X-Organization-ID,
            // invisible to subsequent org-sidebar reads. Reviewer A+B round-1 caught this.
            return createAgentConversation(
                    conversationDto.getUserId(),
                    conversationDto.getOrganizationId(),
                    conversationDto.getAgentId(),
                    conversationDto.getModel(),
                    conversationDto.getProvider(),
                    conversationDto.getTitle());
        }

        return persistConversation(conversationDto);
    }

    /**
     * Raw persistence path. Validates, maps DTO → entity, and inserts. Does NOT apply the
     * primary-agent guard - callers that already did the find-or-create dance (i.e.
     * {@link #createAgentConversation}) must use this directly to avoid recursion.
     */
    private ConversationDto persistConversation(ConversationDto conversationDto) {
        // Same GuardOverrides range check as updateConversation - keeps the two write paths
        // aligned so a bad turnLimit cannot slip in at creation time.
        validateChatConfig(conversationDto.getChatConfig());

        Conversation conversation = new Conversation(
                conversationDto.getUserId(),
                conversationDto.getTitle(),
                conversationDto.getModel(),
                conversationDto.getProvider()
        );
        // Post-V261 - workspace identity captured by the controller from X-Organization-ID
        // and threaded onto the DTO. Always set: personal-workspace users carry their
        // personal org UUID (resolved from auth.organization_member.is_default=true);
        // team-workspace users carry the active org. Strict-isolation contract:
        // readers see organization_id = :orgId only.
        TenantResolver.requireOrgId(conversationDto.getOrganizationId());
        conversation.setOrganizationId(conversationDto.getOrganizationId());
        conversation.setActive(conversationDto.getActive());
        conversation.setWorkflowId(conversationDto.getWorkflowId());
        conversation.setAgentId(conversationDto.getAgentId());
        conversation.setParentConversationId(conversationDto.getParentConversationId());
        // Propagate the DTO's memoryEnabled. The entity defaults to TRUE, so without
        // this copy any caller asking for memoryEnabled=false (e.g. schedule with
        // with_memory=false, webhook with memory toggle off) silently lands a row
        // with memory_enabled=true → collides with the V212 partial unique index
        // uq_conversations_primary_agent_per_user_workspace and returns 500.
        if (conversationDto.getMemoryEnabled() != null) {
            conversation.setMemoryEnabled(conversationDto.getMemoryEnabled());
        }
        conversation.setChatConfig(conversationDto.getChatConfig());
        conversation.setUpdatedAt(LocalDateTime.now());

        Conversation savedConversation = conversationRepository.save(conversation);
        logger.info("Created conversation with ID: {} and updatedAt: {}", savedConversation.getId(), savedConversation.getUpdatedAt());

        return conversationMapper.toDto(savedConversation);
    }

    private boolean isPrimaryAgentShape(ConversationDto conversationDto) {
        String agentId = conversationDto.getAgentId();
        return agentId != null
                && !agentId.isBlank()
                && conversationDto.getParentConversationId() == null
                && !Boolean.FALSE.equals(conversationDto.getMemoryEnabled());
    }

    /**
     * <b>Internal only - do not call from controllers.</b> Used by
     * {@link #createAgentConversation} to run the INSERT inside a fresh transaction so
     * the V115 unique-index violation surfaces synchronously at the call site instead of
     * being deferred to outer-commit (where the catch block has already returned).
     *
     * <p>Skips the primary-agent-shape guard that {@code createConversation} applies -
     * the caller already resolved the idempotency check, so re-entering the guard here
     * would loop. Public visibility is required for the Spring proxy to intercept
     * {@code REQUIRES_NEW}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConversationDto createConversationInNewTransaction(ConversationDto conversationDto) {
        return persistConversation(conversationDto);
    }

    /**
     * Create a new conversation for a specific workflow.
     * The conversation title is set to the provided title, or dynamically fetched from workflow name.
     *
     * <p>Post-V261 - {@code organizationId} is mandatory (personal workspace = personal
     * org UUID, team workspace = active org). Strict-isolation: subsequent reads route
     * on this column, so an unset value would create an unreachable row.
     */
    public ConversationDto createWorkflowConversation(String userId, String organizationId,
                                                       String workflowId, String model, String provider, String title) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("Creating workflow conversation for user: {} (org: {}) and workflow: {} with title: {}",
                userId, organizationId, workflowId, title);

        if (workflowId != null && !workflowId.isBlank()) {
            Optional<Conversation> existing = conversationRepository
                    .findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(organizationId, workflowId);
            if (existing.isPresent()) {
                Conversation reuse = existing.get();
                logger.info("Reusing existing conversation {} for user {} (org {}) workflow {}",
                        reuse.getId(), userId, organizationId, workflowId);
                return conversationMapper.toDto(reuse);
            }
        }

        // Determine the title: use provided title, or fetch from workflow, or fallback
        String conversationTitle = title;
        if (conversationTitle == null || conversationTitle.isBlank()) {
            // Try to fetch workflow name
            if (workflowId != null && !workflowId.isBlank()) {
                conversationTitle = workflowContextProvider.getWorkflowName(workflowId, userId);
            }
        }
        if (conversationTitle == null || conversationTitle.isBlank()) {
            conversationTitle = "Workflow Chat";
        }

        ConversationDto newConversation = new ConversationDto();
        newConversation.setUserId(userId);
        newConversation.setOrganizationId(organizationId);
        newConversation.setTitle(conversationTitle);
        newConversation.setModel(model);
        newConversation.setProvider(provider);
        newConversation.setWorkflowId(workflowId);
        newConversation.setActive(true);
        // Seed the chat defaults the user set in Preferences (V312). A workflow-assistant
        // conversation has agentId == null, so AgentContextBuilder re-reads this stored
        // chat_config on every turn (systemPrompt / webSearch / toolsMode / defaultSkillIds /
        // temperature / turn limits ...). Without this seed the row starts with chat_config =
        // null and inherits none of the user's preferences, unlike a composer conversation.
        newConversation.setChatConfig(
                userChatDefaultsService.seedNewConversationConfig(userId, organizationId, null));

        try {
            return self != null
                    ? self.createConversationInNewTransaction(newConversation)
                    : persistConversation(newConversation);
        } catch (DataIntegrityViolationException e) {
            if (workflowId != null && !workflowId.isBlank()) {
                Optional<Conversation> winner = conversationRepository
                        .findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(organizationId, workflowId);
                if (winner.isPresent()) {
                    logger.info("Lost race for user {} (org {}) workflow {}; returning winner {}",
                            userId, organizationId, workflowId, winner.get().getId());
                    return conversationMapper.toDto(winner.get());
                }
            }
            throw e;
        }
    }

    /**
     * Find-or-create THE conversation for a specific agent.
     *
     * <p><b>Contract: one agent = one conversation.</b> This call is idempotent:
     * if an active conversation already exists for (userId, agentId), we return
     * that one instead of creating a second one. This mirrors
     * {@code ConversationClient.findOrCreateAgentConversation} on the client
     * side and guarantees the invariant even when callers hit this endpoint
     * directly.
     *
     * <p>When legacy duplicates exist in the table (pre-dedup data), we return
     * the oldest row by {@code createdAt} so the caller sees a stable
     * conversationId across retries.
     */
    /**
     * Post-V261 - create-or-reuse an agent-bound conversation in the given workspace.
     * The idempotency check is scoped by org (mandatory): every member of the same
     * workspace who chats with the same agent converges on the same shared
     * conversation row. Personal-workspace users have their own personal org UUID
     * so the same contract gives them a per-user-per-agent row.
     */
    public ConversationDto createAgentConversation(String userId, String organizationId,
                                                    String agentId, String model, String provider, String title) {
        TenantResolver.requireOrgId(organizationId);
        // Idempotent: reuse existing agent conversation if any (one agent = one conversation in this workspace).
        if (agentId != null && !agentId.isBlank()) {
            java.util.List<Conversation> existing = conversationRepository
                    .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(organizationId, agentId);
            if (!existing.isEmpty()) {
                Conversation reuse = existing.get(0);
                logger.info("Reusing existing conversation {} for user {} (org {}) agent {} (one-agent-one-conversation)",
                        reuse.getId(), userId, organizationId, agentId);
                return conversationMapper.toDto(reuse);
            }
        }

        logger.info("Creating agent conversation for user: {} and agent: {} with title: {}", userId, agentId, title);

        // Determine the title: use provided title, or fetch from agent, or fallback
        String conversationTitle = title;
        if (conversationTitle == null || conversationTitle.isBlank()) {
            if (agentId != null && !agentId.isBlank()) {
                conversationTitle = workflowContextProvider.getAgentName(agentId, userId);
            }
        }
        if (conversationTitle == null || conversationTitle.isBlank()) {
            conversationTitle = "Agent Chat";
        }

        ConversationDto newConversation = new ConversationDto();
        newConversation.setUserId(userId);
        newConversation.setOrganizationId(organizationId);
        newConversation.setTitle(conversationTitle);
        newConversation.setModel(model);
        newConversation.setProvider(provider);
        newConversation.setAgentId(agentId);
        newConversation.setActive(true);

        try {
            // Go through the Spring proxy so REQUIRES_NEW applies - the INSERT must flush
            // and commit inside the inner transaction for the unique-index violation to
            // be observable here rather than at outer-commit time.
            return self.createConversationInNewTransaction(newConversation);
        } catch (DataIntegrityViolationException e) {
            // Partial unique index uq_conversations_primary_agent_per_user (V115) fired:
            // a concurrent caller won the race and already inserted the primary row.
            // Re-fetch and return the winner so both callers converge on the same id.
            // The outer transaction is still clean (the failed insert happened in the
            // suspended inner tx), so this read runs without 25P02 errors.
            if (agentId != null && !agentId.isBlank()) {
                // Scope the race-loser re-fetch to the same workspace we tried
                // to insert into. Post-V261 every workspace (incl. personal) has a
                // non-null org UUID so the strict-org finder covers both cases.
                java.util.List<Conversation> winners = conversationRepository
                        .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(organizationId, agentId);
                if (!winners.isEmpty()) {
                    Conversation winner = winners.get(0);
                    logger.info("Lost race for user {} (org {}) agent {}; returning winner {}",
                            userId, organizationId, agentId, winner.getId());
                    return conversationMapper.toDto(winner);
                }
            }
            throw e;
        }
    }

    public ConversationDto updateConversation(String conversationId, ConversationDto conversationDto) {
        logger.info("Updating conversation: {}", conversationId);

        // Fail-fast on invalid turn-limit overrides inside chatConfig. Shares the range
        // rules with agent-scope writes via com.apimarketplace.agent.config.GuardOverrides
        // so the two write paths (PUT /agents, PUT /conversations) cannot drift.
        validateChatConfig(conversationDto.getChatConfig());

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        conversationMapper.updateEntity(conversationDto, conversation);
        Conversation savedConversation = conversationRepository.save(conversation);

        return conversationMapper.toDto(savedConversation);
    }

    /**
     * Validate the optional {@code chatConfig.turnLimits} block on a conversation write.
     * Accepts: absent, empty, or a map whose keys are a subset of
     * {@link com.apimarketplace.agent.config.GuardOverrides#KEYS} with nullable / integer
     * values. Rejects non-numeric values (so a typo like {@code "15 "} surfaces as a 400
     * instead of silently overwriting a valid override).
     *
     * <p>A {@code null} value clears the per-conversation override → the downstream
     * {@code AgentContextBuilder} treats it as "no override" and falls back to the
     * YAML default (same semantic as V100 NULL columns on the agent scope).
     */
    @SuppressWarnings("unchecked")
    private void validateChatConfig(java.util.Map<String, Object> chatConfig) {
        if (chatConfig == null || chatConfig.isEmpty()) return;
        Object tlRaw = chatConfig.get("turnLimits");
        if (tlRaw == null) return;
        if (!(tlRaw instanceof java.util.Map<?, ?> turnLimitsMap)) {
            throw new IllegalArgumentException("chatConfig.turnLimits must be a JSON object");
        }
        java.util.Map<String, Integer> parsed = new java.util.HashMap<>();
        for (java.util.Map.Entry<?, ?> e : turnLimitsMap.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v == null) {
                parsed.put(key, null);
                continue;
            }
            if (v instanceof Number n) {
                parsed.put(key, n.intValue());
                continue;
            }
            throw new IllegalArgumentException(
                key + " must be an integer or null, got " + v.getClass().getSimpleName() + ": " + v);
        }
        com.apimarketplace.agent.config.GuardOverrides.validate(parsed);
    }

    public boolean conversationExists(String conversationId) {
        return conversationRepository.existsById(conversationId);
    }

    public void deleteConversation(String conversationId) {
        logger.info("Soft deleting conversation: {}", conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        conversation.setActive(false);
        conversationRepository.save(conversation);
    }

    public void permanentlyDeleteConversation(String conversationId) {
        logger.info("Permanently deleting conversation: {}", conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
    }

    /**
     * Soft delete all conversations associated with a workflow.
     * Called when a workflow is deleted to cascade the deletion.
     *
     * <p>Back-compat: defaults to personal scope (orgId=null). Phase 5b callers
     * should prefer the 3-arg overload below to honour the active workspace.
     *
     * @return the number of conversations deleted
     */
    public int deleteConversationsByWorkflowId(String workflowId, String userId) {
        return deleteConversationsByWorkflowId(workflowId, userId, null);
    }

    /**
     * Workspace-scoped soft delete (2026-05-18). Filters the cascade so a
     * caller currently in OrgA workspace cannot delete their personal
     * workflow's conversations (and vice versa). When {@code orgId} is set,
     * only rows tagged with that org are deleted; when null/blank, only the
     * caller's personal rows (org=NULL).
     */
    public int deleteConversationsByWorkflowId(String workflowId, String userId, String orgId) {
        logger.info("Soft deleting conversations for workflow: {} by user: {} (org: {})",
                workflowId, userId, orgId);

        var conversations = conversationRepository.findByWorkflowIdAndUserId(workflowId, userId);
        int count = 0;

        for (Conversation conversation : conversations) {
            if (!conversation.getActive()) continue;
            // Per-row strict-isolation gate: cascade only affects rows visible to
            // the caller's active workspace. Mirrors the row-level check we use
            // in MessageService.isConversationInScope.
            if (!com.apimarketplace.common.scope.ScopeGuard.isInStrictScope(
                    userId, orgId, conversation.getUserId(), conversation.getOrganizationId())) {
                continue;
            }
            conversation.setActive(false);
            conversationRepository.save(conversation);
            count++;
            logger.info("Soft deleted conversation {} for workflow {}", conversation.getId(), workflowId);
        }

        logger.info("Soft deleted {} conversations for workflow {} by user {} (org {})",
                count, workflowId, userId, orgId);
        return count;
    }

    /**
     * Soft delete all conversations associated with an agent, scoped to the tenant.
     * Called when an agent is deleted to cascade the deletion.
     * @param agentId the agent ID
     * @param userId the tenant/user ID for isolation
     * @return the number of conversations deleted
     */
    /**
     * Clear all messages from a conversation without deleting the conversation itself.
     * Used for agent conversations where the conversation shell should persist.
     */
    public void clearConversationMessages(String conversationId) {
        logger.info("Clearing all messages for conversation: {}", conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        int deletedMessages = messageRepository.deleteByConversationId(conversationId);

        // The conversation is managed in this transaction. Calling save() here
        // would merge the @OneToMany message collection after the bulk delete
        // and can re-process deleted Message instances through cascade=ALL.
        conversation.setUpdatedAt(LocalDateTime.now());
        logger.info("Cleared {} messages for conversation: {}", deletedMessages, conversationId);
    }

    public int deleteConversationsByAgentId(String agentId, String userId) {
        return deleteConversationsByAgentId(agentId, userId, null);
    }

    /**
     * Workspace-scoped soft delete (2026-05-18). See
     * {@link #deleteConversationsByWorkflowId(String, String, String)} for the
     * strict-isolation contract.
     */
    public int deleteConversationsByAgentId(String agentId, String userId, String orgId) {
        logger.info("Soft deleting conversations for agent: {} by user: {} (org: {})",
                agentId, userId, orgId);

        var conversations = conversationRepository.findByAgentIdAndUserId(agentId, userId);
        int count = 0;

        for (Conversation conversation : conversations) {
            if (!conversation.getActive()) continue;
            if (!com.apimarketplace.common.scope.ScopeGuard.isInStrictScope(
                    userId, orgId, conversation.getUserId(), conversation.getOrganizationId())) {
                continue;
            }
            conversation.setActive(false);
            conversationRepository.save(conversation);
            count++;
            logger.info("Soft deleted conversation {} for agent {}", conversation.getId(), agentId);
        }

        logger.info("Soft deleted {} conversations for agent {} by user {} (org {})",
                count, agentId, userId, orgId);
        return count;
    }
}
