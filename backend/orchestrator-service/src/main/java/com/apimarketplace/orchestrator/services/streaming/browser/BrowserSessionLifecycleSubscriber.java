package com.apimarketplace.orchestrator.services.streaming.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the {@code agent:browse:cdp_ready} Redis pub/sub channel
 * the websearch runner publishes to as soon as it captures the upstream
 * Chromium DevTools URL on the first browser step. Hands each bootstrap
 * notification to {@link BrowserSessionLifecycleService} which mints a
 * CDP JWT and fans an {@code agent_browse_step} event out to the chat
 * frontend's right-side panel.
 *
 * <p>Why pub/sub (not a Redis Stream consumer): we'd need to subscribe
 * dynamically to N per-session stream keys (one per concurrent
 * agent_browse). The project already standardises on pub/sub for event
 * fanout (workflow run streaming, chat tool events, fetch screenshots
 * via {@code WebSearchCallbackController}); a single channel here keeps
 * the JVM-side wiring trivial. The runner ALSO XADDs the same event
 * onto the per-session steps Stream (TTL 1h), so post-hoc consumers
 * (CLI tools, replay scripts) still have access to the archive.</p>
 *
 * <p>If the JVM is down when the runner publishes (a deployment hiccup,
 * or before the orchestrator container is ready), the bootstrap signal
 * is lost on this channel - but the cached envelope in
 * {@code live_view:session:{toolId}} written by the lifecycle service
 * (5-min TTL, matches JWT expiry) means a frontend that reconnects can
 * recover from the cache. Graceful degradation: if even the cache misses,
 * the post-completion path (existing {@code [visualize:agent_browse:{id}]}
 * marker on the tool result) still renders the card after the agent
 * finishes.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BrowserSessionLifecycleSubscriber implements MessageListener {

    public static final String CDP_READY_CHANNEL = "agent:browse:cdp_ready";

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final BrowserSessionLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void registerSubscription() {
        try {
            redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(CDP_READY_CHANNEL));
            log.info("[BrowserLifecycleSubscriber] subscribed to {}", CDP_READY_CHANNEL);
        } catch (Exception e) {
            log.warn("[BrowserLifecycleSubscriber] failed to register subscription "
                    + "(live-view bootstrap will fall back to post-completion path): {}",
                    e.getMessage());
        }
    }

    @Override
    public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
        String body;
        try {
            body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[BrowserLifecycleSubscriber] message decode failed: {}", e.getMessage());
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String runId = textOrNull(node, "run_id");
            String nodeId = textOrNull(node, "node_id");
            String sessionId = textOrNull(node, "session_id");
            String cdpWsUrl = textOrNull(node, "cdp_ws_url");
            String currentUrl = textOrNull(node, "current_url");
            int stepIndex = node.has("step_index") ? node.get("step_index").asInt(0) : 0;
            lifecycleService.onCdpReady(runId, nodeId, sessionId, cdpWsUrl, currentUrl, stepIndex);
        } catch (Exception e) {
            // Don't let a malformed message crash the listener - log and
            // move on. The post-completion path still works.
            log.warn("[BrowserLifecycleSubscriber] failed to handle cdp_ready msg: {}",
                    e.getMessage());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }
}
