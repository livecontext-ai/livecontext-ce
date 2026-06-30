package com.apimarketplace.agent.tools;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.interceptor.ToolExecutionInterceptor;
import com.apimarketplace.agent.tools.validation.SlimSchemaInputCoercer;
import com.apimarketplace.agent.tools.validation.ToolParameterValidator;
import com.apimarketplace.agent.tools.validation.ValidationResult;
import com.apimarketplace.common.web.TenantResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Service responsible for registering all tool providers with the registry.
 * Automatically discovers and registers all ToolsProvider beans at startup.
 * 
 * Features:
 * - Auto-registration of providers
 * - O(1) tool-to-provider lookup cache
 * - Automatic parameter validation
 * - Execution interceptors
 * - Async execution support
 */
@Slf4j
@Service
public class ToolsRegistrationService {

    private final AgentToolRegistry registry;
    private final List<ToolsProvider> providers;
    private final ToolParameterValidator validator;
    private final SlimSchemaInputCoercer slimCoercer;
    private final List<ToolExecutionInterceptor> interceptors;

    // Performance cache: tool name -> provider
    private final Map<String, ToolsProvider> toolToProviderCache = new ConcurrentHashMap<>();

    @Autowired
    public ToolsRegistrationService(
            AgentToolRegistry registry,
            @Autowired(required = false) List<ToolsProvider> providers,
            @Autowired(required = false) ToolParameterValidator validator,
            @Autowired(required = false) SlimSchemaInputCoercer slimCoercer,
            @Autowired(required = false) List<ToolExecutionInterceptor> interceptors) {
        this.registry = registry;
        this.providers = providers != null ? providers : List.of();
        this.validator = validator;
        this.slimCoercer = slimCoercer;
        this.interceptors = interceptors != null
            ? interceptors.stream()
                .sorted(Comparator.comparingInt(ToolExecutionInterceptor::getOrder))
                .toList()
            : List.of();
    }

    @PostConstruct
    public void registerAllTools() {
        log.info("Registering agent tools from {} providers: {}",
            providers.size(),
            providers.stream().map(p -> p.getClass().getSimpleName()).toList());

        int totalTools = 0;
        List<String> failedProviders = new java.util.ArrayList<>();

        for (ToolsProvider provider : providers) {
            try {
                var tools = provider.getTools();
                registry.registerAll(tools);
                totalTools += tools.size();

                // Build cache
                for (var tool : tools) {
                    toolToProviderCache.put(tool.name(), provider);
                }

                log.info("Registered {} tools from {} provider",
                    tools.size(), provider.getCategory().getSlug());
            } catch (Exception e) {
                String providerName = provider.getCategory().getSlug();
                failedProviders.add(providerName + ": " + e.getMessage());
                log.error("CRITICAL: Failed to register tools from provider {}: {}",
                    providerName, e.getMessage(), e);
            }
        }

        if (!failedProviders.isEmpty()) {
            String summary = String.join("; ", failedProviders);
            throw new IllegalStateException(
                "Application startup aborted: " + failedProviders.size()
                + " tool provider(s) failed to register. "
                + "The application would run in a degraded state with missing tools. "
                + "Fix the underlying issue before restarting. Failures: [" + summary + "]"
            );
        }

        log.info("Agent tools registration complete: {} total tools registered, {} interceptors active",
            totalTools, interceptors.size());
    }

    /**
     * Get the provider that can handle a specific tool.
     * Uses O(1) cache lookup.
     */
    public ToolsProvider getProviderForTool(String toolName) {
        // O(1) lookup from cache
        ToolsProvider cached = toolToProviderCache.get(toolName);
        if (cached != null) {
            return cached;
        }
        
        // Fallback to linear search (for dynamically registered tools)
        for (ToolsProvider provider : providers) {
            if (provider.canHandle(toolName)) {
                toolToProviderCache.put(toolName, provider); // Cache for next time
                return provider;
            }
        }
        return null;
    }

    /**
     * Shared pre-execution pipeline result: either normalized parameters ready
     * for the provider, or a populated failure to short-circuit.
     *
     * <p>Kept in one type so sync and async callers can't drift out of order -
     * both must go through {@link #aliasCoerceValidate(String, Map)}.
     */
    private record PreExecResult(Map<String, Object> params, ToolsProvider.ToolExecutionResult failure) {
        static PreExecResult ok(Map<String, Object> params) {
            return new PreExecResult(params, null);
        }
        static PreExecResult fail(ToolsProvider.ToolExecutionResult failure) {
            return new PreExecResult(null, failure);
        }
    }

    /**
     * Apply the alias-rename → slim-schema-coerce → parameter-validate pipeline
     * exactly once. Called by both the sync and async tool-execution paths so
     * neither can drift: any new pre-execution step belongs here.
     */
    private PreExecResult aliasCoerceValidate(String toolName, Map<String, Object> parameters) {
        // Apply parameter aliasing before validation and execution
        // This helps LLMs that may use outdated parameter names (e.g., 'input' instead of 'parameters')
        Map<String, Object> normalizedParams = parameters;
        if (validator != null) {
            normalizedParams = validator.applyParameterAliases(parameters);
        }

        // Stage 4a.1b: reverse-coerce string-typed slim-schema inputs back to their
        // real declared types before the validator sees them. No-op for tools not
        // registered through a slim path (coercer returns input unchanged).
        if (slimCoercer != null) {
            normalizedParams = slimCoercer.coerce(toolName, normalizedParams);
        }

        // Validate parameters (if validator is available)
        if (validator != null) {
            ValidationResult validation = validator.validate(toolName, normalizedParams);
            if (!validation.isValid()) {
                log.debug("Validation failed for tool {}: {}", toolName, validation.formatErrors());
                return PreExecResult.fail(ToolsProvider.ToolExecutionResult.failure(
                    validation.getPrimaryErrorCode() != null
                        ? validation.getPrimaryErrorCode()
                        : ToolErrorCode.VALIDATION_ERROR,
                    validation.formatErrors(),
                    Map.of("validationErrors", validation.errors().stream()
                        .map(e -> Map.of(
                            "parameter", e.parameterName() != null ? e.parameterName() : "",
                            "message", e.message(),
                            "code", e.errorCode().getCode()
                        ))
                        .collect(Collectors.toList()))
                ));
            }
        }

        return PreExecResult.ok(normalizedParams);
    }

    /**
     * Execute a tool by name with automatic validation and interceptors.
     */
    public ToolsProvider.ToolExecutionResult executeTool(
            String toolName,
            Map<String, Object> parameters,
            ToolsProvider.ToolExecutionContext context) {

        long startTime = System.currentTimeMillis();

        // Find provider
        ToolsProvider provider = getProviderForTool(toolName);
        if (provider == null) {
            return ToolsProvider.ToolExecutionResult.failure(
                ToolErrorCode.TOOL_NOT_FOUND,
                "Tool not found: " + toolName
            );
        }

        // Stage 4a.1b: alias → coerce → validate. Shared between sync and async
        // paths to guarantee both see the same pre-execution pipeline.
        PreExecResult pre = aliasCoerceValidate(toolName, parameters);
        if (pre.failure() != null) {
            return pre.failure();
        }
        Map<String, Object> normalizedParams = pre.params();

        // Run before interceptors (with normalized params)
        for (ToolExecutionInterceptor interceptor : interceptors) {
            try {
                interceptor.beforeExecution(toolName, normalizedParams, context);
            } catch (Exception e) {
                log.warn("Interceptor {} threw exception in beforeExecution: {}",
                    interceptor.getClass().getSimpleName(), e.getMessage());
            }
        }

        // Execute tool with normalized parameters
        ToolsProvider.ToolExecutionResult result;
        try {
            result = provider.execute(toolName, normalizedParams, context);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            
            // Run error interceptors
            for (ToolExecutionInterceptor interceptor : interceptors) {
                try {
                    interceptor.onError(toolName, e, duration);
                } catch (Exception ie) {
                    log.warn("Interceptor threw exception in onError: {}", ie.getMessage());
                }
            }
            
            return ToolsProvider.ToolExecutionResult.failure(
                ToolErrorCode.EXECUTION_FAILED,
                "Tool execution failed: " + e.getMessage()
            );
        }

        long duration = System.currentTimeMillis() - startTime;

        // Run after interceptors
        for (ToolExecutionInterceptor interceptor : interceptors) {
            try {
                interceptor.afterExecution(toolName, result, duration);
            } catch (Exception e) {
                log.warn("Interceptor {} threw exception in afterExecution: {}",
                    interceptor.getClass().getSimpleName(), e.getMessage());
            }
        }

        return result;
    }

    /**
     * Execute a tool asynchronously.
     */
    public CompletableFuture<ToolsProvider.ToolExecutionResult> executeToolAsync(
            String toolName,
            Map<String, Object> parameters,
            ToolsProvider.ToolExecutionContext context) {

        ToolsProvider provider = getProviderForTool(toolName);
        if (provider == null) {
            return CompletableFuture.completedFuture(
                ToolsProvider.ToolExecutionResult.failure(
                    ToolErrorCode.TOOL_NOT_FOUND,
                    "Tool not found: " + toolName
                )
            );
        }

        // Stage 4a.1b: same alias → coerce → validate pipeline as executeTool.
        PreExecResult pre = aliasCoerceValidate(toolName, parameters);
        if (pre.failure() != null) {
            return CompletableFuture.completedFuture(pre.failure());
        }

        // Capture normalized params for async execution
        final Map<String, Object> finalParams = pre.params();
        final String orgIdForWorker = context != null ? context.orgId() : null;
        // Bind BOTH the org id AND the gateway-validated org role onto the async
        // worker thread. The prior 2-arg call bound only the id, leaving
        // currentRequestOrganizationRole() null on the worker - so every
        // service-layer role gate read from TenantResolver (the agent VIEWER write
        // gate, the OrgAccessGuard deny-list, the getAgent self-heal) silently fell
        // to its null-role / fail-open branch on the async path. orgRole is the same
        // validated value the sync path already trusts (gateway / CE filter injected,
        // client copies stripped - see AgentToolsController).
        final String orgRoleForWorker = context != null ? context.orgRole() : null;

        // Execute asynchronously with interceptors
        return CompletableFuture.supplyAsync(() -> {
            ToolsProvider.ToolExecutionResult[] resultHolder = new ToolsProvider.ToolExecutionResult[1];
            TenantResolver.runWithOrgScope(orgIdForWorker, orgRoleForWorker, () -> {
                long startTime = System.currentTimeMillis();

                // Before interceptors
                for (ToolExecutionInterceptor interceptor : interceptors) {
                    try {
                        interceptor.beforeExecution(toolName, finalParams, context);
                    } catch (Exception e) {
                        log.warn("Interceptor threw exception: {}", e.getMessage());
                    }
                }

                ToolsProvider.ToolExecutionResult result;
                try {
                    result = provider.execute(toolName, finalParams, context);
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - startTime;
                    for (ToolExecutionInterceptor interceptor : interceptors) {
                        try {
                            interceptor.onError(toolName, e, duration);
                        } catch (Exception ie) {
                            log.warn("Interceptor threw exception: {}", ie.getMessage());
                        }
                    }
                    resultHolder[0] = ToolsProvider.ToolExecutionResult.failure(
                        ToolErrorCode.EXECUTION_FAILED,
                        "Async execution failed: " + e.getMessage()
                    );
                    return;
                }

                long duration = System.currentTimeMillis() - startTime;
                for (ToolExecutionInterceptor interceptor : interceptors) {
                    try {
                        interceptor.afterExecution(toolName, result, duration);
                    } catch (Exception e) {
                        log.warn("Interceptor threw exception: {}", e.getMessage());
                    }
                }

                resultHolder[0] = result;
            });
            return resultHolder[0];
        });
    }
    
    /**
     * Get core tool definitions as ToolDefinition objects, filtered by name.
     * Converts from AgentToolDefinition (registry format) to ToolDefinition (LLM format).
     *
     * @param toolNames set of tool names to include; null means all registered tools
     * @return list of ToolDefinition for the matching tools
     */
    public List<com.apimarketplace.agent.domain.ToolDefinition> getCoreToolDefinitions(java.util.Set<String> toolNames) {
        return registry.getAllTools().stream()
            .filter(atd -> toolNames == null || toolNames.contains(atd.name()))
            .map(atd -> com.apimarketplace.agent.domain.ToolDefinition.builder()
                .id(atd.name())
                .name(atd.name())
                .description(atd.description())
                .parameters(atd.parameters())
                .requiredParameters(atd.requiredParameters())
                .metadata(java.util.Map.of("source", "core_tool"))
                .timeoutMs(atd.timeoutMs())
                .build())
            .toList();
    }

    /**
     * Get cache statistics for monitoring.
     */
    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cachedTools", toolToProviderCache.size(),
            "providers", providers.size(),
            "interceptors", interceptors.size()
        );
    }
}
