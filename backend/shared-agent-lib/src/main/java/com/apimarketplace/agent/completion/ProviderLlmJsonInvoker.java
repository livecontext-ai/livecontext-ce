package com.apimarketplace.agent.completion;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.cloud.RuntimeLlmProviderResolver;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.provider.LLMProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Stage 2 follow-up (#51) - direct-access production implementation of
 * the COLD-summary JSON invoker contract.
 *
 * <p>Routes the {@code (provider, model, system, user)} prompt through
 * {@link LLMProviderFactory#getProvider(String)} and returns the raw
 * {@link CompletionResponse#content() content} string - the summariser
 * prompt pins a JSON-only response, so this is the envelope JSON the
 * caller will parse.
 *
 * <p><b>Where it runs.</b> This bean needs direct access to the LLM
 * provider registry, so it lives in {@code shared-agent-lib} (bundled
 * with agent-service at compile scope). Callers that live in a service
 * which does <em>not</em> host providers (e.g. conversation-service,
 * which per architecture delegates LLM execution to agent-service) must
 * invoke it via the agent-service HTTP endpoint
 * ({@code /api/internal/agent/execute/json-completion}) rather than
 * trying to autowire this bean directly.
 *
 * <p><b>Stripping.</b> The summariser prompt instructs the model to emit
 * bare JSON, but several providers still wrap in Markdown code fences
 * despite explicit instructions. Only the outermost fence is stripped;
 * the inner content is forwarded untouched.
 *
 * <p><b>Failure mode.</b> Any provider-layer exception propagates to the
 * caller. Null/blank content is rejected as {@link IllegalStateException}
 * because the summariser needs a non-empty JSON body to parse.
 */
@Slf4j
@Component
public class ProviderLlmJsonInvoker {

    private final LLMProviderFactory providerFactory;
    private final RuntimeLlmProviderResolver providerResolver;

    public ProviderLlmJsonInvoker(LLMProviderFactory providerFactory) {
        this(providerFactory, null);
    }

    @Autowired
    public ProviderLlmJsonInvoker(LLMProviderFactory providerFactory,
                                  @Autowired(required = false) RuntimeLlmProviderResolver providerResolver) {
        this.providerFactory = Objects.requireNonNull(providerFactory, "providerFactory");
        this.providerResolver = providerResolver;
    }

    public String invoke(String provider, String model, String system, String user) {
        return invoke(provider, model, system, user, null);
    }

    /**
     * @param tenantId optional - forwarded to the completion request so
     *                 per-tenant rate limiters can attribute traffic.
     *                 {@code null} is accepted for system-internal calls.
     */
    public String invoke(String provider, String model, String system, String user, String tenantId) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(user, "user");

        LLMProvider llm = resolveProvider(provider, tenantId);

        CompletionRequest request = CompletionRequest.builder()
                .tenantId(tenantId)
                .model(model)
                .systemPrompt(system)
                .userPrompt(user)
                .temperature(0.2)
                .stream(false)
                .build();

        log.debug("json-completion invoke provider={} model={} tenant={} sys_len={} user_len={}",
                provider, model, tenantId,
                system == null ? 0 : system.length(),
                user.length());

        CompletionResponse response = llm.complete(request);
        String raw = response == null ? null : response.content();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "json-completion provider=" + provider + " model=" + model + " returned empty content");
        }
        return stripFence(raw);
    }

    private LLMProvider resolveProvider(String provider, String tenantId) {
        if (providerResolver == null) {
            return providerFactory.getProvider(provider);
        }
        AgentLoopContext context = AgentLoopContext.builder()
                .tenantId(tenantId)
                .provider(provider)
                .build();
        return providerResolver.resolve(provider, context);
    }

    /**
     * Strip a Markdown code fence wrapping the whole string (``` or ```json).
     * Leaves fences inside the content alone - only removes a single outer
     * wrapper so the JSON parser doesn't trip on ``` tokens.
     */
    static String stripFence(String s) {
        String trimmed = s.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String afterOpen = trimmed.substring(firstNewline + 1);
        if (afterOpen.endsWith("```")) {
            afterOpen = afterOpen.substring(0, afterOpen.length() - 3);
        }
        return afterOpen.strip();
    }
}
