package com.apimarketplace.orchestrator.lifecycle;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Returns {@code 503 Service Unavailable} with {@code Retry-After: 10} on POST endpoints
 * that GROW the in-flight work set when the orchestrator is {@code DRAINING}. Allows
 * shutdown-relevant writes (cancel, pause, stop) to pass - ops needs them to drain stuck
 * runs during a planned restart.
 *
 * <h2>Scope</h2>
 *
 * <p>Matches the four entry points that create new in-flight work:
 * <ul>
 *   <li>{@code POST /api/workflows/{workflowId}/start} - fresh run</li>
 *   <li>{@code POST /api/workflows/runs/{runId}/resume} - wake a paused/awaiting run</li>
 *   <li>{@code POST /api/workflows/runs/{runId}/rerun} - re-execute completed work</li>
 *   <li>{@code POST /api/workflows/runs/{runId}/step} - manual step advance</li>
 * </ul>
 *
 * <p>Other writes (cancel, pause, stop, deletion) REDUCE the in-flight set and are
 * intentionally not gated - they remain available during drain.
 *
 * <p>Read paths ({@code GET}) and webhook-trigger paths
 * ({@code POST /api/webhooks/...}) pass through unchanged. Frontend polling stays
 * responsive during drain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class OrchestratorLifecycleGateFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorLifecycleGateFilter.class);

    /**
     * Path patterns that GROW the in-flight set. Real prod mounts live under
     * {@code /api/v2/workflows/dag/*} - verified against:
     * <ul>
     *   <li>{@code WorkflowExecutionController}: {@code POST /execute},
     *       {@code POST /{workflowId}/runs/{runId}/start}</li>
     *   <li>{@code WorkflowRunController}: {@code POST /runs/{runId}/resume},
     *       {@code POST /runs/{runId}/rerun/{stepId}}</li>
     *   <li>{@code StepByStepController}: {@code POST /runs/{runId}/step/{stepId}/execute},
     *       {@code POST /runs/{runId}/start-step-by-step},
     *       {@code POST /runs/{runId}/core/{coreId}/execute},
     *       {@code POST /runs/{runId}/step-by-step/{stepId}/execute}</li>
     * </ul>
     *
     * <p>Maintained as a small list rather than a single regex so each entry stays easy
     * to grep when refactoring controllers.
     */
    private static final List<String> GATED_POST_PATHS = List.of(
        "/api/v2/workflows/dag/execute",
        "/api/v2/workflows/dag/*/runs/*/start",
        "/api/v2/workflows/dag/runs/*/resume",
        "/api/v2/workflows/dag/runs/*/rerun/*",
        "/api/v2/workflows/dag/runs/*/start-step-by-step",
        "/api/v2/workflows/dag/runs/*/step/*/execute",
        "/api/v2/workflows/dag/runs/*/step-by-step/*/execute",
        "/api/v2/workflows/dag/runs/*/core/*/execute"
    );

    private final OrchestratorLifecycleGate gate;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public OrchestratorLifecycleGateFilter(OrchestratorLifecycleGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (gate.isDraining() && isGatedWrite(request)) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "10");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"orchestrator_draining\",\"retry_after_seconds\":10}");
            logger.info("[LifecycleGate] 503 - {} {} refused (DRAINING)",
                request.getMethod(), request.getRequestURI());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isGatedWrite(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return false;
        String uri = request.getRequestURI();
        if (uri == null) return false;
        for (String pattern : GATED_POST_PATHS) {
            if (pathMatcher.match(pattern, uri)) return true;
        }
        return false;
    }
}
