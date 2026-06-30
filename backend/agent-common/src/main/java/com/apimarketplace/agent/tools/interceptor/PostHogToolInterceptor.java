package com.apimarketplace.agent.tools.interceptor;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits a PII-free {@code tool_call_completed} product-analytics event (PostHog)
 * for every agent / workflow tool call - so the owner can see WHICH tools and
 * APIs succeed vs fail (the core "which features work well" signal).
 *
 * <p>No-op unless analytics is configured; never throws (and {@link
 * com.apimarketplace.agent.tools.ToolsRegistrationService} also guards interceptor
 * exceptions). Emits only tool name / success / error-code / duration / org -
 * never parameters or result payloads (those can carry PII/content).</p>
 *
 * <p>The interface passes the execution context only to {@link #beforeExecution},
 * not to {@link #afterExecution}/{@link #onError}. {@code ToolsRegistrationService}
 * runs before → execute → after|onError sequentially on the SAME thread (the sync
 * path, and the async path inside a single {@code supplyAsync}/{@code runWithOrgScope}
 * lambda), so a {@link ThreadLocal} reliably carries the tenant/org across. It is
 * always cleared in after/onError; on the (impossible-here) thread mismatch the
 * event is simply dropped - never misattributed.</p>
 */
@Component
public class PostHogToolInterceptor implements ToolExecutionInterceptor {

    private record CallScope(String tenantId, String orgId, String workflowId) {}

    private static final ThreadLocal<CallScope> SCOPE = new ThreadLocal<>();

    /**
     * Field-injected (required=false) to match the other PostHog emit sites and
     * stay resilient if the analytics auto-config is ever excluded - the bean is
     * then null and every method is a no-op.
     */
    @Autowired(required = false)
    private PostHogAnalyticsClient postHog;

    @Override
    public void beforeExecution(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (postHog == null || !postHog.isActive()) return;
        SCOPE.set(new CallScope(
                context != null ? context.tenantId() : null,
                context != null ? context.orgId() : null,
                context != null ? context.viewingWorkflowId() : null));
    }

    @Override
    public void afterExecution(String toolName, ToolExecutionResult result, long durationMs) {
        try {
            CallScope scope = SCOPE.get();
            if (scope == null || postHog == null || !postHog.isActive()) return;
            boolean success = result != null && result.success();
            String errorCode = (!success && result != null && result.errorCode() != null)
                    ? result.errorCode().getCode() : null;
            postHog.capture(scope.tenantId(), "tool_call_completed",
                    buildToolCallProps(toolName, success, durationMs, scope.orgId(), scope.workflowId(), errorCode, null));
        } catch (Exception ignored) {
            // best-effort analytics
        } finally {
            SCOPE.remove();
        }
    }

    @Override
    public void onError(String toolName, Exception exception, long durationMs) {
        try {
            CallScope scope = SCOPE.get();
            if (scope == null || postHog == null || !postHog.isActive()) return;
            String errorType = exception != null ? exception.getClass().getSimpleName() : null;
            postHog.capture(scope.tenantId(), "tool_call_completed",
                    buildToolCallProps(toolName, false, durationMs, scope.orgId(), scope.workflowId(), "EXCEPTION", errorType));
        } catch (Exception ignored) {
            // best-effort analytics
        } finally {
            SCOPE.remove();
        }
    }

    @Override
    public int getOrder() {
        return 50; // after the logging interceptor (0), before any later ones
    }

    /** PII-free property bag for {@code tool_call_completed}. Package-private + static for unit testing.
     * {@code workflowId} is the viewing-workflow id when the call ran in workflow context
     * (best-available attribution; the run/agent-execution id is not on ToolExecutionContext). */
    static Map<String, Object> buildToolCallProps(String toolName, boolean success, long durationMs,
                                                  String orgId, String workflowId, String errorCode, String errorType) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tool_name", toolName);
        props.put("success", success);
        props.put("duration_ms", durationMs);
        if (orgId != null) props.put("organization_id", orgId);
        if (workflowId != null) props.put("workflow_id", workflowId);
        if (errorCode != null) props.put("error_code", errorCode);
        if (errorType != null) props.put("error_type", errorType);
        return props;
    }
}
