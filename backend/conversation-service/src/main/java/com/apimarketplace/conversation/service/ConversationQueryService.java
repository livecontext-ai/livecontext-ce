package com.apimarketplace.conversation.service;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service de lecture des conversations avec pagination/recherche.
 */
@Service
@Transactional(readOnly = true)
public class ConversationQueryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationQueryService.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationMapper conversationMapper;
    private final WorkflowContextProvider workflowContextProvider;

    // Bounded in-memory cache for workflow/agent names (id -> CacheEntry)
    // Max 500 entries, TTL 10 minutes per entry
    private static final int MAX_CACHE_SIZE = 500;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes

    private record CacheEntry(String name, long createdAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }

    private final Map<String, CacheEntry> workflowNameCache = new ConcurrentHashMap<>();

    public ConversationQueryService(ConversationRepository conversationRepository,
                                    MessageRepository messageRepository,
                                    ConversationMapper conversationMapper,
                                    WorkflowContextProvider workflowContextProvider) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationMapper = conversationMapper;
        this.workflowContextProvider = workflowContextProvider;
    }

    /** Back-compat (pre-PR21) - kept for callers that haven't been migrated yet. */
    public Optional<ConversationDto> getConversationById(String conversationId, String userId) {
        return getConversationById(conversationId, userId, null);
    }

    /**
     * Strict-isolation single fetch. Post-V261 every conversation row has a
     * non-null {@code organization_id} (personal-workspace users get their
     * personal org UUID from {@code auth.organization_member.is_default=true}),
     * so the {@code organizationId} parameter is required. Cross-scope rows
     * return {@code Optional.empty} (controller maps to 404), closing the leak
     * in both directions.
     */
    public Optional<ConversationDto> getConversationById(String conversationId, String userId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("Getting conversation by ID: {} (org: {})", conversationId, organizationId);
        Optional<Conversation> conv =
                conversationRepository.findByIdAndOrganizationIdStrict(conversationId, organizationId);
        return conv.map(conversationMapper::toDto)
                .map(dto -> enrichWithAgentTitle(enrichWithWorkflowTitle(dto, userId), userId))
                .map(dto -> {
                    Map<String, String> previews = fetchFirstMessagePreviews(List.of(conversationId));
                    dto.setFirstMessagePreview(previews.get(conversationId));
                    return dto;
                });
    }

    /**
     * Strict-isolation sidebar listing. Post-V261 every conversation row has
     * a non-null {@code organization_id} - personal-workspace users get their
     * personal org UUID and read through the same finder as team workspaces.
     */
    public Page<ConversationDto> getConversationsByUserId(String userId, String organizationId,
                                                           int page, int size, boolean includeInactive) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("🔍 [SERVICE] Getting conversations for user: {} (org: {}), page: {}, size: {}, includeInactive: {}",
                userId, organizationId, page, size, includeInactive);

        Pageable pageable = PageRequest.of(page, size);
        Page<Conversation> conversations = includeInactive
                ? conversationRepository.findByOrganizationIdStrictOrderByUpdatedAtDesc(organizationId, pageable)
                : conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(organizationId, pageable);

        logger.info("✅ [SERVICE] Found {} conversations for user: {} (org: {}), page: {}, totalElements: {}, totalPages: {}",
                conversations.getContent().size(), userId, organizationId, page,
                conversations.getTotalElements(), conversations.getTotalPages());

        // Pre-cache workflow names for all conversations with workflowId
        List<ConversationDto> dtos = conversations.getContent().stream()
                .map(conversationMapper::toDto)
                .collect(Collectors.toList());
        enrichWithWorkflowTitles(dtos, userId);

        return enrichConversationPage(conversations, userId);
    }

    /** Strict-isolation title search. */
    public Page<ConversationDto> searchConversationsByTitle(String userId, String organizationId,
                                                              String searchTerm, int page, int size) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("Searching conversations by title for user: {} (org: {}), searchTerm: {}",
                userId, organizationId, searchTerm);
        Pageable pageable = PageRequest.of(page, size);
        Page<Conversation> result = conversationRepository.findByOrganizationIdStrictAndTitleContainingIgnoreCase(
                organizationId, searchTerm, pageable);
        return enrichConversationPage(result, userId);
    }

    /** Strict-isolation full-text message-content search. */
    public Page<ConversationDto> searchConversationsByContent(String userId, String organizationId,
                                                                String searchTerm, int page, int size) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("Searching conversations by content for user: {} (org: {}), searchTerm: {}",
                userId, organizationId, searchTerm);
        Pageable pageable = PageRequest.of(page, size);
        Page<Conversation> result = conversationRepository.findByOrganizationIdStrictAndMessageContentContaining(
                organizationId, searchTerm, pageable);
        return enrichConversationPage(result, userId);
    }

    /** Strict-isolation recent-conversations list. */
    public List<ConversationDto> getRecentConversations(String userId, String organizationId, int limit) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("Getting recent conversations for user: {} (org: {}), limit: {}", userId, organizationId, limit);
        Pageable pageable = PageRequest.of(0, limit);
        Page<Conversation> result = conversationRepository
                .findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(organizationId, pageable);
        List<ConversationDto> dtos = result.getContent().stream()
                .map(conversationMapper::toDto)
                .collect(Collectors.toList());
        dtos = enrichWithWorkflowTitles(dtos, userId);
        enrichDtosWithFirstMessagePreviews(dtos);
        return dtos;
    }

    /** Strict-isolation active conversation count. */
    public long getConversationCount(String userId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return conversationRepository.countByOrganizationIdStrictAndActiveTrue(organizationId);
    }

    /**
     * Strict-isolation workflow-bound conversation lookup.
     */
    public Optional<ConversationDto> findByUserIdAndWorkflowId(String userId, String organizationId, String workflowId) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("Finding conversation for user: {} (org: {}) and workflow: {}", userId, organizationId, workflowId);
        Optional<Conversation> result = conversationRepository
                .findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(organizationId, workflowId);
        return result.map(conv -> enrichWithWorkflowTitle(conversationMapper.toDto(conv), userId));
    }

    /**
     * Strict-isolation agent-bound conversation lookup.
     *
     * <p>Post-V261 every conversation lives in some org workspace (personal or
     * team). "One agent = one shared conversation per workspace": every caller
     * within the same org who chats with the same agent converges on the same
     * row.
     */
    public Optional<ConversationDto> findByUserIdAndAgentId(String userId, String organizationId, String agentId) {
        TenantResolver.requireOrgId(organizationId);
        logger.info("Finding conversation for user: {} (org: {}) and agent: {}", userId, organizationId, agentId);
        java.util.List<Conversation> hits = conversationRepository
                .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(organizationId, agentId);
        return hits.isEmpty()
                ? Optional.empty()
                : Optional.of(enrichWithAgentTitle(conversationMapper.toDto(hits.get(0)), userId));
    }

    /**
     * Enriches a conversation DTO with the workflow name as title.
     * For workflow-linked conversations, the title should be the workflow name (dynamic).
     *
     * @param dto The conversation DTO to enrich
     * @param userId The user ID for API authorization
     * @return The enriched DTO (same instance, modified in place)
     */
    private ConversationDto enrichWithWorkflowTitle(ConversationDto dto, String userId) {
        if (dto == null || dto.getWorkflowId() == null || dto.getWorkflowId().isBlank()) {
            return dto;
        }

        String workflowId = dto.getWorkflowId();
        String workflowName = getCachedWorkflowName(workflowId, userId, dto.getTitle());

        if (workflowName != null) {
            dto.setTitle(workflowName);
        }

        return dto;
    }

    private String getCachedWorkflowName(String workflowId, String userId, String fallback) {
        CacheEntry entry = workflowNameCache.get(workflowId);
        if (entry != null && !entry.isExpired()) {
            return entry.name();
        }

        // Evict expired entry
        if (entry != null) {
            workflowNameCache.remove(workflowId);
        }

        // Evict oldest entries if cache is full
        if (workflowNameCache.size() >= MAX_CACHE_SIZE) {
            evictOldestEntries();
        }

        String name = workflowContextProvider.getWorkflowName(workflowId, userId);
        String resolved = name != null ? name : fallback;
        if (resolved != null) {
            workflowNameCache.put(workflowId, new CacheEntry(resolved, System.currentTimeMillis()));
        }
        return resolved;
    }

    private void evictOldestEntries() {
        // Remove expired entries first, then oldest if still over limit
        workflowNameCache.entrySet().removeIf(e -> e.getValue().isExpired());
        if (workflowNameCache.size() >= MAX_CACHE_SIZE) {
            // Remove ~25% of entries (oldest by creation time)
            int toRemove = MAX_CACHE_SIZE / 4;
            Iterator<Map.Entry<String, CacheEntry>> it = workflowNameCache.entrySet().iterator();
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }

    /**
     * Enriches a list of conversation DTOs with workflow names.
     */
    private List<ConversationDto> enrichWithWorkflowTitles(List<ConversationDto> dtos, String userId) {
        // Pre-fetch workflow names not in cache (or expired)
        Set<String> workflowIds = dtos.stream()
            .map(ConversationDto::getWorkflowId)
            .filter(id -> id != null && !id.isBlank())
            .filter(id -> {
                CacheEntry entry = workflowNameCache.get(id);
                return entry == null || entry.isExpired();
            })
            .collect(Collectors.toSet());

        // Fetch missing workflow names
        for (String workflowId : workflowIds) {
            String name = workflowContextProvider.getWorkflowName(workflowId, userId);
            if (name != null) {
                workflowNameCache.put(workflowId, new CacheEntry(name, System.currentTimeMillis()));
            }
        }

        // Enrich all DTOs
        return dtos.stream()
            .map(dto -> enrichWithWorkflowTitle(dto, userId))
            .collect(Collectors.toList());
    }

    // ==================== AGENT TITLE ENRICHMENT ====================

    private final Map<String, CacheEntry> agentNameCache = new ConcurrentHashMap<>();

    /**
     * Enriches a conversation DTO with the agent name as title.
     * For agent-linked conversations, the title should be the agent name (dynamic).
     */
    private ConversationDto enrichWithAgentTitle(ConversationDto dto, String userId) {
        if (dto == null || dto.getAgentId() == null || dto.getAgentId().isBlank()) {
            return dto;
        }

        String agentId = dto.getAgentId();
        String agentName = getCachedAgentName(agentId, userId, dto.getTitle());

        if (agentName != null) {
            dto.setTitle(agentName);
        }

        return dto;
    }

    private String getCachedAgentName(String agentId, String userId, String fallback) {
        CacheEntry entry = agentNameCache.get(agentId);
        if (entry != null && !entry.isExpired()) {
            return entry.name();
        }

        if (entry != null) {
            agentNameCache.remove(agentId);
        }

        if (agentNameCache.size() >= MAX_CACHE_SIZE) {
            agentNameCache.entrySet().removeIf(e -> e.getValue().isExpired());
        }

        String name = workflowContextProvider.getAgentName(agentId, userId);
        String resolved = name != null ? name : fallback;
        if (resolved != null) {
            agentNameCache.put(agentId, new CacheEntry(resolved, System.currentTimeMillis()));
        }
        return resolved;
    }

    // ==================== FIRST MESSAGE PREVIEW ====================

    private Page<ConversationDto> enrichConversationPage(Page<Conversation> conversations, String userId) {
        Map<String, String> previews = fetchFirstMessagePreviews(
                conversations.getContent().stream().map(Conversation::getId).collect(Collectors.toList()));
        return conversations.map(conv -> {
            ConversationDto dto = conversationMapper.toDto(conv);
            dto = enrichWithWorkflowTitle(dto, userId);
            dto = enrichWithAgentTitle(dto, userId);
            dto.setFirstMessagePreview(previews.get(conv.getId()));
            return dto;
        });
    }

    private void enrichDtosWithFirstMessagePreviews(List<ConversationDto> dtos) {
        List<String> ids = dtos.stream().map(ConversationDto::getId).collect(Collectors.toList());
        Map<String, String> previews = fetchFirstMessagePreviews(ids);
        for (ConversationDto dto : dtos) {
            dto.setFirstMessagePreview(previews.get(dto.getId()));
        }
    }

    private Map<String, String> fetchFirstMessagePreviews(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        try {
            List<Object[]> rows = messageRepository.findFirstUserMessagePreviewBatch(conversationIds);
            for (Object[] row : rows) {
                String convId = (String) row[0];
                String preview = (String) row[1];
                if (convId != null && preview != null && !preview.isBlank()) {
                    result.put(convId, preview);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch first message previews: {}", e.getMessage());
        }
        return result;
    }

}
