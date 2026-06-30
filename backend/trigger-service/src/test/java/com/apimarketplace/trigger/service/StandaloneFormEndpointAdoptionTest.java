package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.repository.FormSubmissionLogRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Register → adopt → fire-ready chain for a STANDALONE FORM endpoint
 * (the {@code trigger:form} surface), guarding against the schedule-style
 * orphan-trigger bug class.
 *
 * <p><b>The bug class.</b> A standalone trigger is created in the builder before the
 * workflow has an id - so it lands with {@code workflow_id} NULL and {@code trigger_id}
 * NULL (an "orphan"). On pin it must be ADOPTED onto its workflow: both columns get
 * stamped. The schedule surface regressed here - the F4 PUB-HIJACK fix removed schedule's
 * adoption entirely, so a builder-created schedule stayed orphaned forever → it never
 * fired, was not counted against the plan limit, and was reaped. The fix restored
 * {@code StandaloneScheduleService.updateWorkflowReference} (sets BOTH workflow_id AND
 * trigger_id, guarded NULL→value).
 *
 * <p><b>Why FORM is a different mechanism (and this test pins it).</b> Form (like chat)
 * adopts in TWO trigger-service calls, NOT one:
 * <ol>
 *   <li>{@link StandaloneFormEndpointService#updateWorkflowReference} stamps {@code workflow_id}
 *       (driven from {@code PinAwareTriggerSyncService.syncChatFormEndpoints} →
 *       {@code TriggerClient.updateFormEndpointWorkflowReference});</li>
 *   <li>{@link StandaloneFormEndpointService#syncTriggerId} then finds the row BY the just-stamped
 *       {@code workflow_id} and stamps {@code trigger_id} (driven from the same sync via
 *       {@code TriggerClient.syncFormEndpointTriggerId}).</li>
 * </ol>
 * A form fires in {@code FormDispatchService.submitForm} only when BOTH are present
 * (non-null {@code workflowId} to find the workflow + non-null {@code triggerId} to
 * dispatch). If EITHER adoption step were dropped - the schedule-style defect - the
 * endpoint would stay un-fireable. This test drives the real service across the full
 * chain and asserts both columns end up stamped, so removing either step turns it RED.
 */
@DisplayName("Standalone form endpoint - register → adopt → fire-ready (orphan-trigger bug guard)")
class StandaloneFormEndpointAdoptionTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_WORKFLOW_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String FORM_TRIGGER_ID = "trigger:contact_form";

    private StandaloneFormEndpointRepository repository;
    private StandaloneFormEndpointService service;

    @BeforeEach
    void setUp() {
        repository = mock(StandaloneFormEndpointRepository.class);
        service = new StandaloneFormEndpointService(
                repository,
                mock(FormSubmissionLogRepository.class),
                mock(PlanLimitHelper.class)); // mock = no-op checkLimit, like the sibling immutability test
        // save() echoes back its argument (no JPA-generated fields needed for these assertions).
        when(repository.save(any(StandaloneFormEndpointEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private StandaloneFormEndpointRequest standaloneCreateRequest() {
        // Builder-created form endpoint: no workflowId, no triggerId yet - the workflow
        // does not exist at create time. This is the exact orphan starting state.
        return new StandaloneFormEndpointRequest(
                "Contact Form",          // name
                "Get in touch",          // description
                null,                    // workflowId  ← orphan
                null,                    // workflowName
                List.of(),               // formConfig
                "Thanks!",               // successMessage
                "node-form-1",           // sourceNodeId
                null);                   // triggerId    ← orphan
    }

    @Test
    @DisplayName("create() leaves a true orphan - workflow_id AND trigger_id both NULL (the bug's starting state)")
    void createLeavesOrphanWithBothReferencesNull() {
        StandaloneFormEndpointDto created = service.create(TENANT_ID, ORG_ID, "FREE", standaloneCreateRequest());

        // A standalone create MUST NOT pre-link a workflow or a trigger. If it did, this
        // surface could never exhibit the orphan bug - but the assertion documents reality:
        // both stay null until pin-time adoption runs.
        assertThat(created.getWorkflowId()).isNull();
        assertThat(created.getTriggerId()).isNull();
    }

    @Test
    @DisplayName("pin adoption stamps BOTH workflow_id (step 1) and trigger_id (step 2) → endpoint becomes fire-ready")
    void adoptionStampsBothWorkflowIdAndTriggerId() {
        // ── Arrange: the orphan row as persisted by create() (workflow_id NULL, trigger_id NULL).
        StandaloneFormEndpointEntity orphan = new StandaloneFormEndpointEntity();
        orphan.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        orphan.setTenantId(TENANT_ID);
        orphan.setOrganizationId(ORG_ID);
        orphan.setName("Contact Form");
        orphan.setToken("fm_orphan_token");
        orphan.setWorkflowId(null);   // orphan
        orphan.setTriggerId(null);    // orphan

        when(repository.findByIdAndOrganizationIdStrict(orphan.getId(), ORG_ID))
                .thenReturn(Optional.of(orphan));
        // syncTriggerId finds the now-linked row BY workflow_id (mirrors the real finder).
        when(repository.findByWorkflowId(WORKFLOW_ID))
                .thenReturn(List.of(orphan));

        // ── Act, step 1: PinAwareTriggerSyncService → updateFormEndpointWorkflowReference.
        StandaloneFormEndpointDto afterStep1 =
                service.updateWorkflowReference(TENANT_ID, ORG_ID, orphan.getId(), WORKFLOW_ID, "My Workflow");

        // workflow_id is stamped; trigger_id NOT yet (this is the two-step form contract).
        assertThat(afterStep1.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(orphan.getWorkflowId()).isEqualTo(WORKFLOW_ID);

        // ── Act, step 2: PinAwareTriggerSyncService → syncFormEndpointTriggerId.
        service.syncTriggerId(WORKFLOW_ID, FORM_TRIGGER_ID);

        // ── Assert: BOTH references are now stamped. FormDispatchService.submitForm reads
        // endpoint.getWorkflowId() (to find the workflow) and endpoint.getTriggerId() (to
        // dispatch); both must be non-null or the form silently never fires. This is the
        // fire-ready state the schedule surface failed to reach when its adoption was removed.
        assertThat(orphan.getWorkflowId())
                .as("workflow_id must be stamped by adoption step 1")
                .isEqualTo(WORKFLOW_ID);
        assertThat(orphan.getTriggerId())
                .as("trigger_id must be stamped by adoption step 2 (syncTriggerId) - "
                        + "if this is null the form can never fire (schedule-style orphan bug)")
                .isEqualTo(FORM_TRIGGER_ID);

        // The trigger_id write was persisted (not just mutated in memory).
        verify(repository, atLeastOnce()).save(orphan);
    }

    @Test
    @DisplayName("syncTriggerId is a no-op when no endpoint matches the workflow_id (back-link must run first)")
    void syncTriggerIdNoOpWhenNoEndpointMatchesWorkflow() {
        // Mirrors the PinAwareTriggerSyncService invariant: if the back-link (step 1) did
        // not stamp workflow_id, the find-by-workflow_id in step 2 returns nothing and no
        // unrelated endpoint gets clobbered.
        when(repository.findByWorkflowId(WORKFLOW_ID)).thenReturn(List.of());

        service.syncTriggerId(WORKFLOW_ID, FORM_TRIGGER_ID);

        // No save - nothing matched, nothing written.
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("adoption is guarded NULL→value only - re-binding an already-linked workflow is rejected (hijack-safe)")
    void adoptionRejectsRebindingAlreadyLinkedWorkflow() {
        // Once owned, workflow_id is immutable (same guard schedule/chat/webhook use). This
        // keeps the F4 clone-hijack class closed: an adopted form cannot be silently re-pointed.
        StandaloneFormEndpointEntity alreadyLinked = new StandaloneFormEndpointEntity();
        alreadyLinked.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        alreadyLinked.setTenantId(TENANT_ID);
        alreadyLinked.setOrganizationId(ORG_ID);
        alreadyLinked.setName("Contact Form");
        alreadyLinked.setToken("fm_linked_token");
        alreadyLinked.setWorkflowId(WORKFLOW_ID);   // already owned

        when(repository.findByIdAndOrganizationIdStrict(alreadyLinked.getId(), ORG_ID))
                .thenReturn(Optional.of(alreadyLinked));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.updateWorkflowReference(TENANT_ID, ORG_ID, alreadyLinked.getId(),
                                OTHER_WORKFLOW_ID, "Other Workflow"))
                .isInstanceOf(WorkflowReferenceImmutableException.class)
                .hasMessageContaining("workflowId is immutable");

        // Idempotent re-adoption to the SAME workflow is allowed (NULL→value guard only blocks
        // a CHANGE, not a no-op repeat) - proves pin can run twice without throwing.
        StandaloneFormEndpointDto reAdopted =
                service.updateWorkflowReference(TENANT_ID, ORG_ID, alreadyLinked.getId(), WORKFLOW_ID, "My Workflow");
        assertThat(reAdopted.getWorkflowId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    @DisplayName("orphan rows are excluded from the plan-limit count (workflow_id IS NOT NULL only)")
    void planLimitCountExcludesOrphans() {
        // The count an org sees must come from the workflow_id-IS-NOT-NULL finder, so orphan
        // drafts (and the schedule-style never-adopted rows) do not eat the user's slots.
        when(repository.countByOrganizationIdStrictAndWorkflowIdIsNotNull(ORG_ID)).thenReturn(2L);

        var config = service.getConfig(TENANT_ID, ORG_ID, "FREE");

        // EndpointConfigDto is a record → record-component accessor, not a JavaBean getter.
        assertThat(config.currentCount()).isEqualTo(2L);
        verify(repository).countByOrganizationIdStrictAndWorkflowIdIsNotNull(ORG_ID);
    }
}
