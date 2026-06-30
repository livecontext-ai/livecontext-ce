package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core.HttpAuthConfig;
import com.apimarketplace.orchestrator.domain.workflow.Core.HttpParam;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.apimarketplace.common.web.NoRedirectSimpleClientHttpRequestFactory;
import com.apimarketplace.common.web.UrlSafetyValidator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.util.LinkedMultiValueMap;

/**
 * HTTP Request node - Makes HTTP calls to external APIs.
 *
 * Flow:
 * 1. Resolve URL and body expressions using SpEL templates
 * 2. Apply authentication configuration
 * 3. Execute HTTP request
 * 4. Return response data including status, headers, and body
 *
 * Supports:
 * - Methods: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
 * - Auth: none, basic, bearer, api-key, custom-header
 * - Body types: none, json, form-data, x-www-form-urlencoded, raw
 */
public class HttpRequestNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestNode.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String method;
    private final String urlExpression;
    private final String authType;
    private final HttpAuthConfig authConfig;
    private final List<HttpParam> queryParams;
    private final List<HttpParam> headers;
    private final String bodyType;
    private final String bodyExpression;
    private final String contentType;
    private final Integer timeout;

    // Injected service
    private RestTemplate restTemplate;
    /** F2.3 - optional cancel signal source. Null in unit tests that don't wire Redis. */
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    /**
     * Static cache of RestTemplate instances keyed by timeout (ms). Pre-fix
     * `resolveRestTemplate` allocated a fresh RestTemplate + SimpleClientHttpRequestFactory
     * on every node execution when timeout was configured - under split/loop
     * fan-out (Gmail Auto-Labeler classifies ~30 emails in parallel), that
     * burned one socket pool + one factory per call with no warmup, no keep-alive
     * reuse, no pooled connections. Cache shares one configured RestTemplate per
     * unique timeout value across all node instances + all calls.
     *
     * <p>Capacity bound: timeout values come from user configuration so the
     * keyspace is small in practice; nevertheless we cap at 32 distinct
     * timeouts using a synchronized {@link java.util.LinkedHashMap} with
     * access-order eviction so a runaway templating bug can't grow this map
     * unbounded.
     */
    private static final int TIMEOUT_TEMPLATE_CACHE_SIZE = 32;
    private static final Map<Integer, RestTemplate> TIMEOUT_TEMPLATE_CACHE =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, RestTemplate> eldest) {
                    return size() > TIMEOUT_TEMPLATE_CACHE_SIZE;
                }
            });

    public HttpRequestNode(
            String nodeId,
            String method,
            String urlExpression,
            String authType,
            HttpAuthConfig authConfig,
            List<HttpParam> queryParams,
            List<HttpParam> headers,
            String bodyType,
            String bodyExpression,
            String contentType,
            Integer timeout
    ) {
        super(nodeId, NodeType.HTTP_REQUEST);
        this.method = method != null ? method.toUpperCase(Locale.ROOT) : "GET";
        this.urlExpression = urlExpression;
        this.authType = authType != null ? authType : "none";
        this.authConfig = authConfig;
        this.queryParams = queryParams != null ? queryParams : List.of();
        this.headers = headers != null ? headers : List.of();
        this.bodyType = bodyType != null ? bodyType : "none";
        this.bodyExpression = bodyExpression;
        this.contentType = contentType;
        this.timeout = timeout;
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Accepts services from the registry.
     * HttpRequestNode needs RestTemplate for HTTP calls.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.restTemplate = registry.getRestTemplate();
        this.workflowRedisPublisher = registry.getWorkflowRedisPublisher();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("HTTP Request node executing: nodeId={}, method={}, itemId={}",
            nodeId, method, context.itemId());

        // Build minimal resolved_params early so it is available in all failure paths
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("method", method);
        inputData.put("url", urlExpression);
        if (bodyType != null) inputData.put("bodyType", bodyType);
        if (authType != null && !"none".equals(authType)) inputData.put("authType", authType);
        if (timeout != null && timeout > 0) inputData.put("timeout", timeout);

        try {
            // Validate RestTemplate
            if (restTemplate == null) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", inputData);
                return NodeExecutionResult.failureWithOutput(nodeId, "RestTemplate not configured", failOutput, 0);
            }

            // Resolve URL expression
            String url = resolveExpression(urlExpression, context);
            if (url == null || url.isBlank()) {
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", inputData);
                return NodeExecutionResult.failureWithOutput(nodeId, "URL is required", failOutput, 0);
            }

            // Add query parameters
            url = appendQueryParams(url, context);

            // Add API key as query param if configured
            url = appendApiKeyQueryParam(url, context);

            // SSRF protection: validate URL before making any request
            UrlSafetyValidator.validateUrl(url);

            logger.info("HTTP Request: {} {}", method, url);

            // Prepare headers
            HttpHeaders httpHeaders = prepareHeaders(context);

            // Prepare body
            Object body = prepareBody(context);

            // Enrich inputData with fully resolved values
            inputData.put("url", url);
            if (body != null) inputData.put("body", body);
            if (!headers.isEmpty()) inputData.put("headers", headers.size() + " header(s)");
            if (!queryParams.isEmpty()) inputData.put("queryParams", queryParams.size() + " param(s)");

            // Create request
            HttpEntity<Object> request = new HttpEntity<>(body, httpHeaders);

            // Use per-node timeout if configured, otherwise use shared RestTemplate
            RestTemplate effectiveRestTemplate = resolveRestTemplate();

            // F2.3 - execute the call asynchronously and poll the run's cancel
            // key every 200ms so a STOP arriving mid-request releases the
            // orchestrator thread within ~200ms instead of waiting up to the
            // full HTTP timeout (default ~5min). The HTTP call itself may
            // continue in the background until the underlying connection times
            // out - Phase 4 will plumb a real abort via Apache HttpClient.
            ResponseEntity<String> response;
            final String finalUrl = url;
            final HttpEntity<Object> finalRequest = request;
            // HOTFIX-2 (2026-05-20) - defensively rebind orgId on the FJP worker
            // thread. HTTP itself doesn't persist OrgScopedEntity, but
            // RestTemplate interceptors / retry handlers added later could; the
            // wrap is cheap and prevents the bug class from regressing.
            final String orgIdForWorker = context.organizationId();
            CompletableFuture<ResponseEntity<String>> future = CompletableFuture.supplyAsync(() -> {
                ResponseEntity<String>[] holder = new ResponseEntity[1];
                com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () ->
                    holder[0] = effectiveRestTemplate.exchange(
                        finalUrl,
                        HttpMethod.valueOf(method),
                        finalRequest,
                        String.class
                    )
                );
                return holder[0];
            });

            try {
                response = waitWithCancelPolling(future, context);
            } catch (CancellationException ce) {
                logger.info("⏹️ [CANCEL] HTTP request cancelled by user: nodeId={} url={}", nodeId, url);
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", inputData);
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "HTTP Request cancelled (run cancel signal)", failOutput, 0);
            } catch (HttpStatusCodeException e) {
                NodeExecutionResult errorResult = buildErrorResult(e, context);
                if (errorResult.output() != null) errorResult.output().put("resolved_params", inputData);
                return errorResult;
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                if (cause instanceof HttpStatusCodeException hse) {
                    NodeExecutionResult errorResult = buildErrorResult(hse, context);
                    if (errorResult.output() != null) errorResult.output().put("resolved_params", inputData);
                    return errorResult;
                }
                throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
            }

            // Build success result
            NodeExecutionResult successResult = buildSuccessResult(response, context);
            if (successResult.output() != null) successResult.output().put("resolved_params", inputData);
            return successResult;

        } catch (Exception e) {
            logger.error("HTTP Request failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("resolved_params", inputData);
            return NodeExecutionResult.failureWithOutput(nodeId, "HTTP Request failed: " + e.getMessage(), failOutput, 0);
        }
    }

    /**
     * F2.3 - wait on the HTTP future while polling the run's cancel key every
     * 200ms. If the cancel signal flips, {@code future.cancel(true)} releases
     * the wait and a {@link CancellationException} is thrown to the caller.
     *
     * <p>If no publisher is wired (unit tests), falls back to a direct
     * {@code future.get()} so behavior is unchanged.
     */
    private ResponseEntity<String> waitWithCancelPolling(
            CompletableFuture<ResponseEntity<String>> future,
            ExecutionContext context) throws InterruptedException, ExecutionException {
        if (workflowRedisPublisher == null || context == null || context.runId() == null) {
            // No cancel source → just wait. Preserves test behavior and CE setups.
            return future.get();
        }
        while (true) {
            try {
                return future.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (workflowRedisPublisher.isAgentCancelSignalSet(context.runId())) {
                    future.cancel(true);
                    throw new CancellationException("run cancel signal set");
                }
                // else continue polling
            }
        }
    }

    private String appendQueryParams(String url, ExecutionContext context) {
        if (queryParams.isEmpty()) {
            return url;
        }

        List<String> parts = new ArrayList<>();
        for (HttpParam param : queryParams) {
            String key = resolveExpression(param.key(), context);
            String value = resolveExpression(param.value(), context);
            if (key != null && !key.isBlank()) {
                // Pre-fix encoded only the VALUE; a key like "foo bar" or
                // "weird&key" would be pasted raw and produce a malformed
                // query string ("?foo bar=v" → URI parser fails) or worse
                // smuggle a second pair ("?weird&key=v" → two distinct
                // params seen by upstream). Encode both halves now.
                String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
                String encodedValue = value != null
                    ? URLEncoder.encode(value, StandardCharsets.UTF_8)
                    : "";
                parts.add(encodedKey + "=" + encodedValue);
            }
        }

        if (!parts.isEmpty()) {
            String queryString = String.join("&", parts);
            url += (url.contains("?") ? "&" : "?") + queryString;
        }

        return url;
    }

    /**
     * Appends the API key as a query parameter when authType is "api-key"
     * and apiKeyLocation is "query".
     */
    private String appendApiKeyQueryParam(String url, ExecutionContext context) {
        if (authConfig == null || !"api-key".equals(authType)) {
            return url;
        }
        String location = authConfig.apiKeyLocation();
        if (!"query".equalsIgnoreCase(location)) {
            return url;
        }

        String apiKeyName = resolveExpression(authConfig.apiKeyName(), context);
        String apiKeyValue = resolveExpression(authConfig.apiKeyValue(), context);
        if (apiKeyName != null && !apiKeyName.isBlank() && apiKeyValue != null) {
            String encoded = URLEncoder.encode(apiKeyValue, StandardCharsets.UTF_8);
            url += (url.contains("?") ? "&" : "?") + apiKeyName + "=" + encoded;
        }

        return url;
    }

    /**
     * Returns a RestTemplate with per-node timeout if configured,
     * otherwise returns the shared RestTemplate.
     *
     * <p>Configured templates are cached per timeout value via
     * {@link #TIMEOUT_TEMPLATE_CACHE} so a node executed in a loop or split
     * doesn't allocate a fresh RestTemplate + factory per call. Same timeout
     * value across nodes shares the same template.
     */
    private RestTemplate resolveRestTemplate() {
        if (timeout == null || timeout <= 0) {
            return restTemplate;
        }
        // Clamp connect timeout to the same ceiling as before (300s).
        int clamped = Math.min(timeout, 300000);
        return TIMEOUT_TEMPLATE_CACHE.computeIfAbsent(clamped, t -> {
            RestTemplate template = new RestTemplate();
            NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
            factory.setConnectTimeout(t);
            factory.setReadTimeout(t);
            template.setRequestFactory(factory);
            return template;
        });
    }

    private HttpHeaders prepareHeaders(ExecutionContext context) {
        HttpHeaders httpHeaders = new HttpHeaders();

        // Add custom headers
        for (HttpParam header : headers) {
            String key = resolveExpression(header.key(), context);
            String value = resolveExpression(header.value(), context);
            if (key != null && !key.isBlank() && value != null) {
                httpHeaders.add(key, value);
            }
        }

        // Set Content-Type if not already set
        if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
            if (contentType != null && !contentType.isBlank()) {
                httpHeaders.setContentType(MediaType.parseMediaType(contentType));
            } else if ("json".equals(bodyType)) {
                httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            } else if ("form-data".equals(bodyType)) {
                httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            } else if ("x-www-form-urlencoded".equals(bodyType)) {
                httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            }
        }

        // Set Accept if not already set
        if (!httpHeaders.containsKey(HttpHeaders.ACCEPT)) {
            httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        }

        // Apply authentication
        applyAuthentication(httpHeaders, context);

        return httpHeaders;
    }

    private void applyAuthentication(HttpHeaders httpHeaders, ExecutionContext context) {
        if (authConfig == null || "none".equals(authType)) {
            return;
        }

        switch (authType) {
            case "basic" -> {
                String username = resolveExpression(authConfig.username(), context);
                String password = resolveExpression(authConfig.password(), context);
                if (username != null && password != null) {
                    String credentials = username + ":" + password;
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    httpHeaders.add(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
                }
            }
            case "bearer" -> {
                String token = resolveExpression(authConfig.bearerToken(), context);
                if (token != null && !token.isBlank()) {
                    httpHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                }
            }
            case "api-key" -> {
                // Query location is handled by appendApiKeyQueryParam(), only add header here
                String location = authConfig.apiKeyLocation();
                if (!"query".equalsIgnoreCase(location)) {
                    String apiKeyName = resolveExpression(authConfig.apiKeyName(), context);
                    String apiKeyValue = resolveExpression(authConfig.apiKeyValue(), context);
                    if (apiKeyName != null && apiKeyValue != null) {
                        httpHeaders.add(apiKeyName, apiKeyValue);
                    }
                }
            }
            case "custom-header" -> {
                String headerName = resolveExpression(authConfig.headerName(), context);
                String headerValue = resolveExpression(authConfig.headerValue(), context);
                if (headerName != null && headerValue != null) {
                    httpHeaders.add(headerName, headerValue);
                }
            }
        }
    }

    private Object prepareBody(ExecutionContext context) {
        if ("none".equals(bodyType) || bodyExpression == null || bodyExpression.isBlank()) {
            return null;
        }

        String resolvedBody = resolveExpression(bodyExpression, context);
        if (resolvedBody == null || resolvedBody.isBlank()) {
            return null;
        }

        // For JSON body, try to parse it
        if ("json".equals(bodyType)) {
            try {
                return objectMapper.readValue(resolvedBody, Object.class);
            } catch (Exception e) {
                logger.warn("Failed to parse JSON body, using raw string: {}", e.getMessage());
                return resolvedBody;
            }
        }

        // For x-www-form-urlencoded, parse into MultiValueMap so Spring sets the
        // correct Content-Type and encodes properly (RestTemplate requires this).
        if ("x-www-form-urlencoded".equals(bodyType)) {
            LinkedMultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            String[] pairs = resolvedBody.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq).trim();
                    String value = eq < pair.length() - 1 ? pair.substring(eq + 1).trim() : "";
                    formData.add(key, value);
                } else if (!pair.isBlank()) {
                    formData.add(pair.trim(), "");
                }
            }
            return formData;
        }

        // Note: form-data (multipart) requires file upload support which is out of scope.
        // The raw string is sent as-is; for simple key-value multipart, this works with
        // the MULTIPART_FORM_DATA content type set in prepareHeaders.
        return resolvedBody;
    }

    private NodeExecutionResult buildSuccessResult(ResponseEntity<String> response, ExecutionContext context) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("status", response.getStatusCode().value());
        result.put("statusText", response.getStatusCode().toString());
        // headers (legacy single-value map) - kept for backward compat with
        // workflows that already template `{{ http.output.headers.X }}`. Each
        // key collapses to its FIRST value (Spring's `toSingleValueMap`).
        result.put("headers", response.getHeaders().toSingleValueMap());
        // headersMulti - full multi-value form. Required for headers that
        // legitimately repeat: `Set-Cookie` (almost always multiple), `Link`
        // (paginated APIs), `Vary`, `WWW-Authenticate`, etc. Without this the
        // legacy map silently dropped all but the first value.
        // Type: Map<String, List<String>> (Spring's HttpHeaders is already
        // a MultiValueMap, copy it to a plain map to be JSON-serializable).
        Map<String, List<String>> headersMulti = new java.util.LinkedHashMap<>();
        for (var entry : response.getHeaders().entrySet()) {
            headersMulti.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        result.put("headersMulti", headersMulti);
        result.put("node_type", "HTTP_REQUEST");
        result.put("item_index", context.itemIndex());
        result.put("itemIndex", context.itemIndex());
        result.put("item_id", context.itemId());

        // Parse response body
        String body = response.getBody();
        if (body != null && !body.isBlank()) {
            try {
                // Try to parse as JSON
                Object parsed = objectMapper.readValue(body, Object.class);
                result.put("data", parsed);
            } catch (Exception e) {
                // Return as string
                result.put("data", body);
            }
        } else {
            result.put("data", Map.of());
        }

        logger.info("HTTP Request completed: nodeId={}, status={}", nodeId, response.getStatusCode().value());
        return NodeExecutionResult.success(nodeId, result);
    }

    private NodeExecutionResult buildErrorResult(HttpStatusCodeException e, ExecutionContext context) {
        int statusCode = e.getStatusCode().value();
        String errorBody = e.getResponseBodyAsString();

        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("status", statusCode);
        result.put("statusText", e.getStatusCode().toString());
        result.put("node_type", "HTTP_REQUEST");
        result.put("item_index", context.itemIndex());
        result.put("itemIndex", context.itemIndex());
        result.put("item_id", context.itemId());
        result.put("error", extractErrorMessage(errorBody, e.getMessage()));

        // Parse error body
        if (errorBody != null && !errorBody.isBlank()) {
            try {
                Object parsed = objectMapper.readValue(errorBody, Object.class);
                result.put("data", parsed);
            } catch (Exception ex) {
                result.put("data", errorBody);
            }
        } else {
            result.put("data", Map.of());
        }

        logger.warn("HTTP Request error: nodeId={}, status={}, error={}",
            nodeId, statusCode, result.get("error"));

        // Return success with error info in data (workflow continues)
        return NodeExecutionResult.success(nodeId, result);
    }

    private String extractErrorMessage(String responseBody, String defaultMessage) {
        if (responseBody == null || responseBody.isBlank()) {
            return defaultMessage;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Try common error formats
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                JsonNode messageNode = errorNode.path("message");
                if (!messageNode.isMissingNode()) {
                    return messageNode.asText();
                }
                if (errorNode.isTextual()) {
                    return errorNode.asText();
                }
            }

            JsonNode messageNode = root.path("message");
            if (!messageNode.isMissingNode()) {
                return messageNode.asText();
            }

            JsonNode detailNode = root.path("detail");
            if (!detailNode.isMissingNode()) {
                return detailNode.asText();
            }

            if (responseBody.length() < 500) {
                return responseBody;
            }
        } catch (Exception e) {
            logger.debug("Failed to parse error body: {}", e.getMessage());
        }

        return defaultMessage;
    }

    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object value = resolved.get("__expr__");
                return value != null ? value.toString() : null;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression: {} - {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    // Getters
    public String getMethod() {
        return method;
    }

    public String getUrlExpression() {
        return urlExpression;
    }

    public String getAuthType() {
        return authType;
    }

    public HttpAuthConfig getAuthConfig() {
        return authConfig;
    }

    public List<HttpParam> getQueryParams() {
        return queryParams;
    }

    public List<HttpParam> getHeaders() {
        return headers;
    }

    public String getBodyType() {
        return bodyType;
    }

    public String getBodyExpression() {
        return bodyExpression;
    }

    public String getContentType() {
        return contentType;
    }

    public Integer getTimeout() {
        return timeout;
    }

    // Builder
    public static class Builder {
        private String nodeId;
        private String method = "GET";
        private String urlExpression;
        private String authType = "none";
        private HttpAuthConfig authConfig;
        private List<HttpParam> queryParams = new ArrayList<>();
        private List<HttpParam> headers = new ArrayList<>();
        private String bodyType = "none";
        private String bodyExpression;
        private String contentType;
        private Integer timeout;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder urlExpression(String urlExpression) {
            this.urlExpression = urlExpression;
            return this;
        }

        public Builder authType(String authType) {
            this.authType = authType;
            return this;
        }

        public Builder authConfig(HttpAuthConfig authConfig) {
            this.authConfig = authConfig;
            return this;
        }

        public Builder queryParams(List<HttpParam> queryParams) {
            this.queryParams = queryParams != null ? queryParams : new ArrayList<>();
            return this;
        }

        public Builder headers(List<HttpParam> headers) {
            this.headers = headers != null ? headers : new ArrayList<>();
            return this;
        }

        public Builder bodyType(String bodyType) {
            this.bodyType = bodyType;
            return this;
        }

        public Builder bodyExpression(String bodyExpression) {
            this.bodyExpression = bodyExpression;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder timeout(Integer timeout) {
            this.timeout = timeout;
            return this;
        }

        public HttpRequestNode build() {
            return new HttpRequestNode(
                nodeId, method, urlExpression, authType, authConfig,
                queryParams, headers, bodyType, bodyExpression, contentType, timeout
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
