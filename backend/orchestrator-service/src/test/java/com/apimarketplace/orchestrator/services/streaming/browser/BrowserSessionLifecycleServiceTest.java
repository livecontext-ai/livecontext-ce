package com.apimarketplace.orchestrator.services.streaming.browser;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.tools.websearch.CdpTokenIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BrowserSessionLifecycleService} - the cdp_ready
 * → agent_browse_step bridge that delivers live-view coords to the chat
 * frontend before the blocking agent_browse tool call returns.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserSessionLifecycleService")
class BrowserSessionLifecycleServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private EventBus eventBus;
    @Mock
    private CdpTokenIssuer cdpTokenIssuer;
    @Mock
    private WebSearchConfig webSearchConfig;
    @Mock
    private WorkflowRedisPublisher workflowRedisPublisher;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BrowserSessionLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new BrowserSessionLifecycleService(
                redisTemplate, eventBus, objectMapper, cdpTokenIssuer,
                webSearchConfig, workflowRedisPublisher);
    }

    @Test
    @DisplayName("Mints token + dual-publishes event when chat context exists")
    void publishesAgentBrowseStepWhenContextExists() throws Exception {
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(hashOps.entries("agent:browse:meta:run-1:node-1"))
                .thenReturn(Map.of("conversationId", "conv-42", "userId", "user-7"));
        when(valueOps.get("live_view:session:node-1")).thenReturn(null);

        when(cdpTokenIssuer.isConfigured()).thenReturn(true);
        when(cdpTokenIssuer.issue("ses_xyz", "user-7", "run-1", "node-1"))
                .thenReturn("eyJfresh.token");
        when(webSearchConfig.getPublicWsBase()).thenReturn("https://websearch-host.test");

        boolean published = service.onCdpReady(
                "run-1", "node-1", "ses_xyz",
                "ws://internal-chromium:9222/devtools/...",
                "https://example.com", 0);

        assertThat(published).isTrue();

        // Two pub/sub fan-outs: stream:events:{streamId} + ws:conversation:{cid}
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventBus, times(2)).publish(channelCaptor.capture(), jsonCaptor.capture());

        assertThat(channelCaptor.getAllValues())
                .containsExactlyInAnyOrder("stream:events:run-1", "ws:conversation:conv-42");

        // Both fan-outs carry the same JSON envelope.
        String json = jsonCaptor.getAllValues().get(0);
        JsonNode env = objectMapper.readTree(json);
        assertThat(env.get("streamId").asText()).isEqualTo("run-1");
        assertThat(env.get("toolId").asText()).isEqualTo("node-1");
        assertThat(env.get("sessionId").asText()).isEqualTo("ses_xyz");
        assertThat(env.get("cdpToken").asText()).isEqualTo("eyJfresh.token");
        assertThat(env.get("cdpWsUrl").asText())
                .isEqualTo("wss://websearch-host.test/cdp/ses_xyz");
        assertThat(env.get("currentUrl").asText()).isEqualTo("https://example.com");

        // Cache write for reconnect-replay (5 min TTL = JWT TTL).
        verify(valueOps).set(eq("live_view:session:node-1"), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("Skips silently when run context hash carries neither conversationId nor runType=workflow")
    void skipsWhenNoRunContext() {
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(hashOps.entries("agent:browse:meta:run-1:node-1"))
                .thenReturn(Map.of("userId", "user-7")); // no conversationId, no runType
        when(cdpTokenIssuer.isConfigured()).thenReturn(true);

        boolean published = service.onCdpReady(
                "run-1", "node-1", "ses_xyz", "ws://x", "https://example.com", 0);

        assertThat(published).isFalse();
        verify(eventBus, never()).publish(anyString(), anyString());
        verify(workflowRedisPublisher, never()).publishEvent(anyString(), anyString(), any());
        verify(cdpTokenIssuer, never()).issue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Workflow path: publishes agentBrowseStep on ws:workflow:run channel when runType=workflow")
    @SuppressWarnings("unchecked")
    void publishesOnWorkflowChannelWhenWorkflowFlagSet() {
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(hashOps.entries("agent:browse:meta:run-1:node-1"))
                .thenReturn(Map.of("runType", "workflow", "userId", "user-7"));
        when(valueOps.get("live_view:session:node-1")).thenReturn(null);
        when(cdpTokenIssuer.isConfigured()).thenReturn(true);
        when(cdpTokenIssuer.issue("ses_xyz", "user-7", "run-1", "node-1"))
                .thenReturn("eyJfresh.token");
        when(webSearchConfig.getPublicWsBase()).thenReturn("https://websearch-host.test");

        boolean published = service.onCdpReady(
                "run-1", "node-1", "ses_xyz", "ws://x", "https://example.com", 0);

        assertThat(published).isTrue();
        // Workflow path uses WorkflowRedisPublisher, NOT the chat dual-publish.
        verify(eventBus, never()).publish(anyString(), anyString());
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowRedisPublisher).publishEvent(eq("run-1"), eq("agentBrowseStep"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        // Snake_case fields - matches what statusUpdater.updateNodeFromStep parses.
        assertThat(payload.get("nodeId")).isEqualTo("node-1");
        assertThat(payload.get("session_id")).isEqualTo("ses_xyz");
        assertThat(payload.get("cdp_token")).isEqualTo("eyJfresh.token");
        assertThat((String) payload.get("cdp_ws_url"))
                .isEqualTo("wss://websearch-host.test/cdp/ses_xyz");
        assertThat(payload.get("step_index")).isEqualTo(0);
    }

    @Test
    @DisplayName("Skips silently when issuer unconfigured (CE deployment without secret)")
    void skipsWhenIssuerUnconfigured() {
        when(cdpTokenIssuer.isConfigured()).thenReturn(false);

        boolean published = service.onCdpReady(
                "run-1", "node-1", "ses_xyz", "ws://x", "", 0);

        assertThat(published).isFalse();
        verify(eventBus, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("Skips when required ids missing - defensive against malformed events")
    void skipsWhenIdsMissing() {
        // No isConfigured stub - early-exit on null ids should fire BEFORE
        // we even consult the issuer.
        boolean published = service.onCdpReady(
                null, "node-1", "ses_xyz", "ws://x", "", 0);

        assertThat(published).isFalse();
        verify(eventBus, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("Idempotent: skips when bootstrap already cached for same (toolId, sessionId)")
    void idempotentOnReplay() {
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(hashOps.entries("agent:browse:meta:run-1:node-1"))
                .thenReturn(Map.of("conversationId", "conv-42", "userId", "user-7"));
        when(valueOps.get("live_view:session:node-1"))
                .thenReturn("{\"sessionId\":\"ses_xyz\",\"cdpToken\":\"old\"}");
        when(cdpTokenIssuer.isConfigured()).thenReturn(true);

        boolean published = service.onCdpReady(
                "run-1", "node-1", "ses_xyz", "ws://x", "", 0);

        assertThat(published).isFalse();
        // Issuer NOT called - we shortcut on the cache hit.
        verify(cdpTokenIssuer, never()).issue(anyString(), anyString(), anyString(), anyString());
        verify(eventBus, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("Skips when CdpTokenIssuer returns null (e.g. signing failure)")
    void skipsWhenTokenMintFails() {
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(hashOps.entries("agent:browse:meta:run-1:node-1"))
                .thenReturn(Map.of("conversationId", "conv-42", "userId", "user-7"));
        when(valueOps.get("live_view:session:node-1")).thenReturn(null);
        when(cdpTokenIssuer.isConfigured()).thenReturn(true);
        when(cdpTokenIssuer.issue(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        boolean published = service.onCdpReady(
                "run-1", "node-1", "ses_xyz", "ws://x", "", 0);

        assertThat(published).isFalse();
        verify(eventBus, never()).publish(anyString(), anyString());
    }
}
