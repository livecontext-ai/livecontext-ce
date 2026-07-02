package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.execution.*;
import com.apimarketplace.agent.completion.ProviderLlmJsonInvoker;
import com.apimarketplace.agent.service.execution.AgentRemoteExecutionService;
import com.apimarketplace.agent.service.execution.ClassifyService;
import com.apimarketplace.agent.service.execution.GuardrailService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Internal endpoints for remote agent execution.
 * Called by orchestrator-service via AgentClient when agent.execution.remote=true.
 *
 * These endpoints offload heavy LLM computation from orchestrator to agent-service,
 * allowing horizontal scaling of agent workloads.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/agent/execute")
@RequiredArgsConstructor
public class AgentExecutionController {

    private final AgentRemoteExecutionService executionService;
    private final ClassifyService classifyService;
    private final GuardrailService guardrailService;
    private final ProviderLlmJsonInvoker jsonInvoker;

    /**
     * Model execution links (CLOUD only): a billed {@code (provider, model)} pair may
     * EXECUTE on a different API target. Field-injected and optional - null in CE /
     * when the feature flag is off, in which case json-completion runs the requested
     * pair verbatim (pre-link behavior).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.service.ModelExecutionLinkService executionLinkService;

    /**
     * Execute a full agent with tool calls and streaming.
     * Tool calls are delegated back to orchestrator via HTTP.
     * Streaming events are published to Redis for gateway SSE pickup.
     */
    @PostMapping("/agent")
    public ResponseEntity<AgentExecutionResponseDto> executeAgent(
            HttpServletRequest httpRequest,
            @RequestBody AgentExecutionRequestDto request) {
        log.info("Received remote agent execution request: provider={}, model={}, runId={}, nodeId={}",
            request.provider(), request.model(), request.runId(), request.nodeId());

        String organizationId = resolveOrgId(httpRequest, request.credentials());
        // Forward X-User-Roles to the bridge access guard so admin-only policies
        // (V270 default) recognise ADMIN callers. Without this, the dispatch path
        // would degrade every caller to the default USER role downstream.
        String userRoles = headerUserRoles(httpRequest);
        AgentExecutionResponseDto response = executeWithOrgScope(
            organizationId, () -> executionService.executeAgent(request, userRoles));
        return ResponseEntity.ok(response);
    }

    /**
     * Execute classification on content using AI.
     * Simpler than full agent - no tool calls, no streaming.
     */
    @PostMapping("/classify")
    public ResponseEntity<ClassifyResponseDto> executeClassify(
            HttpServletRequest httpRequest,
            @RequestBody ClassifyRequestDto request) {
        log.info("Received remote classify execution request: provider={}, model={}, tenantId={}",
            request.provider(), request.model(), request.tenantId());

        String userRoles = headerUserRoles(httpRequest);
        ClassifyResponseDto response = executeWithOrgScope(
            headerOrgId(httpRequest), () -> classifyService.execute(request, userRoles));
        return ResponseEntity.ok(response);
    }

    /**
     * Execute guardrail validation on content using AI.
     * Simpler than full agent - no tool calls, no streaming.
     */
    @PostMapping("/guardrail")
    public ResponseEntity<GuardrailResponseDto> executeGuardrail(
            HttpServletRequest httpRequest,
            @RequestBody GuardrailRequestDto request) {
        log.info("Received remote guardrail execution request: provider={}, model={}, tenantId={}",
            request.provider(), request.model(), request.tenantId());

        String userRoles = headerUserRoles(httpRequest);
        GuardrailResponseDto response = executeWithOrgScope(
            headerOrgId(httpRequest), () -> guardrailService.execute(request, userRoles));
        return ResponseEntity.ok(response);
    }

    /**
     * One-shot JSON completion (Stage 2 follow-up #51). Routes the
     * {@code (provider, model, system, user)} prompt through the live
     * {@link ProviderLlmJsonInvoker} and returns the raw model content,
     * with any outer Markdown fence already stripped. Intended for
     * callers that don't need the agent-loop scaffolding - COLD-summary
     * generation, single-turn JSON extraction, schema-constrained
     * prompts.
     */
    @PostMapping("/json-completion")
    public ResponseEntity<JsonCompletionResponseDto> executeJsonCompletion(
            HttpServletRequest httpRequest,
            @RequestBody JsonCompletionRequestDto request) {
        log.debug("Received json-completion request: provider={}, model={}, tenantId={}",
            request.provider(), request.model(), request.tenantId());

        // Model execution link (CLOUD only, third consumer after agent execution + CE
        // relay): the requested pair may be linked to an API execution target - honor
        // it, so an admin routing a billed model away from its direct platform key also
        // covers single completions (COLD-summary compaction was the reported gap). A
        // bridge-target link throws IllegalArgumentException -> 400 (a CLI bridge
        // cannot serve a bare completion). Bean absent in CE -> requested pair verbatim.
        final String execProvider;
        final String execModel;
        boolean resolvable = request.provider() != null && !request.provider().isBlank()
            && request.model() != null && !request.model().isBlank();
        if (executionLinkService != null && resolvable) {
            var target = executionLinkService.resolveSingleCompletionTarget(request.provider(), request.model());
            execProvider = target.provider();
            execModel = target.model();
            if (!java.util.Objects.equals(execProvider, request.provider())
                    || !java.util.Objects.equals(execModel, request.model())) {
                log.info("json-completion link route: billed={}/{} -> exec={}/{}",
                    request.provider(), request.model(), execProvider, execModel);
            }
        } else {
            // No link service (CE / feature off) or a blank pair: run the requested pair
            // verbatim - the invoker rejects blanks with its own explicit error.
            execProvider = request.provider();
            execModel = request.model();
        }

        String content = executeWithOrgScope(headerOrgId(httpRequest), () -> jsonInvoker.invoke(
                execProvider,
                execModel,
                request.system(),
                request.user(),
                request.tenantId()
        ));
        return ResponseEntity.ok(new JsonCompletionResponseDto(content));
    }

    private static String resolveOrgId(HttpServletRequest httpRequest, Map<String, Object> credentials) {
        String headerOrgId = headerOrgId(httpRequest);
        if (headerOrgId != null) {
            return headerOrgId;
        }
        if (credentials == null) {
            return null;
        }
        Object credentialOrg = credentials.get("__orgId__");
        if (credentialOrg == null) {
            return null;
        }
        String value = credentialOrg.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private static String headerOrgId(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        String value = httpRequest.getHeader("X-Organization-ID");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String headerUserRoles(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        String value = httpRequest.getHeader("X-User-Roles");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> T executeWithOrgScope(String organizationId, Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(organizationId, () -> result.set(supplier.get()));
        return result.get();
    }
}
