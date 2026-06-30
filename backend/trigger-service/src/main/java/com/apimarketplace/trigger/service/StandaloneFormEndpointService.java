package com.apimarketplace.trigger.service;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest;
import com.apimarketplace.trigger.client.dto.FormSubmissionLogDto;
import com.apimarketplace.trigger.client.dto.EndpointConfigDto;
import com.apimarketplace.trigger.domain.FormSubmissionLogEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.repository.FormSubmissionLogRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing standalone form endpoints.
 *
 * <p>Post-V261: every row carries a non-null {@code organization_id}.
 * Personal-scope (IsNull) finders are gone; all entry points require
 * a non-blank {@code organizationId}.
 */
@Service
public class StandaloneFormEndpointService {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneFormEndpointService.class);

    private final StandaloneFormEndpointRepository formEndpointRepository;
    private final FormSubmissionLogRepository submissionLogRepository;
    private final PlanLimitHelper planLimitHelper;

    @Autowired(required = false)
    private PublicationClient publicationClient;

    @Value("${orchestrator.form-endpoint.base-url:http://localhost:8080}")
    private String baseUrl;

    public StandaloneFormEndpointService(StandaloneFormEndpointRepository formEndpointRepository,
                                         FormSubmissionLogRepository submissionLogRepository,
                                         PlanLimitHelper planLimitHelper) {
        this.formEndpointRepository = formEndpointRepository;
        this.submissionLogRepository = submissionLogRepository;
        this.planLimitHelper = planLimitHelper;
    }

    /** Create a form endpoint in the given organization workspace. */
    @Transactional
    public StandaloneFormEndpointDto create(String tenantId, String organizationId,
                                             String userPlan, StandaloneFormEndpointRequest request) {
        TenantResolver.requireOrgId(organizationId);
        // Dedup: if sourceNodeId provided, return existing record.
        if (request.sourceNodeId() != null && !request.sourceNodeId().isBlank()) {
            Optional<StandaloneFormEndpointEntity> existing =
                    formEndpointRepository.findByOrganizationIdStrictAndSourceNodeId(organizationId, request.sourceNodeId());
            if (existing.isPresent()) {
                return toDto(existing.get());
            }
        }

        // Quota counts only linked endpoints - orphans are reaped by
        // StandaloneTriggerReaperService and must not eat the user's slots.
        long currentCount = formEndpointRepository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(organizationId);
        planLimitHelper.checkLimit(userPlan, currentCount);

        StandaloneFormEndpointEntity entity = new StandaloneFormEndpointEntity();
        entity.setTenantId(tenantId);
        entity.setOrganizationId(organizationId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setToken(StandaloneEndpointTokenGenerator.generateFormToken());
        if (request.workflowId() != null) {
            entity.setWorkflowId(UUID.fromString(request.workflowId()));
        }
        entity.setWorkflowName(request.workflowName());
        entity.setFormConfig(request.formConfig());
        entity.setSuccessMessage(request.successMessage());
        entity.setSourceNodeId(request.sourceNodeId());
        entity.setTriggerId(request.triggerId());

        StandaloneFormEndpointEntity saved = formEndpointRepository.save(entity);
        logger.info("Created standalone form endpoint '{}' for tenant {} org {}",
                saved.getName(), tenantId, organizationId);

        registerSharedLink(tenantId, saved);

        return toDto(saved);
    }

    /** Strict-isolation update. */
    @Transactional
    public StandaloneFormEndpointDto update(String tenantId, String organizationId, UUID endpointId, StandaloneFormEndpointRequest request) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneFormEndpointEntity entity = formEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Form endpoint not found"));

        entity.setName(request.name());
        entity.setDescription(request.description());
        if (request.workflowId() != null) {
            UUID requestedWorkflowId = UUID.fromString(request.workflowId());
            assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), requestedWorkflowId);
            entity.setWorkflowId(requestedWorkflowId);
        }
        entity.setWorkflowName(request.workflowName());
        entity.setFormConfig(request.formConfig());
        entity.setSuccessMessage(request.successMessage());
        if (request.triggerId() != null) {
            entity.setTriggerId(request.triggerId());
        }

        StandaloneFormEndpointEntity saved = formEndpointRepository.save(entity);
        logger.info("Updated standalone form endpoint '{}' ({})", saved.getName(), endpointId);

        return toDto(saved);
    }

    /** Strict-isolation delete. */
    @Transactional
    public void delete(String tenantId, String organizationId, UUID endpointId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneFormEndpointEntity entity = formEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Form endpoint not found"));

        unregisterSharedLink(entity.getToken());
        formEndpointRepository.delete(entity);
        logger.info("Deleted standalone form endpoint '{}' ({})", entity.getName(), endpointId);
    }

    /** Strict-isolation list. */
    public List<StandaloneFormEndpointDto> getAll(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        List<StandaloneFormEndpointEntity> rows =
                formEndpointRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(organizationId);
        return rows.stream().map(this::toDto).toList();
    }

    /** Strict-isolation single fetch. */
    public StandaloneFormEndpointDto getById(String tenantId, String organizationId, UUID endpointId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneFormEndpointEntity entity = formEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Form endpoint not found"));
        return toDto(entity);
    }

    /** Strict-isolation regenerate. */
    @Transactional
    public StandaloneFormEndpointDto regenerateToken(String tenantId, String organizationId, UUID endpointId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneFormEndpointEntity entity = formEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Form endpoint not found"));

        String oldToken = entity.getToken();
        unregisterSharedLink(oldToken);

        entity.setToken(StandaloneEndpointTokenGenerator.generateFormToken());

        StandaloneFormEndpointEntity saved = formEndpointRepository.save(entity);
        logger.info("Regenerated token for form endpoint '{}' ({})", saved.getName(), endpointId);

        registerSharedLink(tenantId, saved);

        return toDto(saved);
    }

    /** Strict-isolation submission-log read (auth-check). */
    public Page<FormSubmissionLogDto> getSubmissionLogs(String tenantId, String organizationId, UUID endpointId, int page, int size) {
        TenantResolver.requireOrgId(organizationId);
        formEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Form endpoint not found"));

        return submissionLogRepository.findByFormEndpointIdOrderBySubmittedAtDesc(endpointId, PageRequest.of(page, size))
                .map(this::toSubmissionLogDto);
    }

    public EndpointConfigDto getConfig(String tenantId, String organizationId, String userPlan) {
        TenantResolver.requireOrgId(organizationId);
        int maxPerUser = planLimitHelper.getMaxEndpoints(userPlan);
        long count = formEndpointRepository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(organizationId);
        return new EndpointConfigDto(maxPerUser, count);
    }

    /** Strict-isolation update-workflow. */
    @Transactional
    public StandaloneFormEndpointDto updateWorkflowReference(String tenantId, String organizationId, UUID endpointId,
                                                              UUID workflowId, String workflowName) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneFormEndpointEntity entity = formEndpointRepository.findByIdAndOrganizationIdStrict(endpointId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Form endpoint not found"));

        assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), workflowId);
        entity.setWorkflowId(workflowId);
        entity.setWorkflowName(workflowName);

        StandaloneFormEndpointEntity saved = formEndpointRepository.save(entity);
        return toDto(saved);
    }

    private void assertWorkflowReferenceMutationAllowed(UUID currentWorkflowId, UUID requestedWorkflowId) {
        if (currentWorkflowId != null && !Objects.equals(currentWorkflowId, requestedWorkflowId)) {
            throw new WorkflowReferenceImmutableException(
                    "workflowId is immutable on standalone form endpoints; delete and recreate the endpoint to change it");
        }
    }

    public Optional<StandaloneFormEndpointDto> findByToken(String token) {
        return formEndpointRepository.findByToken(token).map(this::toDto);
    }

    @Transactional
    public void logSubmission(UUID formEndpointId, Map<String, Object> submissionData,
                              String status, int workflowsTriggered, String ipAddress) {
        FormSubmissionLogEntity log = new FormSubmissionLogEntity();
        log.setFormEndpointId(formEndpointId);
        log.setSubmissionData(submissionData);
        log.setResponseStatus(status);
        log.setWorkflowsTriggered(workflowsTriggered);
        log.setIpAddress(ipAddress);
        submissionLogRepository.save(log);
    }

    @Transactional
    public void syncTriggerId(UUID workflowId, String triggerId) {
        List<StandaloneFormEndpointEntity> endpoints = formEndpointRepository.findByWorkflowId(workflowId);
        for (StandaloneFormEndpointEntity ep : endpoints) {
            if (!java.util.Objects.equals(ep.getTriggerId(), triggerId)) {
                ep.setTriggerId(triggerId);
                formEndpointRepository.save(ep);
            }
        }
    }

    private StandaloneFormEndpointDto toDto(StandaloneFormEndpointEntity entity) {
        StandaloneFormEndpointDto dto = new StandaloneFormEndpointDto();
        dto.setId(entity.getId());
        // Carry workspace identity to orchestrator's FormDispatchService so it
        // can scope-check before firing a workflow_run.
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setToken(entity.getToken());
        dto.setFormUrl(baseUrl + "/form/" + entity.getToken());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setWorkflowName(entity.getWorkflowName());
        dto.setFormConfig(entity.getFormConfig());
        dto.setSuccessMessage(entity.getSuccessMessage());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setSourceNodeId(entity.getSourceNodeId());
        dto.setTriggerId(entity.getTriggerId());
        return dto;
    }

    private FormSubmissionLogDto toSubmissionLogDto(FormSubmissionLogEntity entity) {
        FormSubmissionLogDto dto = new FormSubmissionLogDto();
        dto.setId(entity.getId());
        dto.setSubmissionData(entity.getSubmissionData());
        dto.setResponseStatus(entity.getResponseStatus());
        dto.setWorkflowsTriggered(entity.getWorkflowsTriggered());
        dto.setIpAddress(entity.getIpAddress());
        dto.setSubmittedAt(entity.getSubmittedAt());
        return dto;
    }

    private void registerSharedLink(String tenantId, StandaloneFormEndpointEntity entity) {
        if (publicationClient != null) {
            try {
                publicationClient.registerSharedLink(
                        tenantId, "FORM", entity.getToken(), entity.getId(),
                        entity.getName(), entity.getDescription());
            } catch (Exception e) {
                logger.warn("Failed to register shared link for form endpoint '{}': {}",
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
