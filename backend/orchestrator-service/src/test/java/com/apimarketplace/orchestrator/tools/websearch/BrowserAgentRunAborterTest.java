package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.orchestrator.config.WebSearchConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BrowserAgentRunAborter} - the cross-service abort that makes
 * a workflow Stop (or the live-view red button) actually kill the running
 * browser-agent instead of letting the runner browse to completion.
 */
@DisplayName("BrowserAgentRunAborter")
class BrowserAgentRunAborterTest {

    @SuppressWarnings("unchecked")
    private static ListOperations<String, String> listOps(StringRedisTemplate redis) {
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);
        return ops;
    }

    @Test
    @DisplayName("abortSession: POSTs the runner abort AND LPUSHes a session-tagged ABORT on the control key")
    void abortSessionPostsAndLpushes() {
        RestTemplate rest = mock(RestTemplate.class);
        WebSearchConfig cfg = mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lops = listOps(redis);

        BrowserAgentRunAborter aborter = new BrowserAgentRunAborter(rest, cfg, redis);
        aborter.abortSession("run_1", "node_1", "ses_abc");

        // 1. Immediate REST abort at the (sid)-keyed endpoint.
        verify(rest).postForObject(
                eq("http://websearch:8085/agent/sessions/ses_abc/abort"), any(), eq(Map.class));
        // 2. Fallback control LPUSH - session-tagged ABORT on the runner's key.
        verify(lops).leftPush(
                eq("agent:run:run_1:node:node_1:control"),
                contains("\"cmd\":\"ABORT\""));
        verify(lops).leftPush(eq("agent:run:run_1:node:node_1:control"), contains("ses_abc"));
    }

    @Test
    @DisplayName("abortSession: no sessionId -> skips REST, still LPUSHes an untagged ABORT (back-compat)")
    void abortSessionNoSidLpushesUntagged() {
        RestTemplate rest = mock(RestTemplate.class);
        WebSearchConfig cfg = mock(WebSearchConfig.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lops = listOps(redis);

        BrowserAgentRunAborter aborter = new BrowserAgentRunAborter(rest, cfg, redis);
        aborter.abortSession("run_1", "node_1", null);

        verify(rest, never()).postForObject(anyString(), any(), eq(Map.class));
        verify(lops).leftPush(eq("agent:run:run_1:node:node_1:control"), eq("{\"cmd\":\"ABORT\"}"));
    }

    @Test
    @DisplayName("abortSession: never throws when the REST abort fails (a Stop must not be blocked)")
    void abortSessionSwallowsRestFailure() {
        RestTemplate rest = mock(RestTemplate.class);
        WebSearchConfig cfg = mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        when(rest.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("websearch down"));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lops = listOps(redis);

        BrowserAgentRunAborter aborter = new BrowserAgentRunAborter(rest, cfg, redis);
        aborter.abortSession("run_1", "node_1", "ses_abc"); // must not throw

        // The control LPUSH still fires despite the REST failure.
        verify(lops).leftPush(eq("agent:run:run_1:node:node_1:control"), contains("ABORT"));
    }

    @Test
    @DisplayName("abortAllForRun: SCANs the run's meta keys and aborts each in-flight browse")
    @SuppressWarnings("unchecked")
    void abortAllForRunScansAndAborts() {
        RestTemplate rest = mock(RestTemplate.class);
        WebSearchConfig cfg = mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        listOps(redis);

        // SCAN agent:browse:meta:run_1:* returns one browse node.
        org.springframework.data.redis.core.Cursor<String> cursor =
                mock(org.springframework.data.redis.core.Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("agent:browse:meta:run_1:node_9");
        when(redis.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);

        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("agent:browse:meta:run_1:node_9", "sessionId")).thenReturn("ses_live");

        BrowserAgentRunAborter aborter = new BrowserAgentRunAborter(rest, cfg, redis);
        int n = aborter.abortAllForRun("run_1");

        assertThat(n).isEqualTo(1);
        // Aborted the discovered session at the right endpoint.
        verify(rest).postForObject(
                eq("http://websearch:8085/agent/sessions/ses_live/abort"), any(), eq(Map.class));
    }

    @Test
    @DisplayName("abortAllForRun: blank runId / null redis is a safe no-op returning 0")
    void abortAllForRunGuards() {
        BrowserAgentRunAborter a1 = new BrowserAgentRunAborter(
                mock(RestTemplate.class), mock(WebSearchConfig.class), mock(StringRedisTemplate.class));
        assertThat(a1.abortAllForRun("")).isZero();
        assertThat(a1.abortAllForRun(null)).isZero();
    }

    @Test
    @DisplayName("abortSession: null runId/nodeId is a no-op")
    void abortSessionNullIdsNoop() {
        RestTemplate rest = mock(RestTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        BrowserAgentRunAborter aborter = new BrowserAgentRunAborter(rest, mock(WebSearchConfig.class), redis);
        aborter.abortSession(null, "node_1", "ses_abc");
        aborter.abortSession("run_1", null, "ses_abc");
        verify(rest, never()).postForObject(anyString(), any(), eq(Map.class));
    }
}
