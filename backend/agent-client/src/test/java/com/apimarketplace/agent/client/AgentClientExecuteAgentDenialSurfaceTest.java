package com.apimarketplace.agent.client;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pins the 4xx surfacing contract introduced 2026-05-21.
 *
 * <p>Pre-fix: {@code AgentClient.executeAgent/Classify/Guardrail} caught
 * {@code Exception} and returned {@code null}, swallowing the typed
 * {@code BRIDGE_ACCESS_DENIED} 403/429 body produced by agent-service's
 * {@code GlobalExceptionHandler}. The orchestrator then collapsed
 * {@code response == null} into {@code "Remote agent execution returned null"} -
 * losing the actionable text the V270 admin_only fix relies on.
 *
 * <p>Post-fix: a typed {@code HttpClientErrorException} handler parses the
 * {@code error}/{@code message} field out of the 4xx body and returns a
 * structured FAILED response. The user keeps seeing the typed reason
 * (e.g. {@code "Bridge access denied for claude-code: admin_only_requires_admin_role"})
 * instead of a bare null.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentClient - 4xx denial body surfacing on remote execute endpoints")
class AgentClientExecuteAgentDenialSurfaceTest {

    @Mock
    private RestTemplate executionRestTemplate;

    private AgentClient agentClient;

    @BeforeEach
    void setUp() throws Exception {
        agentClient = new AgentClient("http://localhost:8090");
        // The execution paths use a dedicated long-timeout RestTemplate that
        // the public constructors don't expose. Reflection is the minimal
        // intrusion to test the 4xx path without inflating the API surface.
        Field f = AgentClient.class.getDeclaredField("executionRestTemplate");
        f.setAccessible(true);
        f.set(agentClient, executionRestTemplate);
    }

    @Test
    @DisplayName("executeAgent - 403 with the real GlobalExceptionHandler body shape surfaces the actionable `message` sentence, not the `BRIDGE_ACCESS_DENIED` discriminator code")
    void executeAgentSurfacesBridgeDenialReason() {
        // The real wire body emitted by agent-service GlobalExceptionHandler
        // (BridgeAccessDeniedException handler, V270 prod path):
        //   error    = "BRIDGE_ACCESS_DENIED"  (discriminator)
        //   reason   = "admin_only_requires_admin_role"  (token)
        //   provider = "claude-code"
        //   message  = "Bridge access denied for claude-code: admin_only_requires_admin_role"
        // The user-facing actionable text lives in `message`. A naive
        // implementation that reads `error` first would surface only the
        // discriminator code - UX regression vs the pre-fix squashed string.
        String body = """
            {"error":"BRIDGE_ACCESS_DENIED",
             "reason":"admin_only_requires_admin_role",
             "provider":"claude-code",
             "message":"Bridge access denied for claude-code: admin_only_requires_admin_role"}
            """;
        HttpClientErrorException ex = HttpClientErrorException.create(
            HttpStatus.FORBIDDEN, "Forbidden",
            org.springframework.http.HttpHeaders.EMPTY, body.getBytes(), null);
        when(executionRestTemplate.exchange(
                eq("http://localhost:8090/api/internal/agent/execute/agent"),
                eq(HttpMethod.POST), any(), eq(AgentExecutionResponseDto.class)))
            .thenThrow(ex);

        AgentExecutionResponseDto result = agentClient.executeAgent(agentRequest());

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo(
            "Bridge access denied for claude-code: admin_only_requires_admin_role");
        assertThat(result.stopReason()).isEqualTo("ERROR");
        assertThat(result.provider()).isEqualTo("claude-code");
    }

    @Test
    @DisplayName("executeAgent - 429 quota-exhausted body surfaces the actionable `message`, not just the token")
    void executeAgentSurfaces429Body() {
        String body = """
            {"error":"BRIDGE_ACCESS_DENIED",
             "reason":"daily_quota_exhausted",
             "provider":"claude-code",
             "message":"Bridge access denied for claude-code: daily_quota_exhausted",
             "remainingRequestsToday":0}
            """;
        HttpClientErrorException ex = HttpClientErrorException.create(
            HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
            org.springframework.http.HttpHeaders.EMPTY, body.getBytes(), null);
        when(executionRestTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(), eq(AgentExecutionResponseDto.class)))
            .thenThrow(ex);

        AgentExecutionResponseDto result = agentClient.executeAgent(agentRequest());

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo(
            "Bridge access denied for claude-code: daily_quota_exhausted");
    }

    @Test
    @DisplayName("Falls back to `reason` token when `message` is absent - defensive guard for sibling 4xx handlers that omit `message`")
    void executeAgentFallsBackToReasonWhenMessageAbsent() {
        String body = """
            {"error":"BRIDGE_ACCESS_DENIED",
             "reason":"bridge_disabled",
             "provider":"claude-code"}
            """;
        HttpClientErrorException ex = HttpClientErrorException.create(
            HttpStatus.FORBIDDEN, "Forbidden",
            org.springframework.http.HttpHeaders.EMPTY, body.getBytes(), null);
        when(executionRestTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(), eq(AgentExecutionResponseDto.class)))
            .thenThrow(ex);

        AgentExecutionResponseDto result = agentClient.executeAgent(agentRequest());

        assertThat(result).isNotNull();
        assertThat(result.error()).isEqualTo("bridge_disabled");
    }

    @Test
    @DisplayName("executeAgent - non-JSON 4xx body falls back to a status-derived message instead of null")
    void executeAgentFallsBackOnNonJsonBody() {
        HttpClientErrorException ex = HttpClientErrorException.create(
            HttpStatus.FORBIDDEN, "Forbidden",
            org.springframework.http.HttpHeaders.EMPTY, "raw text body".getBytes(), null);
        when(executionRestTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(), eq(AgentExecutionResponseDto.class)))
            .thenThrow(ex);

        AgentExecutionResponseDto result = agentClient.executeAgent(agentRequest());

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("403");
    }

    @Test
    @DisplayName("executeClassify - same 4xx-surfacing contract for the /classify endpoint")
    void executeClassifySurfacesDenialReason() {
        String body = """
            {"error":"BRIDGE_ACCESS_DENIED",
             "reason":"admin_only_requires_admin_role",
             "provider":"claude-code",
             "message":"Bridge access denied for claude-code: admin_only_requires_admin_role"}
            """;
        HttpClientErrorException ex = HttpClientErrorException.create(
            HttpStatus.FORBIDDEN, "Forbidden",
            org.springframework.http.HttpHeaders.EMPTY, body.getBytes(), null);
        when(executionRestTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(), eq(ClassifyResponseDto.class)))
            .thenThrow(ex);

        ClassifyResponseDto result = agentClient.executeClassify(classifyRequest());

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo(
            "Bridge access denied for claude-code: admin_only_requires_admin_role");
    }

    @Test
    @DisplayName("executeGuardrail - same 4xx-surfacing contract for the /guardrail endpoint")
    void executeGuardrailSurfacesDenialReason() {
        String body = """
            {"error":"BRIDGE_ACCESS_DENIED",
             "reason":"bridge_disabled",
             "provider":"claude-code",
             "message":"Bridge access denied for claude-code: bridge_disabled"}
            """;
        HttpClientErrorException ex = HttpClientErrorException.create(
            HttpStatus.FORBIDDEN, "Forbidden",
            org.springframework.http.HttpHeaders.EMPTY, body.getBytes(), null);
        when(executionRestTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(), eq(GuardrailResponseDto.class)))
            .thenThrow(ex);

        GuardrailResponseDto result = agentClient.executeGuardrail(guardrailRequest());

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo(
            "Bridge access denied for claude-code: bridge_disabled");
    }

    // ---- helpers ---------------------------------------------------------------

    private static AgentExecutionRequestDto agentRequest() {
        return new AgentExecutionRequestDto(
            "prompt", "system", "claude-code", "claude-sonnet-4-6",
            null, null, null, null, null, null, null, null,
            "tenant-1", "run-1", "agent:test", null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, "agent-1", null, null, null, null, null, null, null, null, null);
    }

    private static ClassifyRequestDto classifyRequest() {
        return new ClassifyRequestDto(
            "content", "prompt",
            List.of(new ClassifyRequestDto.CategoryDto("billing", "billing issues")),
            "claude-code", "claude-sonnet-4-6", null, null, "tenant-1", "agent-1");
    }

    private static GuardrailRequestDto guardrailRequest() {
        return new GuardrailRequestDto(
            "content", "prompt",
            List.of(new GuardrailRequestDto.RuleDto("no-pii", "no personal info")),
            "block", "claude-code", "claude-sonnet-4-6", null, null, "tenant-1", "agent-1");
    }
}
