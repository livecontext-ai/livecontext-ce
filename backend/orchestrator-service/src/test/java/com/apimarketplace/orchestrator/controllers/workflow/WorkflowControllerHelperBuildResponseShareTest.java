package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the share-context secret-withholding in
 * {@link WorkflowControllerHelper#buildWorkflowResponse}.
 *
 * <p>Webhook tokens let anyone trigger the workflow via its webhook URL, so they
 * are owner secrets. The gateway/monolith filter resolves an APPLICATION share
 * token to the OWNER and allow-lists {@code GET /api/workflows/{id}} for the
 * {@code /s/{token}} application viewer - without this guard, that read leaked
 * the owner's webhook tokens to an anonymous share-link visitor.
 */
@DisplayName("WorkflowControllerHelper.buildWorkflowResponse - share-context secret withholding")
class WorkflowControllerHelperBuildResponseShareTest {

    private static final UUID WF_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private WorkflowControllerHelper helperWith(TriggerClient triggerClient) {
        WorkflowControllerHelper helper = new WorkflowControllerHelper();
        ReflectionTestUtils.setField(helper, "triggerClient", triggerClient);
        ReflectionTestUtils.setField(helper, "objectMapper", new ObjectMapper());
        return helper;
    }

    private WorkflowEntity workflow() {
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(WF_ID);
        when(workflow.getStatus()).thenReturn(WorkflowEntity.WorkflowStatus.ACTIVE);
        return workflow;
    }

    private void shareContext(boolean share) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (share) {
            req.addHeader("X-Share-Context", "true");
            req.addHeader("X-Share-Resource-Type", "APPLICATION");
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("normal (non-share) request → webhookTokens are included")
    void nonShareContext_includesWebhookTokens() {
        TriggerClient triggerClient = mock(TriggerClient.class);
        when(triggerClient.getTokensForWorkflow(WF_ID)).thenReturn(Map.of("my_webhook", "tok_secret"));
        shareContext(false);

        Map<String, Object> response = helperWith(triggerClient).buildWorkflowResponse(workflow());

        assertThat(response).containsKey("webhookTokens");
        assertThat(response.get("webhookTokens")).isEqualTo(Map.of("my_webhook", "tok_secret"));
    }

    @Test
    @DisplayName("APPLICATION share-context request → webhookTokens are WITHHELD and the trigger service is not even queried")
    void shareContext_withholdsWebhookTokens() {
        TriggerClient triggerClient = mock(TriggerClient.class);
        shareContext(true);

        Map<String, Object> response = helperWith(triggerClient).buildWorkflowResponse(workflow());

        assertThat(response).doesNotContainKey("webhookTokens");
        // The secret is never fetched in a share context (defense-in-depth: no accidental leak downstream).
        verify(triggerClient, never()).getTokensForWorkflow(any());
        // The rest of the response is still built (the viewer needs the definition to render).
        assertThat(response).containsKey("id").containsKey("plan").containsKey("status");
    }

    @Test
    @DisplayName("APPLICATION share context → the plan's raw inline secrets are scrubbed, and the ENTITY's plan is NOT mutated (deep copy)")
    void shareContext_scrubsPlanSecrets() throws Exception {
        TriggerClient triggerClient = mock(TriggerClient.class);
        // A plan whose httpRequest node carries a raw inline bearer token.
        Map<String, Object> authConfig = new HashMap<>();
        authConfig.put("bearerToken", "super-secret-value");
        Map<String, Object> http = new HashMap<>();
        http.put("url", "https://example.com");
        http.put("authConfig", authConfig);
        Map<String, Object> core = new HashMap<>();
        core.put("label", "n1");
        core.put("httpRequest", http);
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new java.util.ArrayList<>(List.of(core)));

        WorkflowEntity workflow = workflow();
        when(workflow.getPlan()).thenReturn(plan);
        shareContext(true);

        Map<String, Object> response = helperWith(triggerClient).buildWorkflowResponse(workflow);

        // The response plan is scrubbed - the raw secret and its authConfig container are gone.
        String responsePlanJson = new ObjectMapper().writeValueAsString(response.get("plan"));
        assertThat(responsePlanJson).doesNotContain("super-secret-value").doesNotContain("authConfig");
        // The persisted entity's plan is a DEEP COPY away - it must be untouched.
        assertThat(authConfig).containsEntry("bearerToken", "super-secret-value");
    }

    // -------------------------------------------------------------------------
    // The bootstrap / reused-run response (reached under the allow-listed POST
    // /api/v2/workflows/dag/execute a share visitor may call) must ALSO withhold
    // webhookTokens - same secret, different builder (fix the whole class).
    // -------------------------------------------------------------------------

    private WorkflowControllerHelper helperWith(TriggerClient triggerClient, TriggerTypeDetector detector) {
        WorkflowControllerHelper helper = new WorkflowControllerHelper();
        ReflectionTestUtils.setField(helper, "triggerClient", triggerClient);
        ReflectionTestUtils.setField(helper, "triggerTypeDetector", detector);
        return helper;
    }

    private WorkflowPlan webhookPlan() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getId()).thenReturn(WF_ID.toString());
        return plan;
    }

    @Test
    @DisplayName("reused-run bootstrap response, non-share request → webhookTokens are included when the plan has a webhook trigger")
    void reusedRunResponse_nonShareContext_includesWebhookTokens() {
        TriggerClient triggerClient = mock(TriggerClient.class);
        TriggerTypeDetector detector = mock(TriggerTypeDetector.class);
        WorkflowPlan plan = webhookPlan();
        when(detector.hasWebhookTrigger(plan)).thenReturn(true);
        when(triggerClient.getTokensForWorkflow(WF_ID)).thenReturn(Map.of("my_webhook", "tok_secret"));
        shareContext(false);

        Map<String, Object> response = helperWith(triggerClient, detector).buildReusedRunResponse("run-1", plan);

        assertThat(response).containsKey("webhookTokens");
    }

    @Test
    @DisplayName("reused-run bootstrap response, APPLICATION share context → webhookTokens are WITHHELD and the trigger service is not queried")
    void reusedRunResponse_shareContext_withholdsWebhookTokens() {
        TriggerClient triggerClient = mock(TriggerClient.class);
        TriggerTypeDetector detector = mock(TriggerTypeDetector.class);
        WorkflowPlan plan = webhookPlan();
        // hasWebhookTrigger is short-circuited by !isShareContext() before it is even called.
        shareContext(true);

        Map<String, Object> response = helperWith(triggerClient, detector).buildReusedRunResponse("run-1", plan);

        assertThat(response).doesNotContainKey("webhookTokens");
        verify(triggerClient, never()).getTokensForWorkflow(any());
        // The rest of the bootstrap response is still built.
        assertThat(response).containsKey("runId").containsKey("status");
    }
}
