package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.conversation.domain.stream.StreamEvent;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ai.ChatStreamingService;
import com.apimarketplace.conversation.streaming.RedisStreamingOutput;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.common.web.TenantResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Initializes new chat streams.
 * Single Responsibility: Create and start streaming for new chat requests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamInitializer {

    private final ChatStreamingService chatStreamingService;
    private final ConversationHistoryService conversationHistoryService;
    private final StreamStateService stateService;
    private final StreamPubSubService pubSubService;

    /**
     * Initialize a stream asynchronously for WebSocket-based streaming.
     * Returns JSON {conversationId, streamId, model} immediately.
     * Events flow via Redis Pub/Sub to WebSocket channel: ws:conversation:{conversationId}
     */
    public Mono<ResponseEntity<Map<String, String>>> initializeStreamAsync(ChatRequest request, String userId) {
        log.info("WS stream init - User: {}, Message length: {}, Conversation: {}",
                userId,
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getConversationId());

        return getOrCreateConversationId(request, userId)
                .flatMap(conversationId -> createStreamWithRetry(userId, conversationId, request)
                        .map(metadata -> {
                            String newStreamId = metadata.streamId();
                            log.info("WS stream created: {} for conversation: {} user: {}", newStreamId, conversationId, userId);

                            // Start async processing (publishes to Redis Pub/Sub)
                            startAsyncProcessing(request, newStreamId, conversationId);

                            // Publish stream_started to Redis so WS clients get it
                            pubSubService.publish(newStreamId,
                                    StreamEvent.started(newStreamId, conversationId, request.getModel()))
                                    .subscribe();

                            return ResponseEntity.ok(Map.of(
                                    "conversationId", conversationId,
                                    "streamId", newStreamId,
                                    "model", request.getModel()
                            ));
                        })
                        .onErrorResume(error -> {
                            if (!isTransientStreamStoreFailure(error)) {
                                return Mono.error(error);
                            }
                            log.warn("Transient stream-store failure while creating chat stream for conversation {}: {}",
                                    conversationId, error.getMessage());
                            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .body(Map.of(
                                            "code", "STREAM_INIT_TEMPORARILY_UNAVAILABLE",
                                            "message", "Streaming is temporarily unavailable. Please retry.",
                                            "retryable", "true",
                                            "conversationId", conversationId
                                    )));
                        }));
    }

    private Mono<StreamMetadata> createStreamWithRetry(String userId, String conversationId, ChatRequest request) {
        return stateService.createStream(userId, conversationId, request.getModel(), request.getProvider())
                .retryWhen(Retry.backoff(2, Duration.ofMillis(150))
                        .maxBackoff(Duration.ofMillis(500))
                        .filter(this::isTransientStreamStoreFailure)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private boolean isTransientStreamStoreFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("QueryTimeoutException")
                    || className.contains("RedisCommandTimeoutException")
                    || (message != null && message.toLowerCase().contains("redis command timed out"))
                    || (message != null && message.toLowerCase().contains("command timed out"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Get existing conversation ID or create a new one.
     * When agentId is present in the request, links the conversation to the agent.
     */
    private Mono<String> getOrCreateConversationId(ChatRequest request, String userId) {
        if (request.getConversationId() == null || request.getConversationId().isEmpty()) {
            return Mono.fromCallable(() -> {
                final String[] createdConversationId = new String[1];
                TenantResolver.runWithOrgScope(request.getOrgId(), () -> {
                // PR21 R2 - thread workspace identity into the write path. ChatControllerV3
                // already populated request.orgId from the inbound X-Organization-ID header;
                // without this propagation every new team-workspace v3 chat would land with
                // organization_id = NULL → invisible to the org-strict sidebar finder.
                // Reviewer B+C round-1 caught this as the headline missing piece.
                String newConversationId = conversationHistoryService.createConversation(
                        userId, request.getOrgId(), "Generating Title...",
                        request.getModel(), request.getProvider(), request.getAgentId(),
                        initialChatConfig(request)
                );
                log.info("Created conversation: {} for user: {} (org: {}) agentId: {}",
                        newConversationId, userId, request.getOrgId(), request.getAgentId());
                request.setConversationId(newConversationId);
                createdConversationId[0] = newConversationId;
                });
                return createdConversationId[0];
            }).subscribeOn(Schedulers.boundedElastic());
        } else {
            if (request.isDefaultSkillIdsProvided()) {
                return Mono.fromCallable(() -> {
                    conversationHistoryService.persistDefaultSkillIds(
                            request.getConversationId(),
                            userId,
                            request.getOrgId(),
                            request.getDefaultSkillIds() != null ? request.getDefaultSkillIds() : List.of());
                    return request.getConversationId();
                }).subscribeOn(Schedulers.boundedElastic());
            }
            return Mono.just(request.getConversationId());
        }
    }

    private Map<String, Object> initialChatConfig(ChatRequest request) {
        // Shared with the CE MonolithChatController - see ChatRequestConfigMapper.
        return ChatRequestConfigMapper.initialChatConfig(request);
    }

    /**
     * Start async processing for the stream.
     */
    private void startAsyncProcessing(ChatRequest request, String streamId, String conversationId) {
        Mono.fromRunnable(() -> {
                    TenantResolver.runWithOrgScope(request.getOrgId(), () -> {
                    try {
                        RedisStreamingOutput streamOutput = new RedisStreamingOutput(
                                streamId, stateService, pubSubService,
                                conversationId, request.getModel(), request.getProvider()
                        );
                        chatStreamingService.processStreamingRequest(request, streamOutput, streamId);
                    } catch (Exception e) {
                        log.error("Error during streaming: {}", e.getMessage(), e);
                        stateService.error(streamId, e.getMessage()).subscribe();
                        pubSubService.publishError(streamId, e.getMessage(), "STREAM_ERROR", true).subscribe();
                    }
                    });
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
}
