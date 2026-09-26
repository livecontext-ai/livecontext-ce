package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REST half of arming an agent.
 *
 * <p>These exist because this endpoint shipped reading its body through the shared
 * extractor, whose {@code getBoolean} runs {@code Boolean.parseBoolean} on any
 * string. That answers <b>false</b> for "maybe", so a malformed value DISARMED an
 * agent somebody had armed on purpose and returned 200 saying so. It is the same
 * green-when-wrong write that was closed on the MCP path, reached by a different
 * route, and it survived because this endpoint had no test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - arming the tool-authorization flag")
class AgentControllerToolAuthorizationTest {

    private static final UUID AGENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String TENANT_ID = "tenant-1";

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
                agentService, webhookTokenService, widgetConfigService, tenantResolver,
                new RequestParameterExtractor(), "", "http://widget.test", "http://trigger.test");
        lenient().when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
    }

    private void agentSaved(boolean armed) {
        AgentEntity saved = new AgentEntity();
        saved.setId(AGENT_ID);
        saved.setRequireToolAuthorization(armed);
        // The 5-argument overload: the controller has an org role to pass, where the MCP
        // module does not. Stubbing the other one would leave this call unmatched.
        when(agentService.setRequireToolAuthorization(eq(AGENT_ID), anyString(), any(), any(), anyBoolean()))
                .thenReturn(saved);
    }

    private ResponseEntity<Map<String, Object>> patch(Object required) {
        Map<String, Object> body = new HashMap<>();
        body.put("required", required);
        return controller.setRequireToolAuthorization(AGENT_ID, request, body);
    }

    @Test
    @DisplayName("a value that is neither true nor false is refused, not read as false")
    void malformedValueIsRefused() {
        ResponseEntity<Map<String, Object>> response = patch("maybe");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("must be true or false");
        // Boolean.parseBoolean("maybe") is false, so a lenient read here disarms an
        // agent its owner armed on purpose and answers 200.
        verify(agentService, never()).setRequireToolAuthorization(any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an empty string is refused too")
    void emptyStringIsRefused() {
        assertThat(patch("").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(agentService, never()).setRequireToolAuthorization(any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an omitted field is refused rather than defaulted")
    void missingFieldIsRefused() {
        ResponseEntity<Map<String, Object>> response =
                controller.setRequireToolAuthorization(AGENT_ID, request, new HashMap<>());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(agentService, never()).setRequireToolAuthorization(any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("arming an agent whose chat channel is off is a 400 with the reason, not a 404")
    void armingWithoutChannelIs400() {
        when(agentService.setRequireToolAuthorization(eq(AGENT_ID), anyString(), any(), any(), eq(true)))
                .thenThrow(new AgentService.ChannelRequiredException());

        ResponseEntity<Map<String, Object>> response = patch(true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("chat channel is off");
    }

    @Test
    @DisplayName("arms on a real true")
    void armsOnBooleanTrue() {
        agentSaved(true);

        ResponseEntity<Map<String, Object>> response = patch(true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("requireToolAuthorization", true);
        verify(agentService).setRequireToolAuthorization(eq(AGENT_ID), eq(TENANT_ID), any(), any(), eq(true));
    }

    @Test
    @DisplayName("arms on the string 'true', which is what a form or a CLI sends")
    void armsOnStringTrue() {
        agentSaved(true);

        assertThat(patch("true").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(agentService).setRequireToolAuthorization(eq(AGENT_ID), eq(TENANT_ID), any(), any(), eq(true));
    }

    @Test
    @DisplayName("disarms on an explicit false, which is a real request")
    void disarmsOnFalse() {
        agentSaved(false);

        ResponseEntity<Map<String, Object>> response = patch(false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("requireToolAuthorization", false);
        verify(agentService).setRequireToolAuthorization(eq(AGENT_ID), eq(TENANT_ID), any(), any(), eq(false));
    }

    @Test
    @DisplayName("reports what actually landed, not what was asked")
    void reportsTheSavedValue() {
        // The response is read back off the saved entity, so a service that refused or
        // adjusted the value cannot be reported as if it had applied it.
        agentSaved(false);

        assertThat(patch(true).getBody()).containsEntry("requireToolAuthorization", false);
    }
}
