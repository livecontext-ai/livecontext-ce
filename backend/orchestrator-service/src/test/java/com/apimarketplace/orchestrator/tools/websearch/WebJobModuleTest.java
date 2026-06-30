package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebJobModule (abstract base)")
class WebJobModuleTest {

    @Mock private RestTemplate restTemplate;
    @Mock private WebSearchConfig config;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SERVICE_URL = "http://websearch-host:8085";

    private TestModule module;

    @BeforeEach
    void setUp() {
        lenient().when(config.getServiceUrl()).thenReturn(SERVICE_URL);
        module = new TestModule(restTemplate, config, redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("happy path: submits job, BLPOPs result, returns success")
    void happyPath() throws Exception {
        when(config.getBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("fetch:result:job-1"), eq(Duration.ofSeconds(150))))
            .thenReturn("{\"markdown\":\"hello\",\"screenshots\":[\"BASE64...\"],\"screenshot_key\":\"k1\"}");
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-1"));

        ToolExecutionResult res = module.run("test-action", Map.of("k", "v"), null);

        assertThat(res.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data).containsEntry("markdown", "hello");
        assertThat(data).containsEntry("screenshot_key", "k1");
        // postProcess hook removed the base64 array
        assertThat(data).doesNotContainKey("screenshots");
    }

    @Test
    @DisplayName("submit returns null body: failure with explicit message")
    void submitReturnsNullBody() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(null);

        ToolExecutionResult res = module.run("test-action", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(res.error()).contains("No job_id");
    }

    @Test
    @DisplayName("submit returns no job_id: failure with explicit message")
    void submitReturnsNoJobId() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("status", "accepted"));

        ToolExecutionResult res = module.run("test-action", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(res.error()).contains("No job_id");
    }

    @Test
    @DisplayName("BLPOP timeout: returns RATE_LIMITED-friendly failure with action name")
    void blpopTimeoutSurfacesActionName() {
        when(config.getBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(null);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-2"));

        ToolExecutionResult res = module.run("fetch", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(res.error())
            .contains("Web fetch timed out after 150s")
            .contains("Do NOT retry this fetch");
    }

    @Test
    @DisplayName("response with error field: surfaces the error verbatim")
    void responseHasErrorField() throws Exception {
        when(config.getBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"error\":\"upstream 503\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-3"));

        ToolExecutionResult res = module.run("fetch", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Web fetch failed: upstream 503")
            .contains("Do NOT retry this fetch");
    }

    @Test
    @DisplayName("RestTemplate throws: caught, mapped to failedError(action, ...)")
    void restTemplateThrows() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenThrow(new RuntimeException("connection refused"));

        ToolExecutionResult res = module.run("fetch", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Web fetch failed: connection refused");
    }

    @Test
    @DisplayName("concurrency gate: blocks the (limit+1)-th caller with concurrencyError()")
    void concurrencyGateBlocks() {
        // limit=1 module; first call holds the permit by hanging on Redis
        TestModule small = new TestModule(restTemplate, config, redisTemplate, objectMapper, 1);
        // Acquire the only permit synthetically (simulating an in-flight call)
        small.acquireOne();

        ToolExecutionResult res = small.run("fetch", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.RATE_LIMITED);
        assertThat(res.error()).contains("Too many concurrent web searches");
    }

    @Test
    @DisplayName("onSubmitTimeout returning non-null overrides the default timeoutError")
    void onSubmitTimeoutOverridesDefault() {
        // Subclass returns a custom failure on timeout - the base pipeline
        // must use that instead of the standard "Web {action} timed out" wording.
        when(config.getBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(null);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-7"));

        TestModuleWithTimeoutOverride mod = new TestModuleWithTimeoutOverride(
            restTemplate, config, redisTemplate, objectMapper);

        ToolExecutionResult res = mod.run("agent_browse", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("custom timeout cleanup ran")
            .contains("jobId=job-7")
            .doesNotContain("Web agent_browse timed out after");
        assertThat(mod.timeoutHookInvoked).isTrue();
        assertThat(mod.observedJobId).isEqualTo("job-7");
    }

    @Test
    @DisplayName("onSubmitTimeout returning null falls through to standard timeoutError")
    void onSubmitTimeoutNullFallsThrough() {
        // Default hook (returns null) must preserve the existing behaviour:
        // standard "Web {action} timed out after Ns. Do NOT retry" message.
        when(config.getBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(null);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-8"));

        ToolExecutionResult res = module.run("fetch", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Web fetch timed out after 150s")
            .contains("Do NOT retry this fetch");
    }

    @Test
    @DisplayName("onSubmitTimeout throwing: caught in submitAndAwait, falls back to standard timeoutError (slot/cleanup contract preserved)")
    void onSubmitTimeoutHookExceptionMaskedToTimeoutError() {
        // Buggy subclass throws inside the hook. The base must NOT let the
        // exception escape into the outer catch (which would mis-report as
        // failedError 'Web fetch failed: ...'). It must fall through to the
        // standard timeoutError so the agent sees a coherent timeout message.
        when(config.getBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(null);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-throw"));

        TestModuleWithThrowingHook mod = new TestModuleWithThrowingHook(
            restTemplate, config, redisTemplate, objectMapper);

        ToolExecutionResult res = mod.run("agent_browse", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .as("hook exception must be swallowed, surfacing the standard timeoutError")
            .contains("Web agent_browse timed out after 150s")
            .doesNotContain("Web agent_browse failed");
    }

    @Test
    @DisplayName("onSubmitTimeout receives tenantId arg (not just context) - needed for legacy paths where context.tenantId() is null")
    void onSubmitTimeoutReceivesTenantIdArg() {
        // Verifies the hook signature change: tenantId is now an explicit arg
        // alongside context. Without this, the BrowserAgentModule cleanup
        // path would skip LREM whenever context.tenantId() is null even
        // though the original execute() call had a tenantId.
        when(config.getBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(null);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-tid"));

        TestModuleWithTenantCapture mod = new TestModuleWithTenantCapture(
            restTemplate, config, redisTemplate, objectMapper);

        mod.run("agent_browse", Map.of(), "tenant-from-arg", null);

        assertThat(mod.observedTenantId).isEqualTo("tenant-from-arg");
        assertThat(mod.observedJobId).isEqualTo("job-tid");
    }

    @Test
    @DisplayName("getBlpopTimeoutSeconds default is config.getBlpopTimeout(); overridable")
    void getBlpopTimeoutSecondsOverridable() {
        // Default subclass uses the config value (150); override path uses 110.
        // Verify both BLPOP duration and timeoutError wording reflect the override.
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), eq(Duration.ofSeconds(110)))).thenReturn(null);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-9"));

        TestModuleWithShorterTimeout mod = new TestModuleWithShorterTimeout(
            restTemplate, config, redisTemplate, objectMapper);

        ToolExecutionResult res = mod.run("fetch", Map.of(), null);

        assertThat(res.success()).isFalse();
        assertThat(res.error()).contains("Web fetch timed out after 110s");
    }

    @Test
    @DisplayName("buildCallbackUrl: returns null when streamId or toolCallId missing")
    void buildCallbackUrlNullWhenIdentifiersMissing() {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.credentials()).thenReturn(Map.of("conversationId", "c1"));

        String url = module.callBuildCallbackUrl(ctx, "/path");

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("buildCallbackUrl: encodes streamId/toolId/conversationId into query string")
    void buildCallbackUrlEncodes() {
        when(config.getCallbackBaseUrl()).thenReturn("http://app-host:8099");
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("__streamId__", "s 1");
        creds.put("__toolCallId__", "t/1");
        creds.put("conversationId", "c=1");
        when(ctx.credentials()).thenReturn(creds);

        String url = module.callBuildCallbackUrl(ctx, "/api/internal/callback");

        assertThat(url).isEqualTo(
            "http://app-host:8099/api/internal/callback?streamId=s+1&toolId=t%2F1&conversationId=c%3D1");
    }

    @Test
    @DisplayName("cleanScreenshots: drops single-page array AND each pages[].screenshots")
    void cleanScreenshotsCoversBatch() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("screenshots", List.of("base64a"));
        response.put("pages", List.of(
            new LinkedHashMap<>(Map.of("url", "u1", "screenshots", List.of("b1"), "screenshot_key", "k1")),
            new LinkedHashMap<>(Map.of("url", "u2", "screenshots", List.of("b2"), "screenshot_key", "k2"))
        ));

        module.callCleanScreenshots(response);

        assertThat(response).doesNotContainKey("screenshots");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) response.get("pages");
        assertThat(pages).allSatisfy(p -> {
            assertThat(p).doesNotContainKey("screenshots");
            assertThat(p).containsKey("screenshot_key");
        });
    }

    // ── Test fixture: minimal concrete subclass exposing the protected hooks ───

    private static class TestModule extends WebJobModule {
        TestModule(RestTemplate rt, WebSearchConfig cfg, StringRedisTemplate redis, ObjectMapper om) {
            super(rt, cfg, redis, om, 30);
        }
        TestModule(RestTemplate rt, WebSearchConfig cfg, StringRedisTemplate redis, ObjectMapper om, int limit) {
            super(rt, cfg, redis, om, limit);
        }

        @Override public List<AgentToolDefinition> getToolDefinitions() { return List.of(); }
        @Override public boolean canHandle(String action) { return true; }
        @Override public Optional<ToolExecutionResult> execute(String action, Map<String, Object> p, String t, ToolExecutionContext c) {
            return Optional.of(submitAndAwait(action, p, t, c));
        }
        @Override
        protected Map<String, Object> buildJobParameters(Map<String, Object> p, ToolExecutionContext c) {
            return new LinkedHashMap<>(p);
        }
        @Override
        protected Map<String, Object> postProcess(Map<String, Object> r, Map<String, Object> p, ToolExecutionContext c) {
            cleanScreenshots(r);
            return r;
        }

        ToolExecutionResult run(String action, Map<String, Object> p, ToolExecutionContext c) {
            return submitAndAwait(action, p, null, c);
        }
        ToolExecutionResult run(String action, Map<String, Object> p, String t, ToolExecutionContext c) {
            return submitAndAwait(action, p, t, c);
        }
        String callBuildCallbackUrl(ToolExecutionContext ctx, String path) {
            return buildCallbackUrl(ctx, path);
        }
        void callCleanScreenshots(Map<String, Object> r) { cleanScreenshots(r); }

        // Drains the semaphore so the concurrency-gate test can prove the rejection branch.
        void acquireOne() {
            try {
                java.lang.reflect.Field f = WebJobModule.class.getDeclaredField("concurrencyGate");
                f.setAccessible(true);
                java.util.concurrent.Semaphore s = (java.util.concurrent.Semaphore) f.get(this);
                s.acquire();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Subclass that overrides {@code onSubmitTimeout} to return a custom failure. */
    private static class TestModuleWithTimeoutOverride extends TestModule {
        boolean timeoutHookInvoked = false;
        String observedJobId = null;

        TestModuleWithTimeoutOverride(RestTemplate rt, WebSearchConfig cfg,
                                       StringRedisTemplate redis, ObjectMapper om) {
            super(rt, cfg, redis, om);
        }

        @Override
        protected ToolExecutionResult onSubmitTimeout(String action,
                                                       Map<String, Object> parameters,
                                                       String tenantId,
                                                       ToolExecutionContext context,
                                                       String jobId) {
            timeoutHookInvoked = true;
            observedJobId = jobId;
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "custom timeout cleanup ran for jobId=" + jobId);
        }
    }

    /** Subclass that caps BLPOP timeout below the config default (110 < 150). */
    private static class TestModuleWithShorterTimeout extends TestModule {
        TestModuleWithShorterTimeout(RestTemplate rt, WebSearchConfig cfg,
                                      StringRedisTemplate redis, ObjectMapper om) {
            super(rt, cfg, redis, om);
        }

        @Override
        protected int getBlpopTimeoutSeconds() {
            return 110;
        }
    }

    /** Subclass whose hook throws - exercises the safety net in submitAndAwait. */
    private static class TestModuleWithThrowingHook extends TestModule {
        TestModuleWithThrowingHook(RestTemplate rt, WebSearchConfig cfg,
                                    StringRedisTemplate redis, ObjectMapper om) {
            super(rt, cfg, redis, om);
        }

        @Override
        protected ToolExecutionResult onSubmitTimeout(String action,
                                                       Map<String, Object> parameters,
                                                       String tenantId,
                                                       ToolExecutionContext context,
                                                       String jobId) {
            throw new RuntimeException("buggy subclass - hook should not propagate");
        }
    }

    /** Subclass that captures the hook's tenantId + jobId args for assertion. */
    private static class TestModuleWithTenantCapture extends TestModule {
        String observedTenantId = null;
        String observedJobId = null;

        TestModuleWithTenantCapture(RestTemplate rt, WebSearchConfig cfg,
                                     StringRedisTemplate redis, ObjectMapper om) {
            super(rt, cfg, redis, om);
        }

        @Override
        protected ToolExecutionResult onSubmitTimeout(String action,
                                                       Map<String, Object> parameters,
                                                       String tenantId,
                                                       ToolExecutionContext context,
                                                       String jobId) {
            this.observedTenantId = tenantId;
            this.observedJobId = jobId;
            return null;  // fall through to standard timeoutError
        }
    }
}
