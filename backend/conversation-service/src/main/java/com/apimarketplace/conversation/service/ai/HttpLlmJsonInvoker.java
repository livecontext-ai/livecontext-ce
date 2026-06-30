package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.JsonCompletionRequestDto;
import com.apimarketplace.agent.client.dto.execution.JsonCompletionResponseDto;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.LlmJsonInvoker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Objects;

/**
 * Stage 2 follow-up (#51) - HTTP-backed production wiring for
 * {@link ColdSummarizerService.LlmJsonInvoker}.
 *
 * <p>Per the conversation-service architecture comment in {@code pom.xml}
 * ("delegates LLM execution to agent-service"), conversation-service does
 * <em>not</em> host LLM providers in its own JVM. This invoker therefore
 * forwards the prompt to {@code agent-service}'s internal
 * {@code /api/internal/agent/execute/json-completion} endpoint and returns
 * the raw content the model produced (with any outer Markdown fence
 * already stripped server-side).
 *
 * <p><b>Headers.</b> Uses the same {@code X-User-ID} internal-auth
 * pattern as all other conversation-service → agent-service hops (see
 * {@code BridgeClient}, {@code AgentClient}). {@code null} tenantId
 * yields no header; the endpoint is {@code /api/internal/*} so gateway
 * auth is bypassed.
 *
 * <p><b>Timeouts.</b> Cold-summary calls are explicitly non-streaming
 * single-turn completions; p99 budget is well under 60s. A 90s read
 * timeout leaves headroom for slow providers without letting the request
 * outlive the upstream {@code LOCK_AT_MOST_FOR} (2 min) on the
 * distributed lock.
 */
@Slf4j
@Component
public class HttpLlmJsonInvoker implements LlmJsonInvoker {

    private final String agentServiceUrl;
    private final RestTemplate restTemplate;

    /**
     * Single constructor - Spring 4.3+ auto-wires the sole parameterised
     * ctor. The {@link HttpLlmJsonInvokerConfig}-built {@code
     * llmJsonInvokerRestTemplate} bean carries the 10s connect / 90s read
     * timeout tuned for cold-summary p99. Tests call this ctor directly
     * with a mock {@link RestTemplate}.
     */
    public HttpLlmJsonInvoker(
            @Value("${services.agent-service.url:http://localhost:8090}") String agentServiceUrl,
            @Qualifier("llmJsonInvokerRestTemplate") RestTemplate restTemplate) {
        this.agentServiceUrl = Objects.requireNonNull(agentServiceUrl, "agentServiceUrl");
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    @Override
    public String invoke(String provider, String model, String system, String user) {
        return invoke(provider, model, system, user, null);
    }

    /**
     * Explicit tenant-scoped overload. Reserved for callers that have a
     * tenantId on hand and want per-tenant rate-limit attribution. The
     * three-arg {@link LlmJsonInvoker} seam stays tenant-agnostic so the
     * summariser service doesn't need to know about tenant plumbing.
     */
    public String invoke(String provider, String model, String system, String user, String tenantId) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(user, "user");

        String url = agentServiceUrl + "/api/internal/agent/execute/json-completion";
        JsonCompletionRequestDto body = new JsonCompletionRequestDto(provider, model, system, user, tenantId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set("X-User-ID", tenantId);
        }
        // 2026-05-21 - forward X-Organization-ID + X-Organization-Role so
        // agent-service /api/internal/agent/execute/json-completion (which
        // persists agent_executions, an OrgScopedEntity) sees the correct
        // workspace context. Without this, post-V263 @PrePersist fails or
        // the row lands without an org tag.
        OrgContextHeaderForwarder.forward(headers);

        log.debug("json-completion dispatch url={} provider={} model={} tenant={}",
                url, provider, model, tenantId);

        ResponseEntity<JsonCompletionResponseDto> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonCompletionResponseDto.class);

        JsonCompletionResponseDto payload = response.getBody();
        if (payload == null || payload.content() == null || payload.content().isBlank()) {
            throw new IllegalStateException(
                    "json-completion remote returned empty body provider=" + provider + " model=" + model);
        }
        return payload.content();
    }
}
