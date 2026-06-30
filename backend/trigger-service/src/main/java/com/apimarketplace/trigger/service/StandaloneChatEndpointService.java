package com.apimarketplace.trigger.service;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointRequest;
import com.apimarketplace.trigger.client.dto.ChatEndpointAccessLogDto;
import com.apimarketplace.trigger.client.dto.EndpointConfigDto;
import com.apimarketplace.trigger.domain.ChatEndpointAccessLogEntity;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.repository.ChatEndpointAccessLogRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.publication.client.PublicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing standalone chat endpoints.
 *
 * <p>Post-V261: every row carries a non-null {@code organization_id}.
 * Personal-scope (IsNull) finders are gone; all entry points require
 * a non-blank {@code organizationId}.
 */
@Service
public class StandaloneChatEndpointService {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneChatEndpointService.class);

    private final StandaloneChatEndpointRepository chatEndpointRepository;
    private final ChatEndpointAccessLogRepository accessLogRepository;
    private final PlanLimitHelper planLimitHelper;

    @Autowired(required = false)
    private PublicationClient publicationClient;

    @Value("${orchestrator.chat-endpoint.base-url:http://localhost:8080}")
    private String baseUrl;

    public StandaloneChatEndpointService(StandaloneChatEndpointRepository chatEndpointRepository,
                                         ChatEndpointAccessLogRepository accessLogRepository,
                                         PlanLimitHelper planLimitHelper) {
        this.chatEndpointRepository = chatEndpointRepository;
        this.accessLogRepository = accessLogRepository;
        this.planLimitHelper = planLimitHelper;
    }

    /** Create a chat endpoint in the given organization workspace. */
    @Transactional
    public StandaloneChatEndpointDto create(String tenantId, String organizationId,
                                              String userPlan, StandaloneChatEndpointRequest request) {
        TenantResolver.requireOrgId(organizationId);
        // Dedup: if sourceNodeId provided, return existing record.
        if (request.sourceNodeId() != null && !request.sourceNodeId().isBlank()) {
            Optional<StandaloneChatEndpointEntity> existing =
                    chatEndpointRepository.findByOrganizationIdStrictAndSourceNodeId(organizationId, request.sourceNodeId());
            if (existing.isPresent()) {
                return toDto(existing.get());
            }
        }

        // Quota counts only linked endpoints - orphans are reaped by
        // StandaloneTriggerReaperService and must not eat the user's slots.
        long currentCount = chatEndpointRepository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(organizationId);
        planLimitHelper.checkLimit(userPlan, currentCount);

        StandaloneChatEndpointEntity entity = new StandaloneChatEndpointEntity();
        entity.setTenantId(tenantId);
        entity.setOrganizationId(organizationId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setToken(StandaloneEndpointTokenGenerator.generateChatToken());
        if (request.workflowId() != null) {
            entity.setWorkflowId(UUID.fromString(request.workflowId()));
        }
        entity.setWorkflowName(request.workflowName());
        entity.setWelcomeMessage(request.welcomeMessage());
        entity.setModel(request.model());
        entity.setProvider(request.provider());
        entity.setMemoryEnabled(request.memoryEnabled() != null ? request.memoryEnabled() : true);
        entity.setSourceNodeId(request.sourceNodeId());
        entity.setTriggerId(request.triggerId());

        StandaloneChatEndpointEntity saved = chatEndpointRepository.save(entity);
        logger.info("Created standalone chat endpoint '{}' for tenant {} org {}",
                saved.getName(), tenantId, organizationId);

        registerSharedLink(tenantId, saved);

        return toDto(saved);
    }

    /** Strict-isolation update. */
    @Transactional
    public StandaloneChatEndpointDto update(String tenantId, String organizationId, UUID endpointId, StandaloneChatEndpointRequest request) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneChatEndpointEntity entity = chatEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Chat endpoint not found"));

        entity.setName(request.name());
        entity.setDescription(request.description());
        if (request.workflowId() != null) {
            UUID requestedWorkflowId = UUID.fromString(request.workflowId());
            assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), requestedWorkflowId);
            entity.setWorkflowId(requestedWorkflowId);
        }
        entity.setWorkflowName(request.workflowName());
        entity.setWelcomeMessage(request.welcomeMessage());
        entity.setModel(request.model());
        entity.setProvider(request.provider());
        if (request.memoryEnabled() != null) {
            entity.setMemoryEnabled(request.memoryEnabled());
        }
        if (request.triggerId() != null) {
            entity.setTriggerId(request.triggerId());
        }

        StandaloneChatEndpointEntity saved = chatEndpointRepository.save(entity);
        logger.info("Updated standalone chat endpoint '{}' ({})", saved.getName(), endpointId);

        return toDto(saved);
    }

    /** Strict-isolation delete. */
    @Transactional
    public void delete(String tenantId, String organizationId, UUID endpointId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneChatEndpointEntity entity = chatEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Chat endpoint not found"));

        unregisterSharedLink(entity.getToken());
        chatEndpointRepository.delete(entity);
        logger.info("Deleted standalone chat endpoint '{}' ({})", entity.getName(), endpointId);
    }

    /** Strict-isolation list. */
    public List<StandaloneChatEndpointDto> getAll(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        List<StandaloneChatEndpointEntity> rows =
                chatEndpointRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(organizationId);
        return rows.stream().map(this::toDto).toList();
    }

    /** Strict-isolation single fetch. */
    public StandaloneChatEndpointDto getById(String tenantId, String organizationId, UUID endpointId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneChatEndpointEntity entity = chatEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Chat endpoint not found"));
        return toDto(entity);
    }

    /** Strict-isolation regenerate. */
    @Transactional
    public StandaloneChatEndpointDto regenerateToken(String tenantId, String organizationId, UUID endpointId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneChatEndpointEntity entity = chatEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Chat endpoint not found"));

        String oldToken = entity.getToken();
        unregisterSharedLink(oldToken);

        entity.setToken(StandaloneEndpointTokenGenerator.generateChatToken());

        StandaloneChatEndpointEntity saved = chatEndpointRepository.save(entity);
        logger.info("Regenerated token for chat endpoint '{}' ({})", saved.getName(), endpointId);

        registerSharedLink(tenantId, saved);

        return toDto(saved);
    }

    /** Strict-isolation access-log read (auth-check). */
    public Page<ChatEndpointAccessLogDto> getAccessLogs(String tenantId, String organizationId, UUID endpointId, int page, int size) {
        TenantResolver.requireOrgId(organizationId);
        chatEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Chat endpoint not found"));

        return accessLogRepository.findByChatEndpointIdOrderByAccessedAtDesc(endpointId, PageRequest.of(page, size))
                .map(this::toAccessLogDto);
    }

    public EndpointConfigDto getConfig(String tenantId, String organizationId, String userPlan) {
        TenantResolver.requireOrgId(organizationId);
        int maxPerUser = planLimitHelper.getMaxEndpoints(userPlan);
        long count = chatEndpointRepository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(organizationId);
        return new EndpointConfigDto(maxPerUser, count);
    }

    /** Strict-isolation update-workflow. */
    @Transactional
    public StandaloneChatEndpointDto updateWorkflowReference(String tenantId, String organizationId, UUID endpointId,
                                                              UUID workflowId, String workflowName) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneChatEndpointEntity entity = chatEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Chat endpoint not found"));

        assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), workflowId);
        entity.setWorkflowId(workflowId);
        entity.setWorkflowName(workflowName);

        StandaloneChatEndpointEntity saved = chatEndpointRepository.save(entity);
        return toDto(saved);
    }

    private void assertWorkflowReferenceMutationAllowed(UUID currentWorkflowId, UUID requestedWorkflowId) {
        if (currentWorkflowId != null && !Objects.equals(currentWorkflowId, requestedWorkflowId)) {
            throw new WorkflowReferenceImmutableException(
                    "workflowId is immutable on standalone chat endpoints; delete and recreate the endpoint to change it");
        }
    }

    public Optional<StandaloneChatEndpointDto> findByToken(String token) {
        return chatEndpointRepository.findByToken(token).map(this::toDto);
    }

    @Transactional
    public void logAccess(UUID chatEndpointId, String sessionId, String conversationId,
                          String action, String ipAddress) {
        ChatEndpointAccessLogEntity log = new ChatEndpointAccessLogEntity();
        log.setChatEndpointId(chatEndpointId);
        log.setSessionId(sessionId);
        log.setConversationId(conversationId);
        log.setAction(action);
        log.setIpAddress(ipAddress);
        accessLogRepository.save(log);
    }

    @Transactional
    public void syncTriggerId(UUID workflowId, String triggerId) {
        List<StandaloneChatEndpointEntity> endpoints = chatEndpointRepository.findByWorkflowId(workflowId);
        for (StandaloneChatEndpointEntity ep : endpoints) {
            if (!java.util.Objects.equals(ep.getTriggerId(), triggerId)) {
                ep.setTriggerId(triggerId);
                chatEndpointRepository.save(ep);
            }
        }
    }

    private StandaloneChatEndpointDto toDto(StandaloneChatEndpointEntity entity) {
        StandaloneChatEndpointDto dto = new StandaloneChatEndpointDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        // Carry workspace identity to orchestrator's ChatDispatchService so it
        // can scope-check before firing a workflow_run.
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setToken(entity.getToken());
        dto.setChatUrl(baseUrl + "/chat/" + entity.getToken());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setWorkflowName(entity.getWorkflowName());
        dto.setWelcomeMessage(entity.getWelcomeMessage());
        dto.setModel(entity.getModel());
        dto.setProvider(entity.getProvider());
        dto.setMemoryEnabled(entity.getMemoryEnabled());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setSourceNodeId(entity.getSourceNodeId());
        dto.setTriggerId(entity.getTriggerId());
        return dto;
    }

    private ChatEndpointAccessLogDto toAccessLogDto(ChatEndpointAccessLogEntity entity) {
        ChatEndpointAccessLogDto dto = new ChatEndpointAccessLogDto();
        dto.setId(entity.getId());
        dto.setSessionId(entity.getSessionId());
        dto.setConversationId(entity.getConversationId());
        dto.setAction(entity.getAction());
        dto.setIpAddress(entity.getIpAddress());
        dto.setAccessedAt(entity.getAccessedAt());
        return dto;
    }

    private void registerSharedLink(String tenantId, StandaloneChatEndpointEntity entity) {
        if (publicationClient != null) {
            try {
                publicationClient.registerSharedLink(
                        tenantId, "CHAT", entity.getToken(), entity.getId(),
                        entity.getName(), entity.getDescription());
            } catch (Exception e) {
                logger.warn("Failed to register shared link for chat endpoint '{}': {}",
                        entity.getName(), e.getMessage());
            }
        }
    }

    private void unregisterSharedLink(String token) {
        if (publicationClient != null) {
            try {
                publicationClient.unregisterSharedLink(token);
            } catch (Exception e) {
                logger.warn("Failed to unregister shared link for token={}: {}", token, e.getMessage());
            }
        }
    }
}
