package com.apimarketplace.agent.tools.interceptor;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.common.logging.PayloadLogSafety;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default logging interceptor for tool execution.
 * Logs execution timing and success/failure status.
 *
 * Log format for debugging LLM to tool interactions:
 * - [TOOL-IN] : what the LLM sent (tool name + parameter NAMES)
 * - [TOOL-OUT]: what we returned to the LLM (outcome + result SIZE, or the error)
 *
 * <p><b>Payloads are not logged.</b> Tool parameters and results carry end-user content: a
 * Gmail message body, a spreadsheet row, a document. Application logs are shipped to a
 * central store with a wider read audience than the data itself, so writing payloads there
 * creates a standing human-access channel over user data. Under Google's Limited Use
 * requirements, which apply to restricted scopes such as {@code gmail.readonly}, human access
 * to restricted-scope data is permitted only with the user's affirmative agreement, for
 * security, to comply with law, or on aggregated anonymized data.
 *
 * <p>Set {@code platform.logging.payloads=true} to restore bounded payload logging while
 * debugging LOCALLY. It must stay false in any environment that processes real user data. Even
 * with the flag ON:
 * <ul>
 *   <li>a result classified RESTRICTED by {@link RestrictedDataPolicy} (Gmail, Drive) is never
 *       printed, only described by keys and size: no debugging convenience justifies copying
 *       restricted-scope data into a log store;</li>
 *   <li>metadata goes through {@link ToolMediaMetadata#withoutHeavyMedia} so vision-channel image
 *       bytes never reach a log line, and every serialized payload is truncated (LC-034).</li>
 * </ul>
 *
 * <p>Hardening for LC-009 / LC-034 (CASA remediation).
 */
@Slf4j
@Component
public class LoggingToolInterceptor implements ToolExecutionInterceptor {

    private static final AtomicLong requestCounter = new AtomicLong(0);
    private static final ThreadLocal<Long> currentRequestId = new ThreadLocal<>();

    /**
     * Characters of an error message kept when payload logging is off. Enough to identify the
     * failure, short enough that a provider body echoed into the message is not reconstructable.
     */
    private static final int ERROR_MESSAGE_CAP = 200;

    /** Characters of a metadata map kept when payload logging is on. */
    private static final int METADATA_CAP = 500;

    /** Characters of a parameter map or result body kept when payload logging is on. */
    private static final int PAYLOAD_CAP = 2000;

    private final ObjectMapper objectMapper;

    /** When false (the default and the only safe production value), payloads are never logged. */
    private final boolean logPayloads;

    public LoggingToolInterceptor(
            @Value(PayloadLogSafety.PAYLOAD_LOGGING_PLACEHOLDER) boolean logPayloads) {
        this.logPayloads = logPayloads;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    @Override
    public void beforeExecution(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        long requestId = requestCounter.incrementAndGet();
        currentRequestId.set(requestId);

        String tenantId = context != null ? context.tenantId() : "anonymous";

        // Names only: the VALUES are user content (a Gmail search expression, a message body
        // being sent). The payload-on branch is bounded and media-stripped as well.
        log.info("[TOOL-IN][#{}] {} | tenant={} | params={}",
            requestId,
            toolName,
            tenantId,
            logPayloads
                ? truncateIfNeeded(toCompactJson(ToolMediaMetadata.withoutHeavyMedia(parameters)), PAYLOAD_CAP)
                : PayloadLogSafety.describeKeys(parameters)
        );
    }

    @Override
    public void afterExecution(String toolName, ToolExecutionResult result, long durationMs) {
        Long requestId = currentRequestId.get();
        String reqId = requestId != null ? String.valueOf(requestId) : "?";

        // A restricted-scope result is never printed, whatever the debug flag says.
        boolean printPayload = logPayloads
            && !RestrictedDataPolicy.fromToolMetadata(result.metadata()).isRestricted();

        String metaJson = result.metadata() != null && !result.metadata().isEmpty()
            ? " | meta=" + (printPayload
                ? truncateIfNeeded(
                    toCompactJson(ToolMediaMetadata.withoutHeavyMedia(result.metadata())),
                    METADATA_CAP)
                : PayloadLogSafety.describeKeys(result.metadata()))
            : "";

        if (result.success()) {
            // The body is serialized ONLY when it is going to be printed or measured.
            log.info("[TOOL-OUT][#{}] {} | OK {}ms | data={}{}",
                reqId,
                toolName,
                durationMs,
                printPayload
                    ? truncateIfNeeded(toCompactJson(result.data()), PAYLOAD_CAP)
                    : PayloadLogSafety.describeSize(result.data(), this::toCompactJson),
                metaJson
            );
        } else {
            String errorCode = result.errorCode() != null ? result.errorCode().getCode() : "none";
            // The error string is KEPT because it is what makes a failure diagnosable, but it is
            // not assumed to be developer-authored: an HTTP execution path that cannot classify a
            // provider failure returns the provider's raw response body. So it is capped hard.
            log.warn("[TOOL-OUT][#{}] {} | FAILED {}ms | error={} | code={}{}",
                reqId,
                toolName,
                durationMs,
                printPayload
                    ? truncateIfNeeded(result.error(), 500)
                    : PayloadLogSafety.capMessage(result.error(), ERROR_MESSAGE_CAP),
                errorCode,
                metaJson
            );
        }

        currentRequestId.remove();
    }

    @Override
    public void onError(String toolName, Exception exception, long durationMs) {
        Long requestId = currentRequestId.get();
        String reqId = requestId != null ? String.valueOf(requestId) : "?";

        // A parse failure quotes the offending source content inside the message AND the stack
        // trace. Attach the throwable only when payload logging is on; otherwise log its type.
        if (logPayloads) {
            log.error("[TOOL-OUT][#{}] {} | EXCEPTION {}ms | exception={}",
                reqId, toolName, durationMs, exception.getMessage(), exception);
        } else {
            log.error("[TOOL-OUT][#{}] {} | EXCEPTION {}ms | {}: {}",
                reqId, toolName, durationMs,
                exception.getClass().getName(),
                PayloadLogSafety.capMessage(exception.getMessage(), ERROR_MESSAGE_CAP));
        }

        currentRequestId.remove();
    }

    @Override
    public int getOrder() {
        return 0; // Run first
    }

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

    private String truncateIfNeeded(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...[TRUNCATED+" + (str.length() - maxLength) + "]";
    }
}
