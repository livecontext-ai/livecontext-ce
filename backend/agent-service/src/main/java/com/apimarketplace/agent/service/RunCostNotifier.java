package com.apimarketplace.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Notifies the orchestrator of a settled agent cost so it can accumulate the
 * workflow run's cost and broadcast it to the run-mode UI. Agent executions are
 * the only cost source inside a run.
 *
 * <p>Fully best-effort and fire-and-forget: the HTTP call runs on a small
 * bounded executor so it never blocks (nor rolls back) the caller's
 * observability/credit transaction, and every failure is swallowed with a WARN.
 * A dropped notification only means the run's live cost figure misses one
 * increment - the ledger + agent_executions rows remain the source of truth.
 */
@Component
public class RunCostNotifier {

    private static final Logger log = LoggerFactory.getLogger(RunCostNotifier.class);

    private final RestTemplate restTemplate;
    private final String orchestratorUrl;
    private final ExecutorService executor;

    public RunCostNotifier(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        this.orchestratorUrl = orchestratorUrl;
        this.restTemplate = new org.springframework.boot.web.client.RestTemplateBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(3))
                .build();
        // Bounded pool + caller-runs is irrelevant here: we DISCARD on saturation
        // (cost tracking is best-effort, never worth queueing unbounded work).
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "run-cost-notifier");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
        this.executor = pool;
    }

    /**
     * Fire-and-forget notify. No-op when there is no run to attribute to, or the
     * cost is zero/negative.
     *
     * @param runIdPublic    public run id (WS channel key on the orchestrator)
     * @param organizationId run's org id, or null for personal scope
     * @param tenantId       run owner tenant (forwarded as {@code X-User-ID})
     * @param epoch          epoch the agent executed in
     * @param credits        credits consumed (1 credit = $0.001)
     */
    public void notifyRunCost(String runIdPublic, String organizationId, String tenantId,
                              int epoch, BigDecimal credits) {
        if (runIdPublic == null || runIdPublic.isBlank()) {
            return;
        }
        if (credits == null || credits.signum() <= 0) {
            return;
        }
        try {
            executor.submit(() -> send(runIdPublic, organizationId, tenantId, epoch, credits));
        } catch (Exception e) {
            // Executor rejected (shutting down) - drop silently, best-effort.
            log.debug("[RunCost] notify submit dropped for runId={}: {}", runIdPublic, e.getMessage());
        }
    }

    private void send(String runIdPublic, String organizationId, String tenantId,
                      int epoch, BigDecimal credits) {
        try {
            String url = orchestratorUrl + "/api/internal/orchestrator/runs/" + runIdPublic + "/cost";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null) {
                headers.set("X-User-ID", tenantId);
            }
            if (organizationId != null && !organizationId.isBlank()) {
                headers.set("X-Organization-ID", organizationId);
            }
            Map<String, Object> body = new HashMap<>();
            body.put("organizationId", organizationId);
            body.put("epoch", epoch);
            body.put("credits", credits);
            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Void.class);
        } catch (Exception e) {
            log.warn("[RunCost] notify orchestrator failed for runId={} (non-critical): {}",
                    runIdPublic, e.getMessage());
        }
    }
}
