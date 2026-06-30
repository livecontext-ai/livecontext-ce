package com.apimarketplace.monolith.config;

import com.apimarketplace.conversation.domain.stream.StreamEvent;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monolith-mode adapter for {@link StreamPubSubService}.
 *
 * <p>The real service belongs to the cloud/WebFlux streaming pipeline. CE monolith
 * wires only the shared Redis state and event adapters it needs explicitly. Most
 * stream methods stay no-op because servlet chat uses {@code MonolithStreamingOutput}.
 * Title updates are the exception: cloud conversation tooling calls
 * {@link #publishTitleUpdated(String, String, String)} directly, so CE bridges that
 * event to the same non-reactive Redis WebSocket channel used by servlet streaming.
 */
public class NoOpStreamPubSubService extends StreamPubSubService {

    private static final Logger log = LoggerFactory.getLogger(NoOpStreamPubSubService.class);
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public NoOpStreamPubSubService(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        super(null, null, objectMapper, null);
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Long> publish(String streamId, StreamEvent event) {
        if (event instanceof StreamEvent.TitleUpdated titleUpdated) {
            return publishTitleUpdated(streamId, titleUpdated.conversationId(), titleUpdated.title());
        }
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishAndStoreToolEvent(String streamId, StreamEvent event) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishContent(String streamId, String content) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishReplayContent(String streamId, String content) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishThinking(String streamId, String thinking) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishThinkingSection(String streamId, String title, String content) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishComplete(String streamId, String fullContent, int totalTokens) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishError(String streamId, String error, String errorCode, boolean retryable) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishStopped(String streamId, String partialContent) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishHeartbeat(String streamId) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Long> publishTitleUpdated(String streamId, String conversationId, String title) {
        if (conversationId == null || conversationId.isBlank()) {
            return Mono.just(0L);
        }

        return Mono.fromSupplier(() -> {
            try {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("streamId", streamId);
                event.put("conversationId", conversationId);
                event.put("title", title);
                event.put("timestamp", Instant.now().toString());
                event.put("type", "title_updated");
                Long receivers = redisTemplate.convertAndSend(
                        WS_CHANNEL_PREFIX + conversationId,
                        objectMapper.writeValueAsString(event));
                return receivers != null ? receivers : 0L;
            } catch (Exception ex) {
                log.warn("[CE StreamPubSub] Failed to publish title_updated for stream={} conversation={}",
                        streamId, conversationId, ex);
                return 0L;
            }
        });
    }

    @Override
    public Mono<Long> publishCompactionDone(String streamId, String conversationId,
                                            int turnsCoveredCount, String summarizerModel,
                                            java.time.Instant generatedAt) {
        return Mono.just(0L);
    }

    @Override
    public Flux<StreamEvent> subscribe(String streamId) {
        return Flux.empty();
    }

    @Override
    public void evictStreamCache(String streamId) {
        // no-op
    }

    @Override
    public StreamEvent deserializeEvent(String json) {
        throw new UnsupportedOperationException(
                "deserializeEvent is unreachable in monolith profile (no Pub/Sub subscription active)");
    }
}
