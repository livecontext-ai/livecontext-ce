package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.conversation.domain.stream.StreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.mock;

/**
 * Test configuration for conversation-service integration tests.
 *
 * Provides in-memory replacements for Redis-based services (StreamStateService,
 * StreamPubSubService) and mocks for external dependencies (ReactiveRedisConnectionFactory,
 * ReactiveRedisTemplate, ReactiveRedisMessageListenerContainer).
 *
 * These beans are separate from the E2E @Profile("e2e") beans to keep test isolation clean.
 */
@TestConfiguration
public class IntegrationTestConfig {

    // ========================== Redis Mocks ==========================

    @Bean
    @Primary
    public ReactiveRedisConnectionFactory mockRedisConnectionFactory() {
        return mock(ReactiveRedisConnectionFactory.class);
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate() {
        return mock(ReactiveRedisTemplate.class);
    }

    @Bean
    @Primary
    public ReactiveRedisMessageListenerContainer mockListenerContainer() {
        return mock(ReactiveRedisMessageListenerContainer.class);
    }

    @Bean
    @Primary
    public RedisConnectionFactory mockRedisBlockingConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }

    @Bean
    @Primary
    public StringRedisTemplate mockStringRedisTemplate() {
        return mock(StringRedisTemplate.class);
    }

    @Bean
    @Primary
    public RedisMessageListenerContainer mockRedisMessageListenerContainer() {
        return mock(RedisMessageListenerContainer.class);
    }

    @Bean("redisTemplate")
    @Primary
    @SuppressWarnings("unchecked")
    public RedisTemplate<Object, Object> mockRedisTemplate() {
        return mock(RedisTemplate.class);
    }

    // ========================== shared-agent-lib stubs ==========================

    /**
     * conversation-service includes shared-agent-lib only at test scope to pull in
     * MainCallerRegistryBean (a startup-log assertion bean). The package scan
     * "com.apimarketplace.agent.loop" also picks up AgentLoopService - whose constructor
     * requires LLMProviderFactory + the rest of the agent runtime.
     *
     * conversation-service no longer executes LLMs locally (see commit
     * "refactor(Phase 6): remove local LLM execution from conversation-service"), so we
     * stub the missing collaborator with a Mockito mock just to satisfy Spring's wiring.
     * Any actual call would throw - but no integration test in this module exercises
     * AgentLoopService.
     */
    @Bean
    @Primary
    public com.apimarketplace.agent.factory.LLMProviderFactory mockLLMProviderFactory() {
        return mock(com.apimarketplace.agent.factory.LLMProviderFactory.class);
    }

    // ========================== StreamStateService (in-memory) ==========================

    @Bean
    @Primary
    public StreamStateService integrationTestStreamStateService() {
        return new InMemoryIntegrationStreamStateService();
    }

    // ========================== StreamPubSubService (in-memory) ==========================

    @Bean
    @Primary
    public StreamPubSubService integrationTestStreamPubSubService(
            ObjectMapper objectMapper,
            StreamStateService stateService) {
        return new InMemoryIntegrationStreamPubSubService(objectMapper, stateService);
    }

    // ========================== In-Memory StreamStateService ==========================

    /**
     * In-memory implementation of StreamStateService for integration tests.
     */
    static class InMemoryIntegrationStreamStateService implements StreamStateService {

        private final Map<String, StreamMetadata> metadataStore = new ConcurrentHashMap<>();
        private final Map<String, List<String>> contentStore = new ConcurrentHashMap<>();
        private final Map<String, List<String>> toolEventsStore = new ConcurrentHashMap<>();
        private final Map<String, String> conversationIndex = new ConcurrentHashMap<>();
        private final Map<String, List<String>> userIndex = new ConcurrentHashMap<>();

        @Override
        public Mono<StreamMetadata> createStream(String userId, String conversationId, String model, String provider) {
            String streamId = "stream-" + UUID.randomUUID().toString().substring(0, 8);
            StreamMetadata metadata = StreamMetadata.create(streamId, userId, conversationId, model, provider);
            metadataStore.put(streamId, metadata);
            contentStore.put(streamId, new ArrayList<>());
            toolEventsStore.put(streamId, new ArrayList<>());
            if (conversationId != null) conversationIndex.put(conversationId, streamId);
            userIndex.computeIfAbsent(userId, k -> new ArrayList<>()).add(streamId);
            return Mono.just(metadata);
        }

        @Override
        public Mono<StreamMetadata> registerExternalStream(String streamId, String conversationId, String model, String provider) {
            StreamMetadata metadata = StreamMetadata.create(streamId, "internal", conversationId, model, provider);
            metadataStore.put(streamId, metadata);
            contentStore.put(streamId, new ArrayList<>());
            toolEventsStore.put(streamId, new ArrayList<>());
            if (conversationId != null) conversationIndex.put(conversationId, streamId);
            return Mono.just(metadata);
        }

        @Override
        public Mono<StreamMetadata> getMetadata(String streamId) {
            StreamMetadata metadata = metadataStore.get(streamId);
            return metadata != null ? Mono.just(metadata) : Mono.empty();
        }

        @Override
        public Mono<StreamMetadata> getByConversationId(String conversationId) {
            String streamId = conversationIndex.get(conversationId);
            if (streamId != null) {
                StreamMetadata metadata = metadataStore.get(streamId);
                if (metadata != null) {
                    return Mono.just(metadata);
                }
            }
            return Mono.empty();
        }

        @Override
        public Flux<String> getStreamingConversationIds(String userId) {
            List<String> streamIds = userIndex.getOrDefault(userId, List.of());
            return Flux.fromIterable(streamIds)
                    .filter(id -> {
                        StreamMetadata m = metadataStore.get(id);
                        return m != null && m.state().isActive();
                    })
                    .map(id -> metadataStore.get(id).conversationId());
        }

        @Override
        public Mono<Boolean> updateState(String streamId, StreamState newState) {
            StreamMetadata existing = metadataStore.get(streamId);
            if (existing != null) {
                metadataStore.put(streamId, existing.withState(newState));
                return Mono.just(true);
            }
            return Mono.just(false);
        }

        @Override
        public Mono<Boolean> complete(String streamId) {
            return updateState(streamId, StreamState.COMPLETED);
        }

        @Override
        public Mono<Boolean> stop(String streamId) {
            return updateState(streamId, StreamState.STOPPED_BY_USER);
        }

        @Override
        public Mono<Boolean> setAwaitingApproval(String streamId) {
            return updateState(streamId, StreamState.AWAITING_APPROVAL);
        }

        @Override
        public Mono<Boolean> error(String streamId, String errorMessage) {
            return updateState(streamId, StreamState.ERROR);
        }

        @Override
        public Mono<StreamState> getState(String streamId) {
            StreamMetadata metadata = metadataStore.get(streamId);
            return metadata != null ? Mono.just(metadata.state()) : Mono.empty();
        }

        @Override
        public Mono<Boolean> isActive(String streamId) {
            StreamMetadata metadata = metadataStore.get(streamId);
            return Mono.just(metadata != null && metadata.state().isActive());
        }

        @Override
        public Mono<Long> delete(String streamId) {
            metadataStore.remove(streamId);
            contentStore.remove(streamId);
            toolEventsStore.remove(streamId);
            return Mono.just(1L);
        }

        @Override
        public Mono<Long> appendContent(String streamId, String chunk) {
            contentStore.computeIfAbsent(streamId, k -> new ArrayList<>()).add(chunk);
            return Mono.just((long) contentStore.get(streamId).size());
        }

        @Override
        public Mono<String> getFullContent(String streamId) {
            List<String> chunks = contentStore.getOrDefault(streamId, List.of());
            return Mono.just(String.join("", chunks));
        }

        @Override
        public Flux<String> getContentChunks(String streamId) {
            List<String> chunks = contentStore.getOrDefault(streamId, List.of());
            return Flux.fromIterable(new ArrayList<>(chunks));
        }

        @Override
        public Mono<Long> appendToolEvent(String streamId, String toolEventJson) {
            toolEventsStore.computeIfAbsent(streamId, k -> new ArrayList<>()).add(toolEventJson);
            return Mono.just((long) toolEventsStore.get(streamId).size());
        }

        @Override
        public Flux<String> getToolEvents(String streamId) {
            List<String> events = toolEventsStore.getOrDefault(streamId, List.of());
            return Flux.fromIterable(new ArrayList<>(events));
        }

        @Override
        public Mono<Long> publishStop(String streamId) {
            return stop(streamId).map(b -> 1L);
        }

        @Override
        public Flux<String> subscribeToStop(String streamId) {
            return Flux.empty();
        }

        @Override
        public Mono<Boolean> setCancelKey(String streamId) {
            return Mono.just(true);
        }

        @Override
        public Mono<Boolean> touch(String streamId) {
            return Mono.just(true);
        }

        @Override
        public Mono<Boolean> updateConversationId(String streamId, String newConversationId) {
            StreamMetadata existing = metadataStore.get(streamId);
            if (existing != null) {
                conversationIndex.remove(existing.conversationId());
                StreamMetadata updated = new StreamMetadata(
                        streamId, existing.userId(), newConversationId,
                        existing.model(), existing.provider(),
                        existing.state(), existing.createdAt(),
                        Instant.now(), existing.contentLength()
                );
                metadataStore.put(streamId, updated);
                conversationIndex.put(newConversationId, streamId);
                return Mono.just(true);
            }
            return Mono.just(false);
        }

        /** Clear all state between tests. */
        public void clearAll() {
            metadataStore.clear();
            contentStore.clear();
            toolEventsStore.clear();
            conversationIndex.clear();
            userIndex.clear();
        }
    }

    // ========================== In-Memory StreamPubSubService ==========================

    /**
     * In-memory implementation of StreamPubSubService for integration tests.
     */
    static class InMemoryIntegrationStreamPubSubService extends StreamPubSubService {

        private final Map<String, Sinks.Many<StreamEvent>> channelSinks = new ConcurrentHashMap<>();
        private final ObjectMapper localObjectMapper;
        private final StreamStateService localStateService;

        InMemoryIntegrationStreamPubSubService(ObjectMapper objectMapper, StreamStateService stateService) {
            super(null, null, objectMapper, stateService);
            this.localObjectMapper = objectMapper;
            this.localStateService = stateService;
        }

        private Sinks.Many<StreamEvent> getOrCreateSink(String streamId) {
            return channelSinks.computeIfAbsent(streamId,
                    k -> Sinks.many().multicast().onBackpressureBuffer(1000));
        }

        @Override
        public Mono<Long> publish(String streamId, StreamEvent event) {
            Sinks.Many<StreamEvent> sink = getOrCreateSink(streamId);
            sink.tryEmitNext(event);
            return Mono.just(1L);
        }

        @Override
        public Mono<Long> publishAndStoreToolEvent(String streamId, StreamEvent event) {
            try {
                String json = localObjectMapper.writeValueAsString(event);
                return localStateService.appendToolEvent(streamId, json)
                        .then(publish(streamId, event));
            } catch (Exception e) {
                return Mono.error(e);
            }
        }

        @Override
        public Mono<Long> publishContent(String streamId, String content) {
            return publish(streamId, StreamEvent.content(streamId, content));
        }

        @Override
        public Mono<Long> publishThinking(String streamId, String thinking) {
            return publish(streamId, StreamEvent.thinking(streamId, thinking));
        }

        @Override
        public Mono<Long> publishThinkingSection(String streamId, String title, String content) {
            return publish(streamId, StreamEvent.thinkingSection(streamId, title, content));
        }

        @Override
        public Mono<Long> publishComplete(String streamId, String fullContent, int totalTokens) {
            return publish(streamId, StreamEvent.completed(streamId, fullContent, totalTokens));
        }

        @Override
        public Mono<Long> publishError(String streamId, String error, String errorCode, boolean retryable) {
            return publish(streamId, StreamEvent.error(streamId, error, errorCode, retryable));
        }

        @Override
        public Mono<Long> publishStopped(String streamId, String partialContent) {
            return publish(streamId, StreamEvent.stopped(streamId, partialContent));
        }

        @Override
        public Mono<Long> publishHeartbeat(String streamId) {
            return publish(streamId, StreamEvent.heartbeat(streamId));
        }

        @Override
        public Mono<Long> publishTitleUpdated(String streamId, String conversationId, String title) {
            return publish(streamId, StreamEvent.titleUpdated(streamId, conversationId, title));
        }

        @Override
        public Flux<StreamEvent> subscribe(String streamId) {
            Sinks.Many<StreamEvent> sink = getOrCreateSink(streamId);
            return sink.asFlux()
                    .takeUntil(event ->
                            event instanceof StreamEvent.StreamCompleted ||
                            event instanceof StreamEvent.StreamError ||
                            event instanceof StreamEvent.StreamStopped);
        }
    }
}
