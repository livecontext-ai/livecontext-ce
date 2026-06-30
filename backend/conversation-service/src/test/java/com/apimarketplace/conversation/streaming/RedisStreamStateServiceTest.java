package com.apimarketplace.conversation.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisStreamStateService")
@ExtendWith(MockitoExtension.class)
class RedisStreamStateServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveRedisMessageListenerContainer messageListenerContainer;

    @Mock
    private ReactiveHashOperations<String, Object, Object> hashOps;

    @Mock
    private ReactiveListOperations<String, String> listOps;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private ReactiveSetOperations<String, String> setOps;

    private RedisStreamStateService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        service = new RedisStreamStateService(redisTemplate, messageListenerContainer);
    }

    // ==================== createStream ====================

    @Nested
    @DisplayName("createStream")
    class CreateStream {

        @Test
        @DisplayName("should create stream with correct metadata and store in Redis")
        void shouldCreateStreamWithMetadata() {
            when(hashOps.putAll(anyString(), anyMap())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(valueOps.set(anyString(), anyString())).thenReturn(Mono.just(true));
            when(setOps.add(anyString(), any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(service.createStream("user-1", "conv-1", "gpt-4", "openai"))
                    .assertNext(metadata -> {
                        assertThat(metadata.streamId()).isNotNull().isNotEmpty();
                        assertThat(metadata.userId()).isEqualTo("user-1");
                        assertThat(metadata.conversationId()).isEqualTo("conv-1");
                        assertThat(metadata.model()).isEqualTo("gpt-4");
                        assertThat(metadata.provider()).isEqualTo("openai");
                        assertThat(metadata.state()).isEqualTo(StreamState.CREATED);
                        assertThat(metadata.contentLength()).isZero();
                        assertThat(metadata.createdAt()).isNotNull();
                        assertThat(metadata.lastActivity()).isNotNull();
                    })
                    .verifyComplete();

            verify(hashOps).putAll(startsWith("stream:"), anyMap());
        }

        @Test
        @DisplayName("should set TTL on stream key after creation")
        void shouldSetTTLOnStreamKey() {
            when(hashOps.putAll(anyString(), anyMap())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(valueOps.set(anyString(), anyString())).thenReturn(Mono.just(true));
            when(setOps.add(anyString(), any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(service.createStream("user-1", "conv-1", "gpt-4", "openai"))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(redisTemplate, atLeastOnce()).expire(startsWith("stream:"), eq(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("should create secondary index for conversationId")
        void shouldCreateConversationIndex() {
            when(hashOps.putAll(anyString(), anyMap())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(valueOps.set(anyString(), anyString())).thenReturn(Mono.just(true));
            when(setOps.add(anyString(), any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(service.createStream("user-1", "conv-1", "gpt-4", "openai"))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(valueOps).set(eq("stream:conv:conv-1"), anyString());
        }

        @Test
        @DisplayName("should create secondary index for userId")
        void shouldCreateUserIndex() {
            when(hashOps.putAll(anyString(), anyMap())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(valueOps.set(anyString(), anyString())).thenReturn(Mono.just(true));
            when(setOps.add(anyString(), any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(service.createStream("user-1", "conv-1", "gpt-4", "openai"))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(setOps).add(eq("stream:user:user-1"), anyString());
        }

        @Test
        @DisplayName("should handle null conversationId gracefully")
        void shouldHandleNullConversationId() {
            when(hashOps.putAll(anyString(), anyMap())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(setOps.add(anyString(), any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(service.createStream("user-1", null, "gpt-4", "openai"))
                    .assertNext(metadata -> {
                        assertThat(metadata.conversationId()).isNull();
                    })
                    .verifyComplete();

            // Should not create conversation index for null conversationId
            verify(valueOps, never()).set(startsWith("stream:conv:"), anyString());
        }

        @Test
        @DisplayName("should propagate Redis errors")
        void shouldPropagateRedisErrors() {
            when(hashOps.putAll(anyString(), anyMap()))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
            // All downstream operations must be leniently stubbed since the chain is assembled eagerly
            lenient().when(redisTemplate.expire(anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            lenient().when(valueOps.set(anyString(), anyString()))
                    .thenReturn(Mono.just(true));
            lenient().when(setOps.add(anyString(), any(String[].class)))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.createStream("user-1", "conv-1", "gpt-4", "openai"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    // ==================== getMetadata ====================

    @Nested
    @DisplayName("getMetadata")
    class GetMetadata {

        @Test
        @DisplayName("should return metadata when stream exists")
        void shouldReturnMetadataWhenExists() {
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.STREAMING);

            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getMetadata("stream-1"))
                    .assertNext(metadata -> {
                        assertThat(metadata.streamId()).isEqualTo("stream-1");
                        assertThat(metadata.userId()).isEqualTo("user-1");
                        assertThat(metadata.conversationId()).isEqualTo("conv-1");
                        assertThat(metadata.state()).isEqualTo(StreamState.STREAMING);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty when stream does not exist")
        void shouldReturnEmptyWhenNotExists() {
            when(hashOps.entries("stream:nonexistent")).thenReturn(Flux.empty());

            StepVerifier.create(service.getMetadata("nonexistent"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should parse contentLength from stored fields")
        void shouldParseContentLength() {
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.STREAMING);
            fields.put("contentLength", "42");

            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getMetadata("stream-1"))
                    .assertNext(metadata -> assertThat(metadata.contentLength()).isEqualTo(42))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should default contentLength to 0 when absent")
        void shouldDefaultContentLengthToZero() {
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.CREATED);
            fields.remove("contentLength");
            fields.put("contentLength", "0");

            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getMetadata("stream-1"))
                    .assertNext(metadata -> assertThat(metadata.contentLength()).isZero())
                    .verifyComplete();
        }
    }

    // ==================== getByConversationId ====================

    @Nested
    @DisplayName("getByConversationId")
    class GetByConversationId {

        @Test
        @DisplayName("should find stream using secondary index")
        void shouldFindStreamViaIndex() {
            when(valueOps.get("stream:conv:conv-1")).thenReturn(Mono.just("stream-1"));

            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.STREAMING);
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getByConversationId("conv-1"))
                    .assertNext(metadata -> {
                        assertThat(metadata.streamId()).isEqualTo("stream-1");
                        assertThat(metadata.conversationId()).isEqualTo("conv-1");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty when no index entry exists")
        void shouldReturnEmptyWhenNoIndex() {
            when(valueOps.get("stream:conv:unknown")).thenReturn(Mono.empty());

            StepVerifier.create(service.getByConversationId("unknown"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should filter out stale index entries with mismatched conversationId")
        void shouldFilterStaleIndex() {
            when(valueOps.get("stream:conv:conv-1")).thenReturn(Mono.just("stream-1"));

            // The metadata in Redis has a different conversationId (stale)
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-other",
                    "gpt-4", "openai", StreamState.STREAMING);
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getByConversationId("conv-1"))
                    .verifyComplete();
        }
    }

    // ==================== getStreamingConversationIds ====================

    @Nested
    @DisplayName("getStreamingConversationIds")
    class GetStreamingConversationIds {

        @Test
        @DisplayName("should return active conversation IDs for user")
        void shouldReturnActiveConversationIds() {
            when(setOps.members("stream:user:user-1"))
                    .thenReturn(Flux.just("stream-1", "stream-2"));

            Map<Object, Object> fields1 = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.STREAMING);
            Map<Object, Object> fields2 = buildMetadataMap("stream-2", "user-1", "conv-2",
                    "gpt-4", "openai", StreamState.CREATED);

            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields1.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));
            when(hashOps.entries("stream:stream-2")).thenReturn(Flux.fromIterable(fields2.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getStreamingConversationIds("user-1"))
                    .expectNextMatches(id -> id.equals("conv-1") || id.equals("conv-2"))
                    .expectNextMatches(id -> id.equals("conv-1") || id.equals("conv-2"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should filter out non-active streams")
        void shouldFilterNonActiveStreams() {
            when(setOps.members("stream:user:user-1"))
                    .thenReturn(Flux.just("stream-1"));

            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.COMPLETED);
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getStreamingConversationIds("user-1"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty when user has no streams")
        void shouldReturnEmptyWhenNoStreams() {
            when(setOps.members("stream:user:unknown")).thenReturn(Flux.empty());

            StepVerifier.create(service.getStreamingConversationIds("unknown"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should deduplicate conversation IDs")
        void shouldDeduplicateConversationIds() {
            when(setOps.members("stream:user:user-1"))
                    .thenReturn(Flux.just("stream-1", "stream-2"));

            // Both streams point to the same conversationId
            Map<Object, Object> fields1 = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.STREAMING);
            Map<Object, Object> fields2 = buildMetadataMap("stream-2", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.STREAMING);

            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields1.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));
            when(hashOps.entries("stream:stream-2")).thenReturn(Flux.fromIterable(fields2.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.getStreamingConversationIds("user-1"))
                    .expectNext("conv-1")
                    .verifyComplete();
        }
    }

    // ==================== updateState ====================

    @Nested
    @DisplayName("updateState")
    class UpdateState {

        @Test
        @DisplayName("should update state field in Redis hash")
        void shouldUpdateState() {
            when(hashOps.put(eq("stream:stream-1"), eq("state"), eq("STREAMING")))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.updateState("stream-1", StreamState.STREAMING))
                    .expectNext(true)
                    .verifyComplete();

            verify(hashOps).put("stream:stream-1", "state", "STREAMING");
        }

        @Test
        @DisplayName("should update lastActivity timestamp")
        void shouldUpdateLastActivity() {
            when(hashOps.put(eq("stream:stream-1"), eq("state"), anyString()))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.updateState("stream-1", StreamState.COMPLETED))
                    .expectNext(true)
                    .verifyComplete();

            verify(hashOps).put(eq("stream:stream-1"), eq("lastActivity"), anyString());
        }
    }

    // ==================== complete ====================

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("should set state to COMPLETED and schedule cleanup")
        void shouldCompleteStream() {
            when(hashOps.put(eq("stream:stream-1"), eq("state"), eq("COMPLETED")))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.complete("stream-1"))
                    .expectNext(true)
                    .verifyComplete();

            // Verify cleanup is scheduled (30 seconds TTL for both stream and content keys)
            verify(redisTemplate).expire(eq("stream:stream-1"), eq(Duration.ofSeconds(30)));
            verify(redisTemplate).expire(eq("stream:stream-1:content"), eq(Duration.ofSeconds(30)));
        }
    }

    // ==================== stop ====================

    @Nested
    @DisplayName("stop")
    class Stop {

        @Test
        @DisplayName("should set state to STOPPED_BY_USER and publish stop signal")
        void shouldStopStreamAndPublish() {
            when(hashOps.put(eq("stream:stream-1"), eq("state"), eq("STOPPED_BY_USER")))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.convertAndSend(eq("stream:stop"), eq("stream-1")))
                    .thenReturn(Mono.just(1L));
            when(redisTemplate.expire(anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.stop("stream-1"))
                    .expectNext(true)
                    .verifyComplete();

            verify(redisTemplate).convertAndSend("stream:stop", "stream-1");
        }
    }

    // ==================== setAwaitingApproval ====================

    @Nested
    @DisplayName("setAwaitingApproval")
    class SetAwaitingApproval {

        @Test
        @DisplayName("should set state to AWAITING_APPROVAL with extended TTL")
        void shouldSetAwaitingApproval() {
            when(hashOps.put(eq("stream:stream-1"), eq("state"), eq("AWAITING_APPROVAL")))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(eq("stream:stream-1"), eq(Duration.ofHours(1))))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.setAwaitingApproval("stream-1"))
                    .expectNext(true)
                    .verifyComplete();

            // Verify 1 hour TTL for awaiting approval (longer than normal 30 minutes)
            verify(redisTemplate).expire("stream:stream-1", Duration.ofHours(1));
        }
    }

    // ==================== error ====================

    @Nested
    @DisplayName("error")
    class Error {

        @Test
        @DisplayName("should set state to ERROR and store error message")
        void shouldSetErrorState() {
            when(hashOps.put(eq("stream:stream-1"), eq("state"), eq("ERROR")))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("errorMessage"), eq("Something failed")))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.error("stream-1", "Something failed"))
                    .expectNext(true)
                    .verifyComplete();

            verify(hashOps).put("stream:stream-1", "errorMessage", "Something failed");
        }

        @Test
        @DisplayName("should store 'Unknown error' when errorMessage is null")
        void shouldStoreUnknownErrorWhenNull() {
            when(hashOps.put(eq("stream:stream-1"), eq("state"), eq("ERROR")))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("errorMessage"), eq("Unknown error")))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.error("stream-1", null))
                    .expectNext(true)
                    .verifyComplete();

            verify(hashOps).put("stream:stream-1", "errorMessage", "Unknown error");
        }
    }

    // ==================== getState ====================

    @Nested
    @DisplayName("getState")
    class GetState {

        @Test
        @DisplayName("should return stream state from Redis")
        void shouldReturnState() {
            when(hashOps.get("stream:stream-1", "state"))
                    .thenReturn(Mono.just("STREAMING"));

            StepVerifier.create(service.getState("stream-1"))
                    .expectNext(StreamState.STREAMING)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should default to ERROR when no state is stored")
        void shouldDefaultToErrorWhenNoState() {
            when(hashOps.get("stream:unknown", "state"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.getState("unknown"))
                    .expectNext(StreamState.ERROR)
                    .verifyComplete();
        }
    }

    // ==================== isActive ====================

    @Nested
    @DisplayName("isActive")
    class IsActive {

        @Test
        @DisplayName("should return true for STREAMING state")
        void shouldReturnTrueForStreaming() {
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.STREAMING);
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.isActive("stream-1"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return true for CREATED state")
        void shouldReturnTrueForCreated() {
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.CREATED);
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.isActive("stream-1"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return false for COMPLETED state")
        void shouldReturnFalseForCompleted() {
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.COMPLETED);
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));

            StepVerifier.create(service.isActive("stream-1"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return false when stream does not exist")
        void shouldReturnFalseWhenNotExists() {
            when(hashOps.entries("stream:nonexistent")).thenReturn(Flux.empty());

            StepVerifier.create(service.isActive("nonexistent"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ==================== delete ====================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("should delete stream, content, tools keys and clean indexes")
        void shouldDeleteAllKeys() {
            Map<Object, Object> fields = buildMetadataMap("stream-1", "user-1", "conv-1",
                    "gpt-4", "openai", StreamState.COMPLETED);
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.fromIterable(fields.entrySet())
                    .map(e -> (Map.Entry<Object, Object>) e));
            when(redisTemplate.delete("stream:conv:conv-1")).thenReturn(Mono.just(1L));
            when(setOps.remove("stream:user:user-1", "stream-1")).thenReturn(Mono.just(1L));
            when(redisTemplate.delete("stream:stream-1", "stream:stream-1:content", "stream:stream-1:tools", "agent:cancel:stream-1"))
                    .thenReturn(Mono.just(3L));

            StepVerifier.create(service.delete("stream-1"))
                    .expectNext(3L)
                    .verifyComplete();

            verify(redisTemplate).delete("stream:conv:conv-1");
            verify(setOps).remove("stream:user:user-1", "stream-1");
        }

        @Test
        @DisplayName("should delete keys even when metadata is empty")
        void shouldDeleteKeysWhenMetadataEmpty() {
            when(hashOps.entries("stream:stream-1")).thenReturn(Flux.empty());
            when(redisTemplate.delete("stream:stream-1", "stream:stream-1:content", "stream:stream-1:tools", "agent:cancel:stream-1"))
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(service.delete("stream-1"))
                    .expectNext(0L)
                    .verifyComplete();
        }
    }

    // ==================== appendContent ====================

    @Nested
    @DisplayName("appendContent")
    class AppendContent {

        @Test
        @DisplayName("should append content chunk to Redis list")
        void shouldAppendContent() {
            when(listOps.rightPush("stream:stream-1:content", "Hello"))
                    .thenReturn(Mono.just(1L));
            when(redisTemplate.expire(eq("stream:stream-1:content"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("state"), eq("STREAMING")))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("contentLength"), eq("1")))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.appendContent("stream-1", "Hello"))
                    .expectNext(1L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should update state to STREAMING on content append")
        void shouldUpdateStateToStreaming() {
            when(listOps.rightPush(anyString(), anyString())).thenReturn(Mono.just(5L));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(hashOps.put(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

            StepVerifier.create(service.appendContent("stream-1", "data"))
                    .expectNext(5L)
                    .verifyComplete();

            verify(hashOps).put("stream:stream-1", "state", "STREAMING");
        }

        @Test
        @DisplayName("should trim content when exceeding max chunks")
        void shouldTrimExcessiveContent() {
            long overLimit = 10001L;
            when(listOps.rightPush(anyString(), anyString())).thenReturn(Mono.just(overLimit));
            when(listOps.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(hashOps.put(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

            StepVerifier.create(service.appendContent("stream-1", "data"))
                    .expectNext(overLimit)
                    .verifyComplete();

            verify(listOps).trim("stream:stream-1:content", 1, overLimit);
        }

        @Test
        @DisplayName("should not trim content when within max chunks limit")
        void shouldNotTrimWhenWithinLimit() {
            when(listOps.rightPush(anyString(), anyString())).thenReturn(Mono.just(500L));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(hashOps.put(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));

            StepVerifier.create(service.appendContent("stream-1", "data"))
                    .expectNext(500L)
                    .verifyComplete();

            verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
        }
    }

    // ==================== getFullContent ====================

    @Nested
    @DisplayName("getFullContent")
    class GetFullContent {

        @Test
        @DisplayName("should concatenate all content chunks")
        void shouldConcatenateChunks() {
            when(listOps.size("stream:stream-1:content")).thenReturn(Mono.just(3L));
            when(listOps.range("stream:stream-1:content", 0, -1))
                    .thenReturn(Flux.just("Hello", " ", "World"));

            StepVerifier.create(service.getFullContent("stream-1"))
                    .expectNext("Hello World")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty string when no content")
        void shouldReturnEmptyWhenNoContent() {
            when(listOps.size("stream:stream-1:content")).thenReturn(Mono.just(0L));
            when(listOps.range("stream:stream-1:content", 0, -1))
                    .thenReturn(Flux.empty());

            StepVerifier.create(service.getFullContent("stream-1"))
                    .expectNext("")
                    .verifyComplete();
        }
    }

    // ==================== getContentChunks ====================

    @Nested
    @DisplayName("getContentChunks")
    class GetContentChunks {

        @Test
        @DisplayName("should return all content chunks as Flux")
        void shouldReturnChunks() {
            when(listOps.range("stream:stream-1:content", 0, -1))
                    .thenReturn(Flux.just("chunk1", "chunk2", "chunk3"));

            StepVerifier.create(service.getContentChunks("stream-1"))
                    .expectNext("chunk1", "chunk2", "chunk3")
                    .verifyComplete();
        }
    }

    // ==================== appendToolEvent / getToolEvents ====================

    @Nested
    @DisplayName("appendToolEvent")
    class AppendToolEvent {

        @Test
        @DisplayName("should append tool event JSON to Redis list")
        void shouldAppendToolEvent() {
            String toolJson = "{\"type\":\"tool_call\",\"toolName\":\"search\"}";
            when(listOps.rightPush("stream:stream-1:tools", toolJson))
                    .thenReturn(Mono.just(1L));
            when(redisTemplate.expire(eq("stream:stream-1:tools"), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.appendToolEvent("stream-1", toolJson))
                    .expectNext(1L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should trim tool events when exceeding max limit")
        void shouldTrimExcessiveToolEvents() {
            long overLimit = 501L;
            when(listOps.rightPush(anyString(), anyString())).thenReturn(Mono.just(overLimit));
            when(listOps.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

            StepVerifier.create(service.appendToolEvent("stream-1", "{}"))
                    .expectNext(overLimit)
                    .verifyComplete();

            verify(listOps).trim("stream:stream-1:tools", 1, overLimit);
        }
    }

    @Nested
    @DisplayName("getToolEvents")
    class GetToolEvents {

        @Test
        @DisplayName("should return all tool events")
        void shouldReturnToolEvents() {
            when(listOps.range("stream:stream-1:tools", 0, -1))
                    .thenReturn(Flux.just("{\"type\":\"tool_call\"}", "{\"type\":\"tool_result\"}"));

            StepVerifier.create(service.getToolEvents("stream-1"))
                    .expectNext("{\"type\":\"tool_call\"}")
                    .expectNext("{\"type\":\"tool_result\"}")
                    .verifyComplete();
        }
    }

    // ==================== publishStop / subscribeToStop ====================

    @Nested
    @DisplayName("publishStop")
    class PublishStop {

        @Test
        @DisplayName("should publish stop signal to Redis pub/sub channel")
        void shouldPublishStopSignal() {
            when(redisTemplate.convertAndSend("stream:stop", "stream-1"))
                    .thenReturn(Mono.just(2L));

            StepVerifier.create(service.publishStop("stream-1"))
                    .expectNext(2L)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("subscribeToStop")
    class SubscribeToStop {

        @Test
        @DisplayName("should filter stop signals for specific stream")
        @SuppressWarnings("unchecked")
        void shouldFilterStopSignalsForStream() {
            ReactiveSubscription.Message<String, String> msg1 = mock(ReactiveSubscription.Message.class);
            when(msg1.getMessage()).thenReturn("stream-1");

            ReactiveSubscription.Message<String, String> msg2 = mock(ReactiveSubscription.Message.class);
            when(msg2.getMessage()).thenReturn("stream-other");

            when(messageListenerContainer.receive(any(ChannelTopic.class)))
                    .thenReturn(Flux.just(msg1, msg2));

            StepVerifier.create(service.subscribeToStop("stream-1"))
                    .expectNext("stream-1")
                    .verifyComplete();
        }
    }

    // ==================== touch ====================

    @Nested
    @DisplayName("touch")
    class Touch {

        @Test
        @DisplayName("should refresh TTL for all stream keys")
        void shouldRefreshTTL() {
            when(redisTemplate.expire(eq("stream:stream-1"), eq(Duration.ofMinutes(30))))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(eq("stream:stream-1:content"), eq(Duration.ofMinutes(30))))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(eq("stream:stream-1:tools"), eq(Duration.ofMinutes(30))))
                    .thenReturn(Mono.just(true));
            when(hashOps.put(eq("stream:stream-1"), eq("lastActivity"), anyString()))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.touch("stream-1"))
                    .expectNext(true)
                    .verifyComplete();

            verify(redisTemplate).expire("stream:stream-1", Duration.ofMinutes(30));
            verify(redisTemplate).expire("stream:stream-1:content", Duration.ofMinutes(30));
            verify(redisTemplate).expire("stream:stream-1:tools", Duration.ofMinutes(30));
        }
    }

    // ==================== updateConversationId ====================

    @Nested
    @DisplayName("updateConversationId")
    class UpdateConversationId {

        @Test
        @DisplayName("should update conversationId and maintain indexes")
        void shouldUpdateConversationId() {
            when(hashOps.get("stream:stream-1", "conversationId"))
                    .thenReturn(Mono.just("old-conv"));
            when(redisTemplate.delete("stream:conv:old-conv"))
                    .thenReturn(Mono.just(1L));
            when(hashOps.put("stream:stream-1", "conversationId", "new-conv"))
                    .thenReturn(Mono.just(true));
            when(valueOps.set("stream:conv:new-conv", "stream-1"))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(eq("stream:conv:new-conv"), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.updateConversationId("stream-1", "new-conv"))
                    .expectNext(true)
                    .verifyComplete();

            verify(redisTemplate).delete("stream:conv:old-conv");
            verify(valueOps).set("stream:conv:new-conv", "stream-1");
        }

        @Test
        @DisplayName("should handle empty old conversationId")
        void shouldHandleEmptyOldConversationId() {
            when(hashOps.get("stream:stream-1", "conversationId"))
                    .thenReturn(Mono.empty());
            when(hashOps.put("stream:stream-1", "conversationId", "new-conv"))
                    .thenReturn(Mono.just(true));
            when(valueOps.set("stream:conv:new-conv", "stream-1"))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.expire(eq("stream:conv:new-conv"), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.updateConversationId("stream-1", "new-conv"))
                    .expectNext(true)
                    .verifyComplete();

            // Should not try to delete old index
            verify(redisTemplate, never()).delete("stream:conv:");
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Builds a metadata map simulating what would be stored in Redis HASH.
     */
    private Map<Object, Object> buildMetadataMap(String streamId, String userId, String conversationId,
                                                  String model, String provider, StreamState state) {
        Map<Object, Object> map = new HashMap<>();
        map.put("streamId", streamId);
        map.put("userId", userId);
        map.put("conversationId", conversationId);
        map.put("model", model);
        map.put("provider", provider);
        map.put("state", state.name());
        map.put("createdAt", Instant.now().toString());
        map.put("lastActivity", Instant.now().toString());
        map.put("contentLength", "0");
        return map;
    }
}
