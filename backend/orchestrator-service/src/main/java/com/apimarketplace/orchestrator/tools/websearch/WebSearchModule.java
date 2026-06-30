package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.agent.tools.common.ToolModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Module for web search via SearXNG.
 * Handles action: search
 * Calls POST /search on websearch-service.
 *
 * <p><b>Billing</b> &mdash; each successful search posts a {@code WEB_SEARCH}
 * debit to auth-service. Auth-service owns the fixed credit price via
 * {@code billing.websearch.credits-per-search} (default 1). Post-success debit
 * goes through the async client with retry + dead-letter so a flaky auth-service
 * does not add latency to the search call. Failures from SearXNG (HTTP non-2xx,
 * empty body) skip billing entirely.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchModule implements ToolModule {

    private static final Set<String> HANDLED_ACTIONS = Set.of("search");
    private static final String BILLING_PROVIDER = "websearch";
    private static final String BILLING_MODEL = "default";

    private final RestTemplate restTemplate;
    private final WebSearchConfig config;
    private final CreditConsumptionClient creditClient;

    public WebSearchModule(@Qualifier("webSearchRestTemplate") RestTemplate restTemplate,
                           WebSearchConfig config,
                           CreditConsumptionClient creditClient) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.creditClient = creditClient;
        log.info("[WebSearchModule] Injected RestTemplate interceptors: {}", restTemplate.getInterceptors().size());
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
        if (!"search".equals(action)) return Optional.empty();
        return Optional.of(executeSearch(parameters, tenantId, context));
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeSearch(Map<String, Object> parameters,
                                               String tenantId,
                                               ToolExecutionContext context) {
        String query = (String) parameters.get("query");
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Parameter 'query' is required for action 'search'");
        }

        int maxResults = 10;
        Object maxResultsParam = parameters.get("max_results");
        if (maxResultsParam instanceof Number n) {
            maxResults = n.intValue();
        }

        String timeRange = (String) parameters.get("time_range");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        requestBody.put("max_results", maxResults);
        String normalizedTimeRange = normalizeTimeRange(timeRange);
        if (normalizedTimeRange != null) {
            requestBody.put("time_range", normalizedTimeRange);
        } else if (timeRange != null) {
            // Caller supplied something, but we can't map it → drop the param
            // so SearXNG doesn't return 400 on an alias it doesn't recognise.
            log.warn("web_search: dropping unrecognised time_range='{}' (LLM hallucination?). "
                    + "Valid values: day|week|month|year (or SerpAPI-style past_day|past_week|past_month|past_year).",
                    timeRange);
        }

        try {
            String url = config.getServiceUrl() + "/search";
            log.debug("Calling websearch-service: POST {} with query='{}'", url, query);

            Map<String, Object> response = restTemplate.postForObject(url, requestBody, Map.class);

            if (response == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "No response from websearch-service");
            }

            // Post-success billing. Async + retry + dead-letter via the
            // existing client. On 402 the async client logs a warn and gives up
            // without surfacing to the user;
            // that's the same posture as workflow_node billing.
            chargeForSearch(tenantId, context);

            return ToolExecutionResult.success(response);

        } catch (Exception e) {
            log.error("Web search failed for query '{}': {}", query, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Web search failed: " + e.getMessage());
        }
    }

    /**
     * Builds the idempotency-safe sourceId from the credentials map and fires
     * the async debit. If the credentials carry neither a chat-scope nor a
     * workflow-scope identifier triple the call falls back to a UUID - better
     * than no debit, but logged as a WARN so the gap is visible. In normal
     * agent execution the {@code __toolCallId__} field is always set.
     */
    private void chargeForSearch(String tenantId, ToolExecutionContext context) {
        String billingTenantId = resolveTenantId(tenantId, context);
        if (billingTenantId == null || billingTenantId.isBlank()) {
            log.debug("web_search: skipping debit - no tenantId in context");
            return;
        }
        Map<String, Object> creds = context != null ? context.credentials() : null;
        String sourceId = resolveBillingSourceId(creds);
        try {
            creditClient.consumeCreditsAsync(billingTenantId, "WEB_SEARCH", sourceId,
                    BILLING_PROVIDER, BILLING_MODEL, 0, 0);
        } catch (RuntimeException e) {
            log.warn("web_search: failed to enqueue debit for tenant={} sourceId={}: {}",
                    billingTenantId, sourceId, e.getMessage(), e);
        }
    }

    private static String resolveTenantId(String tenantId, ToolExecutionContext context) {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        return context != null ? context.tenantId() : null;
    }

    static String resolveBillingSourceId(Map<String, Object> credentials) {
        String streamId = credentials != null ? (String) credentials.get("__streamId__") : null;
        String toolCallId = credentials != null ? (String) credentials.get("__toolCallId__") : null;
        String runId = credentials != null ? (String) credentials.get("__workflowRunId__") : null;
        if (runId != null && toolCallId != null) {
            return SourceIdBuilder.webSearchDebitWorkflow(runId, toolCallId, 0);
        }
        if (streamId != null && toolCallId != null) {
            return SourceIdBuilder.webSearchDebitChat(streamId, toolCallId, 0);
        }
        log.warn("web_search: no chat/workflow identifiers in credentials - falling back to UUID sourceId. "
                + "Retries will produce duplicate ledger rows. Check AgentContextBuilder propagation.");
        return SourceIdBuilder.WEB_SEARCH_PREFIX + ":FALLBACK:" + UUID.randomUUID();
    }

    /**
     * Normalise {@code time_range} to one of the four SearXNG-supported
     * values: {@code day | week | month | year}. Accepts a handful of LLM
     * hallucinations / common aliases so a misremembered SerpAPI / Bing-style
     * value doesn't bubble up a 400 → 502 chain:
     * <ul>
     *   <li>SerpAPI: {@code past_day / past_week / past_month / past_year}</li>
     *   <li>Short forms: {@code d / w / m / y}</li>
     *   <li>Relative durations: {@code 1d / 24h / 7d / 30d / 1y}</li>
     *   <li>Verbose: {@code last_day / last_week / last_month / last_year},
     *       {@code daily / weekly / monthly / yearly}, {@code today}, {@code 24hours}</li>
     * </ul>
     * Unknown values return {@code null} - caller should drop the param rather
     * than forward to SearXNG.
     */
    static String normalizeTimeRange(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase().replace('-', '_');
        return switch (s) {
            // Canonical
            case "day", "week", "month", "year" -> s;
            // SerpAPI style
            case "past_day", "past_week", "past_month", "past_year" -> s.substring("past_".length());
            // "last_X"
            case "last_day", "last_week", "last_month", "last_year" -> s.substring("last_".length());
            // Adjective form
            case "daily"   -> "day";
            case "weekly"  -> "week";
            case "monthly" -> "month";
            case "yearly", "annual", "annually" -> "year";
            // Short letter
            case "d" -> "day";
            case "w" -> "week";
            case "m" -> "month";
            case "y" -> "year";
            // Today / now
            case "today", "24h", "24hours", "24_hours", "1d", "1day" -> "day";
            case "7d", "7days", "1week"  -> "week";
            case "30d", "30days", "1month" -> "month";
            case "365d", "365days", "1year" -> "year";
            default -> null;
        };
    }
}
