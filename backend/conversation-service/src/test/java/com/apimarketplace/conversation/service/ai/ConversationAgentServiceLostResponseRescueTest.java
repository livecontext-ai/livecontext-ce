package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.queue.AgentQueueProducer;
import com.apimarketplace.agent.client.queue.RedisResultWaiter;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.StreamService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.controller.v3.chat.StreamStopHandler;
import com.apimarketplace.conversation.streaming.InMemoryStreamStateService;
import com.apimarketplace.conversation.streaming.StreamInterruptionService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "The agent finished answering but the message is not in the history."
 *
 * <p>The reply of a chat turn streams straight from its producer (agent-service's direct loop,
 * or the CLI bridge) into Redis and the browser, and reaches the history only when THIS service
 * writes it after the producer's answer comes back. Two branches lost it for good:
 * <ul>
 *   <li>the answer never came back ({@code AgentClient} / {@code BridgeClient} turn every
 *       exception into {@code null}): only an {@code error} was sent, nothing written, although
 *       the reply sat in Redis (and agent-service had already published {@code done});</li>
 *   <li>the write itself failed: the error was logged and the turn still ended in {@code done}.</li>
 * </ul>
 * The queue transport (the one cloud chat uses) loses it in two more ways: the result await
 * THROWS instead of returning null, and the worker's failure envelope carries no content at all.
 * All of them now save the Redis-buffered reply, with the turn's execution and agent attribution. The
 * turn then ends by what Redis says about the producer: {@code done} only when it COMPLETED,
 * never for a partial. The real {@link StreamInterruptionService} is wired, only the stores are
 * mocked, so the chain from the lost answer to the saved message is what is exercised.
 */
@DisplayName("ConversationAgentService - a reply whose producer's answer was lost is still saved")
class ConversationAgentServiceLostResponseRescueTest {

    private static final String STREAM_ID = "stream-lost-1";
    private static final String CONV_ID = "conv-1";

    private AgentContextBuilder contextBuilder;
    private AgentClient agentClient;
    private BridgeClient bridgeClient;
    private MessageService messageService;
    private StreamStateService stateService;
    private StreamPubSubService pubSubService;
    private StreamService streamService;
    private ConversationHistoryService historyService;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> claims;
    private StreamInterruptionService rescue;
    private StreamingOutput streamOutput;
    private CreditConsumptionClient creditClient;
    private AgentQueueProducer queueProducer;
    private RedisResultWaiter resultWaiter;
    private ConversationAgentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        agentClient = mock(AgentClient.class);
        bridgeClient = mock(BridgeClient.class);
        messageService = mock(MessageService.class);
        stateService = mock(StreamStateService.class);
        pubSubService = mock(StreamPubSubService.class);
        streamService = mock(StreamService.class);
        historyService = mock(ConversationHistoryService.class);
        streamOutput = mock(StreamingOutput.class);
        queueProducer = mock(AgentQueueProducer.class);
        resultWaiter = mock(RedisResultWaiter.class);
        creditClient = mock(CreditConsumptionClient.class);
        when(streamOutput.getCurrentStreamId()).thenReturn(STREAM_ID);
        when(creditClient.fetchBalance(anyString())).thenReturn(new java.math.BigDecimal("100"));
        when(stateService.setCancelKey(anyString())).thenReturn(Mono.just(true));
        when(stateService.updateState(anyString(), any())).thenReturn(Mono.just(true));
        when(stateService.delete(anyString())).thenReturn(Mono.just(1L));
        when(pubSubService.publishStopped(anyString(), anyString())).thenReturn(Mono.just(1L));

        redis = mock(StringRedisTemplate.class);
        claims = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(claims);
        // SETNX: the first claim of a key wins, every later one is refused.
        java.util.Set<String> heldClaims = new java.util.HashSet<>();
        when(claims.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenAnswer(inv -> heldClaims.add(inv.getArgument(0)));
        service = serviceOn(stateService);
    }

    /** The production wiring on the given state store: the real rescue service, only the stores faked. */
    private ConversationAgentService serviceOn(StreamStateService store) throws Exception {
        rescue = new StreamInterruptionService(store,
            pubSubService, historyService, streamService, mock(ConversationRepository.class), redis);

        ConversationAgentService built = new ConversationAgentService(
            contextBuilder,
            mock(AgentObservabilityClient.class),
            mock(AgentConfigProvider.class),
            creditClient,
            messageService,
            mock(PendingActionService.class),
            mock(ToolResultService.class),
            new ObjectMapper(),
            agentClient,
            store,
            mock(EventBus.class),
            mock(HelpSeenRegistry.class),
            mock(MessageRepository.class),
            "http://localhost:8087"
        );
        setField(built, "bridgeEnabled", Boolean.TRUE);
        setField(built, "bridgeClient", bridgeClient);
        setField(built, "bridgeAccessEnforcer", mock(BridgeAccessEnforcer.class));
        setField(built, "streamInterruptionService", rescue);
        return built;
    }

    // ─── Direct API (agent-service) ──────────────────────────────────────────

    @Test
    @DisplayName("regression: agent-service answer lost AFTER its loop COMPLETED - the reply is saved with the turn's execution and agent ids, then `done`")
    void lostAnswerOfACompletedRunSavesTheReplyThenDone() {
        stubContext("deepseek", "deepseek-chat");
        ArgumentCaptor<AgentExecutionRequestDto> dispatched = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        when(agentClient.executeAgent(dispatched.capture())).thenReturn(null);
        bufferedReply(StreamState.COMPLETED, "the whole answer");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        MessageDto saved = savedMessage();
        assertThat(saved.getRole()).isEqualTo("assistant");
        assertThat(saved.getContent()).isEqualTo("the whole answer");
        assertThat(saved.getModel()).isEqualTo("deepseek-chat");
        assertThat(saved.getAgentId()).isEqualTo("agent-9");
        assertThat(saved.getExecutionId()).isNotNull().isEqualTo(dispatched.getValue().executionId());
        InOrder order = inOrder(messageService, streamOutput);
        order.verify(messageService).addMessage(eq(CONV_ID), any(MessageDto.class));
        // Only once the message exists: the chat reloads the history on `done`.
        order.verify(streamOutput).sendDone(eq("the whole answer"), eq("deepseek-chat"), eq("deepseek"),
            eq("user-1"), eq(CONV_ID));
        verify(streamOutput, never()).sendError(anyString());
        // The producer finished: nothing to stop.
        verify(stateService, never()).setCancelKey(anyString());
    }

    @Test
    @DisplayName("regression: agent-service connection dropped while its loop still STREAMS - the partial is saved, the loop gets the cancel key, and the turn ends interrupted, never `done`")
    void lostAnswerOfAStillRunningLoopEndsInterruptedAndStopsTheProducer() {
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(null);
        bufferedReply(StreamState.STREAMING, "half an answer");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        assertThat(savedMessage().getContent()).isEqualTo("half an answer");
        verify(stateService).setCancelKey(STREAM_ID);
        verify(pubSubService).publishStopped(STREAM_ID, "half an answer");
        verify(stateService).updateState(STREAM_ID, StreamState.INTERRUPTED);
        verify(streamService).markStreamAsInterrupted(STREAM_ID, "Agent execution failed: no response from agent-service");
        verify(streamOutput, never()).sendDone(any(), any(), any(), any(), any());
        verify(streamOutput, never()).sendError(anyString());
    }

    @Test
    @DisplayName("agent-service answer lost after its loop FAILED (ERROR): the partial is kept, the turn still ends in `error`, never `done`")
    void lostAnswerOfAFailedRunKeepsThePartialAndTheError() {
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(null);
        bufferedReply(StreamState.ERROR, "partial of a failed run");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        assertThat(savedMessage().getContent()).isEqualTo("partial of a failed run");
        verify(streamOutput).sendError("Agent execution failed: no response from agent-service");
        verify(streamOutput, never()).sendDone(any(), any(), any(), any(), any());
        verify(pubSubService, never()).publishStopped(anyString(), anyString());
        verify(stateService).setCancelKey(STREAM_ID);
    }

    @Test
    @DisplayName("agent-service answer lost with NOTHING buffered: the error is sent as before and nothing is written")
    void lostAnswerWithNothingBufferedKeepsTheError() {
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(null);
        bufferedReply(StreamState.STREAMING, "");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(streamOutput).sendError("Agent execution failed: no response from agent-service");
        verify(streamOutput, never()).sendDone(any(), any(), any(), any(), any());
        verify(messageService, never()).addMessage(anyString(), any(MessageDto.class));
    }

    @Test
    @DisplayName("regression: the answer came back but writing it FAILED - the buffered reply is written instead, with the same attribution")
    void failedWriteFallsBackToTheBufferedReply() {
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(response("the whole answer"));
        when(messageService.addMessage(anyString(), any(MessageDto.class)))
            .thenThrow(new RuntimeException("connection pool exhausted"))
            .thenReturn(new MessageDto());
        bufferedReply(StreamState.COMPLETED, "the whole answer");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        ArgumentCaptor<MessageDto> writes = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService, times(2)).addMessage(eq(CONV_ID), writes.capture());
        MessageDto rescued = writes.getAllValues().get(1);
        assertThat(rescued.getContent()).isEqualTo("the whole answer");
        assertThat(rescued.getAgentId()).isEqualTo("agent-9");
        assertThat(rescued.getExecutionId()).isEqualTo(writes.getAllValues().get(0).getExecutionId());
        verify(streamOutput).sendDone(eq("the whole answer"), any(), any(), any(), eq(CONV_ID));
    }

    @Test
    @DisplayName("the answer came back and was written: the buffer is never read, the reply is never written twice")
    void writtenReplyIsNeverRescued() {
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(response("the whole answer"));
        when(messageService.addMessage(anyString(), any(MessageDto.class))).thenReturn(new MessageDto());

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(messageService, times(1)).addMessage(eq(CONV_ID), any(MessageDto.class));
        verify(stateService, never()).getFullContent(anyString());
    }

    @Test
    @DisplayName("regression: Stop saved the partial, then the stopped loop's finalize marked the stream COMPLETED and its answer was lost - the rescue is refused by Stop's claim, never a second copy")
    void stopThenLostAnswerNeverSavesThePartialTwice() {
        stubContext("deepseek", "deepseek-chat");
        StreamStopHandler stopHandler = new StreamStopHandler(stateService, pubSubService, historyService, agentClient);
        org.springframework.test.util.ReflectionTestUtils.setField(stopHandler, "streamInterruptionService", rescue);
        when(stateService.getByConversationId(CONV_ID)).thenReturn(Mono.just(new StreamMetadata(STREAM_ID, "user-1",
            CONV_ID, "m", "p", StreamState.STREAMING, Instant.now(), Instant.now(), 7)));
        when(stateService.stop(STREAM_ID)).thenReturn(Mono.just(true));
        when(historyService.addMessage(any(), any(), any(), any(), any(), any(), any())).thenReturn(Map.of("id", "m1"));
        // What the stream looks like once the stopped loop has finalized: COMPLETED, not STOPPED_BY_USER.
        bufferedReply(StreamState.COMPLETED, "partial");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(null);

        stopHandler.stopStream("user-1", CONV_ID);
        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(historyService, times(1)).addMessage(eq(CONV_ID), eq("assistant"), eq("partial"), any(), any(), any(), any());
        verify(messageService, never()).addMessage(anyString(), any(MessageDto.class));
        verify(streamOutput).sendError("Agent execution failed: no response from agent-service");
    }

    // ─── The real order of the cancel-key write (a store that deletes like Redis) ─

    @Test
    @DisplayName("regression: agent-service loop still STREAMING - after the interrupted ending the cancel key is STILL THERE for the loop's poller, with the Stop path's TTL")
    void cancelKeySurvivesTheInterruptedEndingForTheLoop() throws Exception {
        InMemoryStreamStateService store = new InMemoryStreamStateService();
        store.seed(STREAM_ID, CONV_ID, StreamState.STREAMING, "half an answer");
        ConversationAgentService onStore = serviceOn(store);
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(null);

        onStore.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        assertThat(savedMessage().getContent()).isEqualTo("half an answer");
        assertThat(store.hasStream(STREAM_ID)).as("the interrupted ending freed the stream keys").isFalse();
        assertThat(store.hasCancelKey(STREAM_ID)).isTrue();
        assertThat(store.cancelKeyTtl(STREAM_ID)).isEqualTo(InMemoryStreamStateService.STOP_PATH_CANCEL_TTL);
    }

    @Test
    @DisplayName("regression: bridge restarted mid-reply - the CLI's poller finds the cancel key after the interrupted ending")
    void cancelKeySurvivesTheInterruptedEndingForTheCli() throws Exception {
        InMemoryStreamStateService store = new InMemoryStreamStateService();
        store.seed(STREAM_ID, CONV_ID, StreamState.STREAMING, "streamed by the CLI");
        ConversationAgentService onStore = serviceOn(store);
        stubContext("claude-code", "claude-opus-4-6");
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any())).thenReturn(null);

        onStore.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        assertThat(store.hasCancelKey(STREAM_ID)).isTrue();
        assertThat(store.cancelKeyTtl(STREAM_ID)).isEqualTo(InMemoryStreamStateService.STOP_PATH_CANCEL_TTL);
    }

    @Test
    @DisplayName("regression: ERROR ending - the cancel key is written after the error finalize, whose cleanup would cut a key written first to 30 s")
    void cancelKeyKeepsTheStopTtlAfterTheErrorEnding() throws Exception {
        InMemoryStreamStateService store = new InMemoryStreamStateService();
        store.seed(STREAM_ID, CONV_ID, StreamState.ERROR, "partial of a failed run");
        ConversationAgentService onStore = serviceOn(store);
        stubContext("deepseek", "deepseek-chat");
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(null);
        // The real output, so its error finalize runs its real cleanup on the store.
        StreamingOutput realOutput = new com.apimarketplace.conversation.streaming.RedisStreamingOutput(
            STREAM_ID, store, pubSubService, CONV_ID, "deepseek-chat", "deepseek");

        onStore.executeStreaming(chatRequest(), realOutput, CONV_ID);

        assertThat(store.state(STREAM_ID)).isEqualTo(StreamState.ERROR);
        assertThat(store.cancelKeyTtl(STREAM_ID)).isEqualTo(InMemoryStreamStateService.STOP_PATH_CANCEL_TTL);
    }

    // ─── Queue transport (what cloud chat uses to reach agent-service) ────────

    @Test
    @DisplayName("regression (queue): the result await THROWS after the loop COMPLETED - the reply is saved, then `done`; no [Error] row, no error event")
    void queueAwaitThrowsAfterACompletedRunSavesTheReplyThenDone() throws Exception {
        stubContext("deepseek", "deepseek-chat");
        useQueueTransport();
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenThrow(new RedisResultWaiter.AgentResultTimeoutException("corr-1", Duration.ofMinutes(130), null));
        bufferedReply(StreamState.COMPLETED, "the whole answer");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(queueProducer).enqueue(any());
        verify(agentClient, never()).executeAgent(any());
        MessageDto saved = savedMessage();
        assertThat(saved.getContent()).isEqualTo("the whole answer");
        assertThat(saved.getAgentId()).isEqualTo("agent-9");
        assertThat(saved.getExecutionId()).isNotNull();
        verify(streamOutput).sendDone(eq("the whole answer"), eq("deepseek-chat"), eq("deepseek"), eq("user-1"), eq(CONV_ID));
        verify(streamOutput, never()).sendError(anyString());
        // The loop COMPLETED: there is nothing left to stop.
        verify(stateService, never()).setCancelKey(anyString());
    }

    @Test
    @DisplayName("regression (queue): the result await THROWS while the loop still STREAMS - the partial is saved, the cancel key is set, the turn ends interrupted")
    void queueAwaitThrowsWhileTheLoopStreamsEndsInterrupted() throws Exception {
        stubContext("deepseek", "deepseek-chat");
        useQueueTransport();
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenThrow(new RuntimeException("Failed waiting for agent result: correlationId=corr-1"));
        bufferedReply(StreamState.STREAMING, "half an answer");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        assertThat(savedMessage().getContent()).isEqualTo("half an answer");
        verify(stateService).setCancelKey(STREAM_ID);
        verify(pubSubService).publishStopped(STREAM_ID, "half an answer");
        verify(stateService).updateState(STREAM_ID, StreamState.INTERRUPTED);
        verify(streamOutput, never()).sendDone(any(), any(), any(), any(), any());
        verify(streamOutput, never()).sendError(anyString());
    }

    @Test
    @DisplayName("queue await throws with NOTHING buffered: the old error path is kept (cancel key, error event, [Error] row)")
    void queueAwaitThrowsWithNothingBufferedKeepsTheErrorPath() throws Exception {
        stubContext("deepseek", "deepseek-chat");
        useQueueTransport();
        when(streamOutput.isStreamProcessing()).thenReturn(true);
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenThrow(new RuntimeException("redis down"));
        bufferedReply(StreamState.STREAMING, "");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(stateService).setCancelKey(STREAM_ID);
        verify(streamOutput).sendError("Agent execution error: redis down");
        assertThat(savedMessage().getContent()).isEqualTo("[Error] redis down");
        verify(pubSubService, never()).publishStopped(anyString(), anyString());
    }

    @Test
    @DisplayName("regression (queue): the worker's failure envelope carries NO content while the loop streamed a partial - the partial is saved and the turn ends in `error`, not an empty `done`")
    void queueFailureEnvelopeWithoutContentRescuesThePartial() throws Exception {
        stubContext("deepseek", "deepseek-chat");
        useQueueTransport();
        // Exactly what AgentQueueWorkerService.publishError sends: {"success":false,"error":...}
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenReturn(failureEnvelope("provider exploded mid-stream"));
        bufferedReply(StreamState.ERROR, "partial the user watched");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        assertThat(savedMessage().getContent()).isEqualTo("partial the user watched");
        verify(streamOutput).sendError("provider exploded mid-stream");
        verify(streamOutput, never()).sendDone(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failure with no content and NOTHING buffered ends exactly as before: nothing written, an empty `done`")
    void failureWithoutContentAndNothingBufferedIsUnchanged() throws Exception {
        stubContext("deepseek", "deepseek-chat");
        useQueueTransport();
        when(resultWaiter.await(anyString(), eq(AgentExecutionResponseDto.class), any(Duration.class)))
            .thenReturn(failureEnvelope("insufficient credits"));
        bufferedReply(StreamState.ERROR, "");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(messageService, never()).addMessage(anyString(), any(MessageDto.class));
        verify(streamOutput).sendDone(eq(""), any(), any(), any(), eq(CONV_ID));
        verify(streamOutput, never()).sendError(anyString());
    }

    // ─── CLI bridge (direct from conversation-service) ───────────────────────

    @Test
    @DisplayName("regression: bridge restarted mid-reply (stream still STREAMING) - the partial is saved, the CLI gets the cancel key, and the turn ends interrupted, never `done`")
    void lostBridgeAnswerMidReplyEndsInterrupted() {
        stubContext("claude-code", "claude-opus-4-6");
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any())).thenReturn(null);
        bufferedReply(StreamState.STREAMING, "streamed by the CLI");

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        MessageDto saved = savedMessage();
        assertThat(saved.getContent()).isEqualTo("streamed by the CLI");
        assertThat(saved.getModel()).isEqualTo("claude-opus-4-6");
        InOrder order = inOrder(messageService, stateService, pubSubService);
        order.verify(messageService).addMessage(eq(CONV_ID), any(MessageDto.class));
        order.verify(pubSubService).publishStopped(STREAM_ID, "streamed by the CLI");
        // The cancel key comes after the key cleanup, which would otherwise delete it.
        order.verify(stateService).delete(STREAM_ID);
        order.verify(stateService).setCancelKey(STREAM_ID);
        verify(streamOutput, never()).sendDone(any(), any(), any(), any(), any());
        verify(streamOutput, never()).sendError(anyString());
        verify(pubSubService, never()).publishComplete(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("bridge unreachable before anything streamed: the error is sent as before")
    void unreachableBridgeKeepsTheError() {
        stubContext("claude-code", "claude-opus-4-6");
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any())).thenReturn(null);
        when(stateService.getMetadata(STREAM_ID)).thenReturn(Mono.empty());
        when(stateService.getFullContent(STREAM_ID)).thenReturn(Mono.empty());

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(streamOutput).sendError("Agent execution failed: no response from bridge");
        verify(messageService, never()).addMessage(anyString(), any(MessageDto.class));
        verify(stateService, never()).setCancelKey(anyString());
    }

    @Test
    @DisplayName("a concurrent drain / TTL rescue already holds the stream: nothing is saved twice, the error stays")
    void concurrentRescuerWinsAndNothingIsSavedTwice() {
        stubContext("claude-code", "claude-opus-4-6");
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class), any())).thenReturn(null);
        bufferedReply(StreamState.STREAMING, "streamed by the CLI");
        when(claims.setIfAbsent(eq("stream:interrupt:claim:" + STREAM_ID), anyString(), any(Duration.class)))
            .thenReturn(false);

        service.executeStreaming(chatRequest(), streamOutput, CONV_ID);

        verify(messageService, never()).addMessage(anyString(), any(MessageDto.class));
        verify(streamOutput).sendError("Agent execution failed: no response from bridge");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void useQueueTransport() throws Exception {
        setField(service, "queueProducer", queueProducer);
        setField(service, "resultWaiter", resultWaiter);
    }

    /** What the queue worker publishes when the run throws: {@code {"success":false,"error":...}}, nothing else. */
    private static AgentExecutionResponseDto failureEnvelope(String error) {
        return new AgentExecutionResponseDto(false, null, null, null, 0, null, error, 0L, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    private MessageDto savedMessage() {
        ArgumentCaptor<MessageDto> written = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageService).addMessage(eq(CONV_ID), written.capture());
        List<MessageDto> all = written.getAllValues();
        return all.get(all.size() - 1);
    }

    private void bufferedReply(StreamState state, String content) {
        when(stateService.getMetadata(STREAM_ID)).thenReturn(Mono.just(new StreamMetadata(STREAM_ID, "user-1",
            CONV_ID, "m", "p", state, Instant.now(), Instant.now(), content.length())));
        when(stateService.getFullContent(STREAM_ID)).thenReturn(Mono.just(content));
    }

    private void stubContext(String provider, String model) {
        AgentLoopContext context = AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .tenantId("user-1")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setAgentId("agent-9");
        request.setMessage("hello");
        return request;
    }

    private AgentExecutionResponseDto response(String content) {
        return new AgentExecutionResponseDto(
            true, content, content, Collections.emptyList(), 1,
            Map.of("promptTokens", 1, "completionTokens", 1, "totalTokens", 2),
            null, 5L, "deepseek", "deepseek-chat",
            Collections.emptyList(), "COMPLETED",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
