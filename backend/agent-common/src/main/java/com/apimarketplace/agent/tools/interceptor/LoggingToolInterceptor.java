package com.apimarketplace.agent.tools.interceptor;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default logging interceptor for tool execution.
 * Logs execution timing, success/failure status, and detailed request/response data.
 *
 * Log format for debugging LLM ↔ Tool interactions:
 * - [TOOL-IN] : What the LLM sent (tool name + full parameters)
 * - [TOOL-OUT]: What we returned to the LLM (success + full data OR error)
 */
@Slf4j
@Component
public class LoggingToolInterceptor implements ToolExecutionInterceptor {

    private static final AtomicLong requestCounter = new AtomicLong(0);
    private static final ThreadLocal<Long> currentRequestId = new ThreadLocal<>();

    private final ObjectMapper objectMapper;

    public LoggingToolInterceptor() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    @Override
    public void beforeExecution(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        long requestId = requestCounter.incrementAndGet();
        currentRequestId.set(requestId);

        String tenantId = context != null ? context.tenantId() : "anonymous";
        String paramsJson = toCompactJson(parameters);

        // Log what LLM sent - this is the INPUT
        log.info("[TOOL-IN][#{}] 🤖→🔧 {} | tenant={} | params={}",
            requestId,
            toolName,
            tenantId,
            paramsJson
        );
    }

    @Override
    public void afterExecution(String toolName, ToolExecutionResult result, long durationMs) {
        Long requestId = currentRequestId.get();
        String reqId = requestId != null ? String.valueOf(requestId) : "?";

        if (result.success()) {
            String dataJson = toCompactJson(result.data());
            String metaJson = result.metadata() != null && !result.metadata().isEmpty()
                ? " | meta=" + toCompactJson(result.metadata())
                : "";

            // Log what we return to LLM - this is the OUTPUT (success)
            log.info("[TOOL-OUT][#{}] 🔧→🤖 {} | ✅ {}ms | data={}{}",
                reqId,
                toolName,
                durationMs,
                truncateIfNeeded(dataJson, 2000),
                metaJson
            );
        } else {
            String errorCode = result.errorCode() != null ? result.errorCode().getCode() : "none";
            String metaJson = result.metadata() != null && !result.metadata().isEmpty()
                ? " | meta=" + toCompactJson(result.metadata())
                : "";

            // Log what we return to LLM - this is the OUTPUT (error)
            // Include metadata for validation errors, suggestions, etc.
            log.warn("[TOOL-OUT][#{}] 🔧→🤖 {} | ❌ {}ms | error={} | code={}{}",
                reqId,
                toolName,
                durationMs,
                truncateIfNeeded(result.error(), 500),
                errorCode,
                truncateIfNeeded(metaJson, 500)
            );
        }

        currentRequestId.remove();
    }

    @Override
    public void onError(String toolName, Exception exception, long durationMs) {
        Long requestId = currentRequestId.get();
        String reqId = requestId != null ? String.valueOf(requestId) : "?";

        log.error("[TOOL-OUT][#{}] 🔧→🤖 {} | 💥 {}ms | exception={}",
            reqId, toolName, durationMs, exception.getMessage(), exception);

        currentRequestId.remove();
    }

    @Override
    public int getOrder() {
        return 0; // Run first
    }

    /**
     * Convert object to compact JSON string for logging.
     */
    private String toCompactJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    /**
     * Truncate string if too long, adding indicator.
     */
    private String truncateIfNeeded(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...[TRUNCATED+" + (str.length() - maxLength) + "]";
    }
}
