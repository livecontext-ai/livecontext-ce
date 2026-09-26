package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.lifecycle.WorkflowActivationReporter;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import com.apimarketplace.orchestrator.webhook.WebhookIndexService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A NEW workflow is the account's lifecycle activation: every creation path reports it, an
 * update never does, and a failing reporter never fails the save.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowManagementService - lifecycle activation on workflow creation")
class WorkflowManagementServiceActivationTest {

    private static final String TENANT_ID = "42";

    @Mock private WorkflowRepository workflowRepository;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private StorageRepository storageRepository;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private TriggerClient triggerClient;
    @Mock private PublicationClient publicationClient;
    @Mock private WebhookIndexService webhookIndexService;
    @Mock private PinAwareTriggerSyncService pinAwareTriggerSyncService;
    @Mock private WorkflowActivationReporter activationReporter;

    private WorkflowManagementService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowManagementService();
        ReflectionTestUtils.setField(service, "workflowRepository", workflowRepository);
        ReflectionTestUtils.setField(service, "orgAccessService", orgAccessService);
        ReflectionTestUtils.setField(service, "breakdownService", breakdownService);
        ReflectionTestUtils.setField(service, "storageRepository", storageRepository);
        ReflectionTestUtils.setField(service, "entitlementGuard", entitlementGuard);
        ReflectionTestUtils.setField(service, "triggerClient", triggerClient);
        ReflectionTestUtils.setField(service, "publicationClient", publicationClient);
        ReflectionTestUtils.setField(service, "webhookIndexService", webhookIndexService);
        ReflectionTestUtils.setField(service, "pinAwareTriggerSyncService", pinAwareTriggerSyncService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "activationReporter", activationReporter);

        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(entitlementGuard).check(anyString(), any(ResourceType.class), any(LongSupplier.class));
    }

    private static Map<String, Object> planMap() {
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("name", "Test Workflow");
        planMap.put("triggers", new ArrayList<>());
        planMap.put("mcps", new ArrayList<>());
        planMap.put("cores", new ArrayList<>());
        planMap.put("edges", new ArrayList<>());
        return planMap;
    }

    @Test
    @DisplayName("a new draft reports the activation of its owner")
    void newDraftReports() {
        UUID id = UUID.randomUUID();
        when(workflowRepository.findById(id)).thenReturn(Optional.empty());

        service.saveDraft(planMap(), TENANT_ID, id, "ORG-1");

        verify(activationReporter).workflowCreated(TENANT_ID);
    }

    @Test
    @DisplayName("an update of an existing draft reports nothing")
    void draftUpdateDoesNotReport() {
        UUID id = UUID.randomUUID();
        WorkflowEntity existing = new WorkflowEntity();
        existing.setId(id);
        existing.setTenantId(TENANT_ID);
        when(workflowRepository.findById(id)).thenReturn(Optional.of(existing));

        service.saveDraft(planMap(), TENANT_ID, id, "ORG-1");

        verify(activationReporter, never()).workflowCreated(anyString());
    }

    @Test
    @DisplayName("a new workflow through the full save path reports the activation")
    void newFullSaveReports() {
        UUID id = UUID.randomUUID();
        when(workflowRepository.findById(id)).thenReturn(Optional.empty());
        WorkflowPlan plan = WorkflowPlan.fromMap(planMap(), id.toString(), TENANT_ID);

        service.saveWorkflow(plan, null, id, "ORG-1");

        verify(activationReporter).workflowCreated(TENANT_ID);
    }

    @Test
    @DisplayName("a clone is a new workflow of the cloner and reports the activation")
    void cloneReports() {
        UUID sourceId = UUID.randomUUID();
        WorkflowEntity source = new WorkflowEntity(TENANT_ID, "Source", TENANT_ID);
        source.setId(sourceId);
        source.setPlan(planMap());
        when(workflowRepository.findById(sourceId)).thenReturn(Optional.of(source));

        service.cloneWorkflow(sourceId, TENANT_ID);

        verify(activationReporter).workflowCreated(TENANT_ID);
    }

    @Test
    @DisplayName("a failing reporter never fails the save")
    void reporterFailureIsSwallowed() {
        UUID id = UUID.randomUUID();
        when(workflowRepository.findById(id)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("boom")).when(activationReporter).workflowCreated(anyString());

        WorkflowEntity saved = service.saveDraft(planMap(), TENANT_ID, id, "ORG-1");

        assertThat(saved).isNotNull();
    }
}
