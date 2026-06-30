package com.apimarketplace.trigger.service;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest;
import com.apimarketplace.trigger.client.dto.WebhookCallLogDto;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.domain.WebhookCallLogEntity;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.repository.WebhookCallLogRepository;
import com.apimarketplace.trigger.client.webhook.WebhookConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing standalone webhooks.
 *
 * <p>Post-V261: every row carries a non-null {@code organization_id} (gateway
 * always injects {@code X-Organization-ID}; personal-workspace users get their
 * personal org UUID). All entry points require a non-blank {@code organizationId}
 * and route through {@code *OrganizationIdStrict} finders. The legacy
 * {@code (orgId blank ? IsNull : Strict)} ternary and the back-compat 2-arg
 * overloads were removed in the V261 sweep.
 */
@Service
public class StandaloneWebhookService {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneWebhookService.class);

    private final StandaloneWebhookRepository webhookRepository;
    private final WebhookCallLogRepository callLogRepository;
    private final CredentialEncryptionService encryptionService;
    private final PlanLimitHelper planLimitHelper;

    @Value("${orchestrator.webhook.base-url:http://localhost:8080}")
    private String baseUrl;

    public StandaloneWebhookService(StandaloneWebhookRepository webhookRepository,
                                    WebhookCallLogRepository callLogRepository,
                                    CredentialEncryptionService encryptionService,
                                    PlanLimitHelper planLimitHelper) {
        this.webhookRepository = webhookRepository;
        this.callLogRepository = callLogRepository;
        this.encryptionService = encryptionService;
        this.planLimitHelper = planLimitHelper;
    }

    /**
     * Create a webhook in the given organization workspace.
     * The fire path (POST /webhook/{token}) does NOT carry the org header
     * (anonymous); orchestrator's WebhookDispatchService reads the stored
     * organization_id on token lookup and stamps it onto the created
     * workflow_run.
     */
    @Transactional
    public StandaloneWebhookDto create(String tenantId, String organizationId,
                                        String userPlan, StandaloneWebhookRequest request) {
        TenantResolver.requireOrgId(organizationId);
        // Dedup FIRST - so a refresh at-limit returns the existing row instead
        // of 400 "limit reached". Plan check only applies to genuinely new rows.
        if (request.sourceNodeId() != null && !request.sourceNodeId().isBlank()) {
            Optional<StandaloneWebhookEntity> existing =
                    webhookRepository.findByOrganizationIdStrictAndSourceNodeId(organizationId, request.sourceNodeId());
            if (existing.isPresent()) {
                return toDto(existing.get());
            }
        }

        // Plan limit - counts only linked webhooks; orphans are reaped after 24h.
        long currentCount = webhookRepository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(organizationId);
        planLimitHelper.checkLimit(userPlan, currentCount);

        StandaloneWebhookEntity entity = new StandaloneWebhookEntity();
        entity.setTenantId(tenantId);
        entity.setOrganizationId(organizationId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setToken(WebhookConfig.generateToken());
        entity.setHttpMethod(request.httpMethod() != null ? request.httpMethod() : "POST");
        entity.setAuthType(request.authType() != null ? request.authType() : "none");
        entity.setAuthConfig(encryptAuthConfig(request.authConfig()));

        if (request.workflowId() != null && !request.workflowId().isBlank()) {
            UUID requestedWorkflowId = UUID.fromString(request.workflowId());
            assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), requestedWorkflowId);
            entity.setWorkflowId(requestedWorkflowId);
        }
        if (request.workflowName() != null) {
            entity.setWorkflowName(request.workflowName());
        }

        entity.setSourceNodeId(request.sourceNodeId());
        StandaloneWebhookEntity saved = webhookRepository.save(entity);
        logger.info("Created standalone webhook '{}' for tenant {} org {}", saved.getName(), tenantId, organizationId);

        return toDto(saved);
    }

    /** Strict-isolation update. */
    @Transactional
    public StandaloneWebhookDto update(String tenantId, String organizationId, UUID webhookId, StandaloneWebhookRequest request) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneWebhookEntity entity = webhookRepository.findByIdAndOrganizationIdStrict(webhookId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found"));

        entity.setName(request.name());
        entity.setDescription(request.description());
        if (request.httpMethod() != null) entity.setHttpMethod(request.httpMethod());
        if (request.authType() != null) entity.setAuthType(request.authType());
        entity.setAuthConfig(encryptAuthConfig(request.authConfig()));

        if (request.workflowId() != null && !request.workflowId().isBlank()) {
            UUID requestedWorkflowId = UUID.fromString(request.workflowId());
            assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), requestedWorkflowId);
            entity.setWorkflowId(requestedWorkflowId);
        }
        if (request.workflowName() != null) {
            entity.setWorkflowName(request.workflowName());
        }

        StandaloneWebhookEntity saved = webhookRepository.save(entity);
        return toDto(saved);
    }

    /** Strict-isolation delete. */
    @Transactional
    public void delete(String tenantId, String organizationId, UUID webhookId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneWebhookEntity entity = webhookRepository.findByIdAndOrganizationIdStrict(webhookId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found"));
        webhookRepository.delete(entity);
    }

    /** Strict-isolation list - returns ALL webhooks tagged with the caller's org. */
    public List<StandaloneWebhookDto> getAll(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        List<StandaloneWebhookEntity> rows =
                webhookRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(organizationId);
        return rows.stream().map(this::toDto).toList();
    }

    /**
     * Strict-isolation single fetch. Cross-scope row → throws, controller maps to 404.
     */
    public StandaloneWebhookDto getById(String tenantId, String organizationId, UUID webhookId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneWebhookEntity entity = webhookRepository.findByIdAndOrganizationIdStrict(webhookId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found"));
        return toDto(entity);
    }

    /** Strict-isolation regenerate. */
    @Transactional
    public StandaloneWebhookDto regenerateToken(String tenantId, String organizationId, UUID webhookId) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneWebhookEntity entity = webhookRepository.findByIdAndOrganizationIdStrict(webhookId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found"));
        entity.setToken(WebhookConfig.generateToken());
        StandaloneWebhookEntity saved = webhookRepository.save(entity);
        return toDto(saved);
    }

    /** Strict-isolation log read (auth-check). */
    public Page<WebhookCallLogDto> getCallLogs(String tenantId, String organizationId, UUID webhookId, int page, int size) {
        TenantResolver.requireOrgId(organizationId);
        webhookRepository.findByIdAndOrganizationIdStrict(webhookId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found"));
        return callLogRepository.findByWebhookIdOrderByCalledAtDesc(webhookId, PageRequest.of(page, size))
                .map(this::toCallLogDto);
    }

    public Map<String, Object> getConfig(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        // Quota displays only linked webhooks - orphans are reaped by
        // StandaloneTriggerReaperService and must not eat the user's slots.
        long count = webhookRepository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(organizationId);
        return Map.of("currentCount", count);
    }

    /** Strict-isolation update-workflow. */
    @Transactional
    public StandaloneWebhookDto updateWorkflowReference(String tenantId, String organizationId, UUID webhookId,
                                                         UUID workflowId, String workflowName) {
        TenantResolver.requireOrgId(organizationId);
        StandaloneWebhookEntity entity = webhookRepository.findByIdAndOrganizationIdStrict(webhookId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found"));
        assertWorkflowReferenceMutationAllowed(entity.getWorkflowId(), workflowId);
        entity.setWorkflowId(workflowId);
        entity.setWorkflowName(workflowName);
        StandaloneWebhookEntity saved = webhookRepository.save(entity);
        return toDto(saved);
    }

    private void assertWorkflowReferenceMutationAllowed(UUID currentWorkflowId, UUID requestedWorkflowId) {
        if (currentWorkflowId != null && !Objects.equals(currentWorkflowId, requestedWorkflowId)) {
            throw new WorkflowReferenceImmutableException(
                    "workflowId is immutable on standalone webhooks; delete and recreate the endpoint to change it");
        }
    }

    public Optional<StandaloneWebhookEntity> findByToken(String token) {
        return webhookRepository.findByToken(token);
    }

    public List<StandaloneWebhookEntity> findByWorkflowId(UUID workflowId) {
        return webhookRepository.findByWorkflowId(workflowId);
    }

    @Transactional
    public void logCall(UUID webhookId, String method, Map<String, Object> headers,
                        Map<String, Object> payload, String status, int workflowsTriggered) {
        WebhookCallLogEntity log = new WebhookCallLogEntity();
        log.setWebhookId(webhookId);
        log.setRequestMethod(method);
        log.setRequestHeaders(headers);
        log.setRequestPayload(payload);
        log.setResponseStatus(status);
        log.setWorkflowsTriggered(workflowsTriggered);
        callLogRepository.save(log);
    }

    public Map<String, String> decryptAuthConfig(Map<String, String> authConfig) {
        if (authConfig == null) return null;
        Map<String, String> decrypted = new LinkedHashMap<>(authConfig);
        for (String key : List.of("basicPassword", "authHeaderValue", "jwtSecretKey")) {
            if (decrypted.containsKey(key) && decrypted.get(key) != null) {
                decrypted.put(key, encryptionService.decrypt(decrypted.get(key)));
            }
        }
        return decrypted;
    }

    private Map<String, String> encryptAuthConfig(Map<String, String> authConfig) {
        if (authConfig == null) return null;
        Map<String, String> encrypted = new LinkedHashMap<>(authConfig);
        for (String key : List.of("basicPassword", "authHeaderValue", "jwtSecretKey")) {
            if (encrypted.containsKey(key) && encrypted.get(key) != null) {
                encrypted.put(key, encryptionService.encrypt(encrypted.get(key)));
            }
        }
        return encrypted;
    }

    private StandaloneWebhookDto toDto(StandaloneWebhookEntity entity) {
        StandaloneWebhookDto dto = new StandaloneWebhookDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        // Surface the workspace tag so downstream consumers (orchestrator
        // WebhookDispatchService) can read it on token lookup and stamp the
        // workflow_run with the correct scope.
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setToken(entity.getToken());
        dto.setWebhookUrl(baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + "/webhook/" + entity.getToken()
                : baseUrl + "/webhook/" + entity.getToken());
        dto.setHttpMethod(entity.getHttpMethod());
        dto.setAuthType(entity.getAuthType());
        // Redact secret-keyed values (basicPassword, jwtSecretKey, authHeaderValue, …)
        // before the DTO leaves the server. The auth config is encrypted-at-rest in
        // the DB but the encrypted ciphertext still leaks structure (which auth
        // method, which header name) and the encrypted blob itself; better to ship
        // "***" for the value and the key only.
        dto.setAuthConfig(com.apimarketplace.trigger.security.TriggerConfigRedactor
                .redactStringMap(entity.getAuthConfig()));
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setWorkflowName(entity.getWorkflowName());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setSourceNodeId(entity.getSourceNodeId());
        return dto;
    }

    private WebhookCallLogDto toCallLogDto(WebhookCallLogEntity entity) {
        WebhookCallLogDto dto = new WebhookCallLogDto();
        dto.setId(entity.getId());
        dto.setWebhookId(entity.getWebhookId());
        dto.setRequestMethod(entity.getRequestMethod());
        dto.setRequestPayload(entity.getRequestPayload());
        dto.setResponseStatus(entity.getResponseStatus());
        dto.setWorkflowsTriggered(entity.getWorkflowsTriggered());
        dto.setCalledAt(entity.getCalledAt());
        return dto;
    }
}
