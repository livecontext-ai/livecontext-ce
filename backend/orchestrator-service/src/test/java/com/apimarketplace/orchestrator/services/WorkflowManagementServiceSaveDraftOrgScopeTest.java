package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the just-shipped WorkflowManagementService.saveDraft org-stamp fix.
 *
 * <p>Bug shape: prior to the fix, the auto-draft path created the workflow row with
 * {@code organization_id = NULL} even when the user was operating in an org workspace.
 * The bell-icon Triggers tab list endpoints filter on
 * {@code organization_id = :orgId}, so the draft (and its eventual schedule/webhook
 * children) silently disappeared from the org member's view of "their" triggers -
 * a functional but invisible workflow.
 *
 * <p>The fix introduces a 4-arg {@code saveDraft(planMap, tenantId, workflowId,
 * organizationId)} overload that stamps {@code organization_id} on new rows and
 * preserves the existing value on updates. The 3-arg legacy overload routes to
 * the 4-arg form with {@code organizationId=null} (back-compat: personal-scope).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowManagementService.saveDraft - organization_id stamping")
class WorkflowManagementServiceSaveDraftOrgScopeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private StorageBreakdownService breakdownService;

    private WorkflowManagementService service;

    private static final String TENANT_ID = "tenant-alice";

    @BeforeEach
    void setUp() {
        service = new WorkflowManagementService();
        setField(service, "workflowRepository", workflowRepository);
        setField(service, "orgAccessService", orgAccessService);
        setField(service, "breakdownService", breakdownService);
        setField(service, "objectMapper", new ObjectMapper());

        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("saveDraft_withOrgId_stampsOrganizationIdOnNewEntity - 4-arg form stamps the active org on new draft row (bell Triggers tab visibility)")
    void saveDraft_withOrgId_stampsOrganizationIdOnNewEntity() {
        // Arrange - fresh workflowId so the service treats it as a new entity.
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("name", "My draft");

        // Act
        service.saveDraft(planMap, TENANT_ID, workflowId, "ORG-1");

        // Assert - capture the entity passed to save() and check organization_id.
        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("new draft must inherit the caller's active org so org-scoped list endpoints surface it")
                .isEqualTo("ORG-1");
    }

    @Test
    @DisplayName("saveDraft_withOrgId_doesNotOverrideExistingOrgIdOnUpdate - pre-existing org-A row is not silently rewritten to org-B on update")
    void saveDraft_withOrgId_doesNotOverrideExistingOrgIdOnUpdate() {
        // Arrange - pre-existing draft already stamped with ORG-A.
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity existing = new WorkflowEntity();
        existing.setId(workflowId);
        existing.setTenantId(TENANT_ID);
        existing.setOrganizationId("ORG-A");
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(existing));
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("name", "Updated draft");

        // Act - caller now sits in ORG-B (e.g. user switched workspace before the autosave).
        service.saveDraft(planMap, TENANT_ID, workflowId, "ORG-B");

        // Assert - the existing row's organization_id stays ORG-A. The 4-arg overload's
        // stamping branch only fires on the new-entity path; updates preserve scope.
        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("update must not rewrite organization_id (would silently move the workflow across orgs)")
                .isEqualTo("ORG-A");
    }

    @Test
    @DisplayName("saveDraft_legacy3ArgForm_leavesOrgIdNullForNewEntity - back-compat contract: personal-scope drafts stay NULL-org")
    void saveDraft_legacy3ArgForm_leavesOrgIdNullForNewEntity() {
        // Arrange - fresh workflowId, no org context (legacy callers).
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("name", "Personal draft");

        // Act - 3-arg legacy form, no organizationId passed.
        service.saveDraft(planMap, TENANT_ID, workflowId);

        // Assert - organization_id stays null (personal scope), matching pre-fix semantics
        // for callers that haven't been migrated to the 4-arg form yet.
        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("legacy 3-arg call must NOT stamp an org - would corrupt personal-scope drafts")
                .isNull();
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}
