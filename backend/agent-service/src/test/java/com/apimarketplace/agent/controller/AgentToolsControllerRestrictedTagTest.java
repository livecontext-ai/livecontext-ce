package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.apimarketplace.common.classification.DataSensitivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression review 2026-09-29 (CASA LC-066): this controller rebuilds the credentials of a relayed
 * tool call key by key, and the restricted-data tag was not one of the keys. The memory tool
 * refuses to keep Gmail content only when it sees the tag, so a restricted caller relayed here
 * (the MCP relay, the CE monolith) could store mailbox content as an ordinary memory.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentToolsController (agent-service) restores the forwarded restricted-data tag")
class AgentToolsControllerRestrictedTagTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;

    private Map<String, Object> credentialsFor(Object dataSensitivity) {
        AgentToolsController controller = new AgentToolsController(registry, registrationService);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-User-ID", "tenant-1");
        when(registry.hasTool("memory")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> context =
            ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeTool(eq("memory"), any(), context.capture()))
            .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of()));

        Map<String, Object> body = new HashMap<>();
        body.put("tool", "memory");
        body.put("parameters", Map.of("action", "save"));
        if (dataSensitivity != null) {
            body.put("dataSensitivity", dataSensitivity);
        }
        controller.executeTool(http, body);
        return context.getValue().credentials();
    }

    @Test
    @DisplayName("regression: a forwarded RESTRICTED tag reaches the tool's credentials")
    void restrictedTagIsRestored() {
        assertThat(DataSensitivity.fromCredentials(credentialsFor("RESTRICTED")).isRestricted()).isTrue();
    }

    @Test
    @DisplayName("no field leaves the call untagged")
    void absentFieldStaysUntagged() {
        assertThat(credentialsFor(null)).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }

    @Test
    @DisplayName("NORMAL is not written either: absence already means NORMAL")
    void normalIsNotWritten() {
        assertThat(credentialsFor("NORMAL")).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }

    @Test
    @DisplayName("an unparseable value is not a restriction")
    void garbageIsNotARestriction() {
        assertThat(credentialsFor("definitely-not-a-level")).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }
}
