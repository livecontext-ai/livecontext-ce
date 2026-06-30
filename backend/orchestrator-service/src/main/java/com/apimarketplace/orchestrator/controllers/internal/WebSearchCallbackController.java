package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal callback endpoint for websearch-service to report fetch screenshots.
 * Publishes screenshot keys to Redis Pub/Sub for real-time streaming
 * and caches them in Redis for later retrieval by the interface entity.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/websearch/callback")
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class WebSearchCallbackController {

    private static final String STREAM_CHANNEL_PREFIX = "stream:events:";
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";
    /** Redis key prefix for temporarily caching screenshot keys by URL. */
    public static final String SCREENSHOT_CACHE_PREFIX = "websearch:screenshot:";
    /** Redis key prefix mapping toolId → interfaceId for screenshot injection. */
    public static final String TOOL_IFACE_PREFIX = "websearch:tool2iface:";
    /** How long cached screenshot keys live in Redis (10 minutes). */
    private static final Duration SCREENSHOT_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final com.apimarketplace.common.event.EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final InterfaceClient interfaceClient;

    /**
     * Receive a fetch screenshot key from websearch-service and publish to Redis.
     * streamId and toolId are passed as query parameters in the callback URL.
     * Body from websearch-service: { url, screenshot_key, screenshot_index, timestamp_ms, is_final }
     */
    @PostMapping("/screenshot")
    public ResponseEntity<Void> receiveScreenshot(
            @RequestParam String streamId,
            @RequestParam String toolId,
            @RequestParam(required = false) String conversationId,
            @RequestBody Map<String, Object> payload) {

        if (streamId == null || toolId == null) {
            log.warn("Missing streamId or toolId in screenshot callback");
            return ResponseEntity.badRequest().build();
        }

        String url = (String) payload.get("url");
        String screenshotKey = (String) payload.get("screenshot_key");
        log.info("Received fetch screenshot for stream={}, tool={}, url={}, key={}", streamId, toolId, url, screenshotKey);

        // Validate screenshot key for security
        if (screenshotKey == null || !screenshotKey.startsWith("screenshots/") || screenshotKey.contains("..")) {
            log.warn("Invalid screenshot_key in callback: {}", screenshotKey);
            return ResponseEntity.badRequest().build();
        }

        // Cache the screenshot key in Redis keyed by URL so persistAndEnrichResult can retrieve it
        if (url != null) {
            String cacheKey = SCREENSHOT_CACHE_PREFIX + url;
            redisTemplate.opsForValue().set(cacheKey, screenshotKey, SCREENSHOT_TTL);
        }

        // Lookup tenantId|interfaceId from Redis and update the interface entity
        String ifaceKey = TOOL_IFACE_PREFIX + toolId;
        String mappingValue = redisTemplate.opsForValue().get(ifaceKey);
        if (mappingValue != null) {
            // New format: "tenantId|interfaceId". Old format (interfaceId only) is rejected
            // because the downstream call requires X-User-ID; old entries TTL out within 10 min.
            String tenantId = null;
            String interfaceId = mappingValue;
            // lastIndexOf is safer: interfaceId is a UUID (no '|'), tenantId could theoretically contain one.
            int sep = mappingValue.lastIndexOf('|');
            if (sep > 0) {
                tenantId = mappingValue.substring(0, sep);
                interfaceId = mappingValue.substring(sep + 1);
            }
            if (tenantId == null || tenantId.isBlank()) {
                log.warn("tool2iface mapping for toolId={} has no tenantId (old format?), skipping interface update", toolId);
            } else {
                try {
                    boolean updated = interfaceClient.updateWebSearchScreenshot(
                            UUID.fromString(interfaceId), url, screenshotKey, tenantId);
                    if (updated) {
                        log.info("Updated interface {} with screenshot key for url={}", interfaceId, url);
                    } else {
                        log.warn("InterfaceClient returned false updating interface {} with screenshot for url={}", interfaceId, url);
                    }
                } catch (Exception e) {
                    log.warn("Failed to update interface {} with screenshot: {}", interfaceId, e.getMessage());
                }
            }
        } else {
            log.info("No interfaceId found for toolId={}, screenshot cached in Redis only", toolId);
        }

        // Build the FetchScreenshot event matching conversation-service format
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("toolId", toolId);
        event.put("url", url);
        event.put("screenshotIndex", payload.getOrDefault("screenshot_index", 0));
        event.put("screenshotKey", screenshotKey);
        event.put("isFinal", payload.getOrDefault("is_final", false));
        event.put("timestamp", Instant.now().toString());

        try {
            String json = objectMapper.writeValueAsString(event);
            // Publish to stream channel (consumed by conversation-service subscribers)
            String channel = STREAM_CHANNEL_PREFIX + streamId;
            eventBus.publish(channel, json);
            // Also publish to WebSocket channel (consumed by gateway WS bridge → frontend)
            if (conversationId != null && !conversationId.isBlank()) {
                String wsChannel = WS_CHANNEL_PREFIX + conversationId;
                eventBus.publish(wsChannel, json);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize screenshot event: {}", e.getMessage());
        }

        return ResponseEntity.ok().build();
    }
}
