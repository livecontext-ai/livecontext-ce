package com.apimarketplace.trigger.service;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.repository.ChatEndpointAccessLogRepository;
import com.apimarketplace.trigger.repository.FormSubmissionLogRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.repository.WebhookCallLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Standalone workflow reference immutability")
class StandaloneWorkflowReferenceImmutabilityTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";
    private static final UUID ENDPOINT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LINKED_WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_WORKFLOW_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    @DisplayName("webhook workflow patch rejects clearing an already linked workflow id before save")
    void webhookWorkflowPatchRejectsClearingLinkedWorkflowIdBeforeSave() {
        StandaloneWebhookRepository repository = mock(StandaloneWebhookRepository.class);
        StandaloneWebhookService service = new StandaloneWebhookService(
                repository,
                mock(WebhookCallLogRepository.class),
                mock(CredentialEncryptionService.class),
                mock(PlanLimitHelper.class));
        when(repository.findByIdAndOrganizationIdStrict(ENDPOINT_ID, ORG_ID))
                .thenReturn(Optional.of(linkedWebhook()));

        assertThatThrownBy(() -> service.updateWorkflowReference(TENANT_ID, ORG_ID, ENDPOINT_ID, null, null))
                .isInstanceOf(WorkflowReferenceImmutableException.class)
                .hasMessageContaining("workflowId is immutable");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("webhook full update rejects rebinding an already linked workflow id before save")
    void webhookFullUpdateRejectsRebindingLinkedWorkflowIdBeforeSave() {
        StandaloneWebhookRepository repository = mock(StandaloneWebhookRepository.class);
        StandaloneWebhookService service = new StandaloneWebhookService(
                repository,
                mock(WebhookCallLogRepository.class),
                mock(CredentialEncryptionService.class),
                mock(PlanLimitHelper.class));
        when(repository.findByIdAndOrganizationIdStrict(ENDPOINT_ID, ORG_ID))
                .thenReturn(Optional.of(linkedWebhook()));

        StandaloneWebhookRequest request = new StandaloneWebhookRequest(
                "Hook",
                null,
                "POST",
                "none",
                Map.of(),
                OTHER_WORKFLOW_ID.toString(),
                "Other workflow",
                null);

        assertThatThrownBy(() -> service.update(TENANT_ID, ORG_ID, ENDPOINT_ID, request))
                .isInstanceOf(WorkflowReferenceImmutableException.class)
                .hasMessageContaining("workflowId is immutable");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("chat endpoint workflow patch rejects clearing an already linked workflow id before save")
    void chatWorkflowPatchRejectsClearingLinkedWorkflowIdBeforeSave() {
        StandaloneChatEndpointRepository repository = mock(StandaloneChatEndpointRepository.class);
        StandaloneChatEndpointService service = new StandaloneChatEndpointService(
                repository,
                mock(ChatEndpointAccessLogRepository.class),
                mock(PlanLimitHelper.class));
        StandaloneChatEndpointEntity entity = new StandaloneChatEndpointEntity();
        entity.setId(ENDPOINT_ID);
        entity.setTenantId(TENANT_ID);
        entity.setOrganizationId(ORG_ID);
        entity.setName("Chat");
        entity.setWorkflowId(LINKED_WORKFLOW_ID);
        when(repository.findByIdAndOrganizationIdStrict(ENDPOINT_ID, ORG_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateWorkflowReference(TENANT_ID, ORG_ID, ENDPOINT_ID, null, null))
                .isInstanceOf(WorkflowReferenceImmutableException.class)
                .hasMessageContaining("workflowId is immutable");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("form endpoint workflow patch rejects clearing an already linked workflow id before save")
    void formWorkflowPatchRejectsClearingLinkedWorkflowIdBeforeSave() {
        StandaloneFormEndpointRepository repository = mock(StandaloneFormEndpointRepository.class);
        StandaloneFormEndpointService service = new StandaloneFormEndpointService(
                repository,
                mock(FormSubmissionLogRepository.class),
                mock(PlanLimitHelper.class));
        StandaloneFormEndpointEntity entity = new StandaloneFormEndpointEntity();
        entity.setId(ENDPOINT_ID);
        entity.setTenantId(TENANT_ID);
        entity.setOrganizationId(ORG_ID);
        entity.setName("Form");
        entity.setWorkflowId(LINKED_WORKFLOW_ID);
        when(repository.findByIdAndOrganizationIdStrict(ENDPOINT_ID, ORG_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateWorkflowReference(TENANT_ID, ORG_ID, ENDPOINT_ID, null, null))
                .isInstanceOf(WorkflowReferenceImmutableException.class)
                .hasMessageContaining("workflowId is immutable");

        verify(repository, never()).save(any());
    }

    private StandaloneWebhookEntity linkedWebhook() {
        StandaloneWebhookEntity entity = new StandaloneWebhookEntity();
        entity.setId(ENDPOINT_ID);
        entity.setTenantId(TENANT_ID);
        entity.setOrganizationId(ORG_ID);
        entity.setName("Hook");
        entity.setToken("wh_token");
        entity.setWorkflowId(LINKED_WORKFLOW_ID);
        return entity;
    }
}
