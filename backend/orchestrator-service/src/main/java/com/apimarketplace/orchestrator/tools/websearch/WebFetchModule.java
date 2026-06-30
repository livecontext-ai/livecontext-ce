package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Module for web fetching (single URL or batch).
 * Handles action: fetch
 *
 * Submits a job to websearch-service then awaits the result on Redis (LIST,
 * single entry). Implementation lives in {@link WebJobModule}; this class
 * only configures the action wiring, the concurrency budget and the
 * fetch-specific request/response shape.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class WebFetchModule extends WebJobModule {

    private static final Set<String> HANDLED_ACTIONS = Set.of("fetch");

    /** Backpressure: limit concurrent web fetches across all threads. */
    private static final int CONCURRENCY_LIMIT = 30;

    private static final String BILLING_PROVIDER = "websearch";
    private static final String BILLING_MODEL = "default";

    private final CreditConsumptionClient creditClient;

    public WebFetchModule(
            @Qualifier("webSearchRestTemplate") RestTemplate restTemplate,
            WebSearchConfig config,
            @Qualifier("webSearchRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CreditConsumptionClient creditClient) {
        super(restTemplate, config, redisTemplate, objectMapper, CONCURRENCY_LIMIT);
        this.creditClient = creditClient;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of(); // Definitions managed by WebSearchToolsProvider
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        ToolExecutionResult result = submitAndAwait(action, parameters, tenantId, context);

        // Post-success billing. A fetch is billed as a single WEB_FETCH debit
        // regardless of how many URLs were batched in the call - mirrors the
        // per-call posture of WEB_SEARCH. Failures (timeout, non-2xx, error
        // payload) skip billing. Async + retry + dead-letter via the client so
        // a flaky auth-service adds no latency to the fetch call.
        if (result != null && result.success()) {
            chargeForFetch(tenantId, context);
        }
        return Optional.of(result);
    }

    /**
     * Builds the idempotency-safe sourceId from the credentials map and fires
     * the async WEB_FETCH debit. Falls back to a UUID-based sourceId (logged at
     * WARN) when neither a chat-scope nor a workflow-scope identifier triple is
     * present - better than skipping the debit. In normal agent execution the
     * {@code __toolCallId__} field is always set.
     */
    private void chargeForFetch(String tenantId, ToolExecutionContext context) {
        String billingTenantId = tenantId != null && !tenantId.isBlank()
                ? tenantId
                : (context != null ? context.tenantId() : null);
        if (billingTenantId == null || billingTenantId.isBlank()) {
            log.debug("web_fetch: skipping debit - no tenantId in context");
            return;
        }
        Map<String, Object> creds = context != null ? context.credentials() : null;
        String sourceId = resolveBillingSourceId(creds);
        try {
            creditClient.consumeCreditsAsync(billingTenantId, "WEB_FETCH", sourceId,
                    BILLING_PROVIDER, BILLING_MODEL, 0, 0);
        } catch (RuntimeException e) {
            log.warn("web_fetch: failed to enqueue debit for tenant={} sourceId={}: {}",
                    billingTenantId, sourceId, e.getMessage(), e);
        }
    }

    static String resolveBillingSourceId(Map<String, Object> credentials) {
        String streamId = credentials != null ? (String) credentials.get("__streamId__") : null;
        String toolCallId = credentials != null ? (String) credentials.get("__toolCallId__") : null;
        String runId = credentials != null ? (String) credentials.get("__workflowRunId__") : null;
        if (runId != null && toolCallId != null) {
            return SourceIdBuilder.webFetchDebitWorkflow(runId, toolCallId, 0);
        }
        if (streamId != null && toolCallId != null) {
            return SourceIdBuilder.webFetchDebitChat(streamId, toolCallId, 0);
        }
        log.warn("web_fetch: no chat/workflow identifiers in credentials - falling back to UUID sourceId. "
                + "Retries will produce duplicate ledger rows. Check AgentContextBuilder propagation.");
        return SourceIdBuilder.WEB_FETCH_PREFIX + ":FALLBACK:" + UUID.randomUUID();
    }

    @Override
    protected Map<String, Object> buildJobParameters(Map<String, Object> parameters,
                                                     ToolExecutionContext context) {
        Map<String, Object> jobParams = new LinkedHashMap<>();

        // Screenshots are disabled - the chat UI shows favicon stacks on the tool row
        // instead of a sidepanel preview. Setting the flag explicitly so the
        // websearch-service does not waste CDP cycles capturing them.
        jobParams.put("screenshots", false);

        // Batch (urls[], capped at maxParallelFetches) or single URL
        Object urlsParam = parameters.get("urls");
        if (urlsParam instanceof List<?> urlsList && !urlsList.isEmpty()) {
            int max = config.getMaxParallelFetches();
            int kept = Math.min(urlsList.size(), max);
            List<String> urls = new ArrayList<>(kept);
            for (int i = 0; i < kept; i++) {
                urls.add(String.valueOf(urlsList.get(i)));
            }
            if (urlsList.size() > max) {
                log.info("Capping fetch URLs from {} to {} (max-parallel={})",
                    urlsList.size(), max, max);
            }
            jobParams.put("urls", urls);
        } else {
            jobParams.put("url", parameters.get("url"));
        }

        return jobParams;
    }

    @Override
    protected Map<String, Object> postProcess(Map<String, Object> response,
                                              Map<String, Object> parameters,
                                              ToolExecutionContext context) {
        cleanScreenshots(response);
        // Cap any oversized strings - most commonly the scraped page
        // markdown/content body, which can run >100 KB on long articles
        // and silently consume the agent's tool-result token budget.
        // Same shared walker the workflow path uses; FileRefs and small
        // strings pass through verbatim.
        return com.apimarketplace.agent.tools.common.ToolResultSizeCap.capLargeStrings(response);
    }
}
