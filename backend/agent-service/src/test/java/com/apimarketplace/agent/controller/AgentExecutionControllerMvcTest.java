package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailResponseDto;
import com.apimarketplace.agent.service.execution.AgentRemoteExecutionService;
import com.apimarketplace.agent.service.execution.ClassifyService;
import com.apimarketplace.agent.service.execution.GuardrailService;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring-MVC test of the internal remote-execution endpoints on
 * {@link AgentExecutionController} via {@code standaloneSetup} (real DispatcherServlet
 * routing + Jackson deserialization of the request body + serialization of the response
 * envelope), with {@link AgentRemoteExecutionService}/{@link GuardrailService} mocked so no
 * LLM, CLI bridge, Redis or network is exercised. Complements the direct-method-call unit
 * tests ({@code AgentExecutionControllerOrgScopeTest}) by proving the HTTP-level contract:
 * the JSON body deserializes into the DTO, the {@code X-Organization-ID} / {@code X-User-Roles}
 * headers are read off the real request, and the response record serializes back over the wire.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentExecutionController - remote execution endpoints over MVC")
class AgentExecutionControllerMvcTest {

    @Mock private AgentRemoteExecutionService executionService;
    @Mock private ClassifyService classifyService;
    @Mock private GuardrailService guardrailService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AgentExecutionController controller = new AgentExecutionController(
                executionService, classifyService, guardrailService, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /agent serializes the response envelope (success, content, totalUsage, toolResults, iterations, stopReason)")
    void executeAgentSerializesResponseEnvelopeOverHttp() throws Exception {
        AgentExecutionResponseDto serviceResult = new AgentExecutionResponseDto(
                true,
                "final",
                "done",
                List.of(Map.of("name", "table", "ok", true)),
                2,
                Map.of("totalTokens", 42),
                null,
                7L,
                "openai",
                "gpt-5-mini",
                null,
                "COMPLETED",
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        when(executionService.executeAgent(any(), any())).thenReturn(serviceResult);

        mockMvc.perform(post("/api/internal/agent/execute/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentRequest(Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.content").value("done"))
                .andExpect(jsonPath("$.iterations").value(2))
                .andExpect(jsonPath("$.stopReason").value("COMPLETED"))
                // The two payloads an orchestrator caller reads back: usage accounting + tool results.
                .andExpect(jsonPath("$.totalUsage.totalTokens").value(42))
                .andExpect(jsonPath("$.toolResults.length()").value(1))
                .andExpect(jsonPath("$.toolResults[0].name").value("table"));
    }

    @Test
    @DisplayName("POST /agent deserializes the JSON body into the DTO and binds X-Organization-ID + forwards X-User-Roles through the real HTTP stack")
    void executeAgentDeserializesBodyAndBindsHeadersOverHttp() throws Exception {
        AtomicReference<AgentExecutionRequestDto> observedRequest = new AtomicReference<>();
        AtomicReference<String> observedRoles = new AtomicReference<>();
        AtomicReference<String> observedOrg = new AtomicReference<>();
        when(executionService.executeAgent(any(), any())).thenAnswer(invocation -> {
            observedRequest.set(invocation.getArgument(0));
            observedRoles.set(invocation.getArgument(1));
            observedOrg.set(TenantResolver.currentRequestOrganizationId());
            return okResponse();
        });

        mockMvc.perform(post("/api/internal/agent/execute/agent")
                        .header("X-Organization-ID", "org-http")
                        .header("X-User-Roles", "ADMIN,USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentRequest(Map.of()))))
                .andExpect(status().isOk());

        // The wire JSON round-tripped into the typed DTO that reached the service.
        assertThat(observedRequest.get()).isNotNull();
        assertThat(observedRequest.get().provider()).isEqualTo("openai");
        assertThat(observedRequest.get().model()).isEqualTo("gpt-5-mini");
        assertThat(observedRequest.get().runId()).isEqualTo("run-1");
        assertThat(observedRequest.get().nodeId()).isEqualTo("agent:test");
        // Header was read off the live request and forwarded as the roles arg (bridge admin policy).
        assertThat(observedRoles).hasValue("ADMIN,USER");
        // Org scope was bound from the header before the service ran.
        assertThat(observedOrg).hasValue("org-http");
    }

    @Test
    @DisplayName("POST /guardrail round-trips the request body and serializes the validation verdict (passed/violations/details) with org scope bound from the header")
    void executeGuardrailRoundTripsValidationOverHttp() throws Exception {
        AtomicReference<GuardrailRequestDto> observedRequest = new AtomicReference<>();
        AtomicReference<String> observedOrg = new AtomicReference<>();
        GuardrailResponseDto verdict = new GuardrailResponseDto(
                true,
                false,
                List.of("no-pii"),
                Map.of("severity", "high"),
                null,
                null,
                3L,
                "claude-code",
                "claude-sonnet-4-6",
                0, 0, 0,
                null, null, null);
        when(guardrailService.execute(any(), any())).thenAnswer(invocation -> {
            observedRequest.set(invocation.getArgument(0));
            observedOrg.set(TenantResolver.currentRequestOrganizationId());
            return verdict;
        });

        mockMvc.perform(post("/api/internal/agent/execute/guardrail")
                        .header("X-Organization-ID", "org-guard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(guardrailRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // The pass/fail verdict and its reasons survive serialization back to the caller.
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0]").value("no-pii"))
                .andExpect(jsonPath("$.details.severity").value("high"));

        // The content-to-validate deserialized from the body and org scope was applied.
        assertThat(observedRequest.get()).isNotNull();
        assertThat(observedRequest.get().content()).isEqualTo("content to validate");
        assertThat(observedOrg).hasValue("org-guard");
    }

    private static AgentExecutionRequestDto agentRequest(Map<String, Object> credentials) {
        return new AgentExecutionRequestDto(
                "prompt",
                "system",
                "openai",
                "gpt-5-mini",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "tenant-1",
                "run-1",
                "agent:test",
                null,
                credentials,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "agent-1",
                null,
                null,
                null,
                null,
                null,
                null,  // executionId
                null,  // source
                null,  // reasoningEffort
                null   // enabledModules
        );
    }

    private static GuardrailRequestDto guardrailRequest() {
        return new GuardrailRequestDto(
                "content to validate",
                "validate this",
                List.of(new GuardrailRequestDto.RuleDto("no-pii", "no personal info")),
                "block",
                "claude-code",
                "claude-sonnet-4-6",
                null,
                null,
                "tenant-1",
                "agent-1");
    }

    private static AgentExecutionResponseDto okResponse() {
        return new AgentExecutionResponseDto(
                true,
                "ok",
                "ok",
                null,
                1,
                null,
                null,
                1L,
                "openai",
                "gpt-5-mini",
                null,
                "COMPLETED",
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
