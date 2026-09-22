package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The workflow detail payload carries who the workflow belongs to.
 *
 * <p>This is a cross-layer pin, and it exists because the layers disagreed silently. The UI
 * attributes an open workflow by reading {@code tenantId} off this response, exactly as it
 * does off the LIST response ({@code WorkflowSummary.tenantId}). The detail builder did not
 * emit it: the field came back undefined, the UI fell through to its no-owner branch, and the
 * breadcrumb showed a date where a person should have been. Nothing threw, nothing logged,
 * and no test on either side could see it - each one was right about its own half.
 */
@DisplayName("WorkflowControllerHelper.buildWorkflowResponse - attribution")
class WorkflowControllerHelperAttributionTest {

    private static final UUID WF_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private WorkflowControllerHelper helper() {
        WorkflowControllerHelper helper = new WorkflowControllerHelper();
        ReflectionTestUtils.setField(helper, "triggerClient", mock(TriggerClient.class));
        ReflectionTestUtils.setField(helper, "objectMapper", new ObjectMapper());
        return helper;
    }

    private WorkflowEntity workflowOwnedBy(String tenantId) {
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(WF_ID);
        when(workflow.getStatus()).thenReturn(WorkflowEntity.WorkflowStatus.ACTIVE);
        when(workflow.getTenantId()).thenReturn(tenantId);
        return workflow;
    }

    @Test
    @DisplayName("names the owning user under the same key the list response uses")
    void carriesTheOwningUser() {
        // Arrange
        WorkflowEntity workflow = workflowOwnedBy("42");

        // Act
        Map<String, Object> response = helper().buildWorkflowResponse(workflow);

        // Assert - `tenantId`, spelled exactly as WorkflowSummary spells it: the client reads
        // one field name for both shapes, so a second spelling here is a field it never finds.
        assertThat(response).containsEntry("tenantId", "42");
    }

    @Test
    @DisplayName("carries the timestamps attribution is shown against")
    void carriesTheTimestamps() {
        // Arrange
        WorkflowEntity workflow = workflowOwnedBy("42");
        when(workflow.getCreatedAt()).thenReturn(Instant.parse("2026-03-12T14:32:00Z"));
        when(workflow.getUpdatedAt()).thenReturn(Instant.parse("2026-09-07T09:12:00Z"));

        // Act
        Map<String, Object> response = helper().buildWorkflowResponse(workflow);

        // Assert
        assertThat(response).containsEntry("createdAt", Instant.parse("2026-03-12T14:32:00Z"));
        assertThat(response).containsEntry("updatedAt", Instant.parse("2026-09-07T09:12:00Z"));
    }

    @Test
    @DisplayName("withholds the owner from an anonymous share-link viewer")
    void withholdsTheOwnerInShareContext() {
        // Arrange - the /s/{token} application viewer, which this endpoint is allow-listed
        // for and which the gateway resolves to the OWNER's identity.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Share-Context", "true");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            WorkflowEntity workflow = workflowOwnedBy("42");

            // Act
            Map<String, Object> response = helper().buildWorkflowResponse(workflow);

            // Assert - same treatment as the webhook tokens and the plan's inline secrets in
            // this same builder: a share viewer gets what it needs to render, and nothing else.
            assertThat(response).doesNotContainKey("tenantId");
            assertThat(response).containsEntry("name", workflow.getName());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("keeps the key present when the owner is unset, rather than dropping it")
    void keepsTheKeyForAnUnsetOwner() {
        // Arrange - defensive: the column is nullable in the schema.
        WorkflowEntity workflow = workflowOwnedBy(null);

        // Act
        Map<String, Object> response = helper().buildWorkflowResponse(workflow);

        // Assert - a present null is "no owner recorded", which the client renders as the
        // plain creation date. An absent key is indistinguishable from a payload built by an
        // older server, and that ambiguity is what this whole test exists to remove.
        assertThat(response).containsKey("tenantId");
        assertThat(response.get("tenantId")).isNull();
    }
}
