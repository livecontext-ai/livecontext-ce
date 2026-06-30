package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.config.StreamConfig;
import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.StreamRepository;
import com.apimarketplace.conversation.streaming.StreamInterruptionService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("StreamTTLService")
@ExtendWith(MockitoExtension.class)
class StreamTTLServiceTest {

    @Mock
    private StreamRepository streamRepository;

    @Mock
    private StreamStateService stateService;

    @Mock
    private StreamInterruptionService streamInterruptionService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private StreamConfig streamConfig;

    @InjectMocks
    private StreamTTLService streamTTLService;

    private static StreamMetadata metadata(String streamId, String provider) {
        return new StreamMetadata(streamId, "internal", "conv-1", "gpt-4", provider,
                StreamState.STREAMING, Instant.now(), Instant.now(), 0);
    }

    @Nested
    @DisplayName("processTimeoutStreams")
    class ProcessTimeoutStreams {

        @Test
        @DisplayName("should return 0 when no expired streams found")
        void shouldReturnZeroWhenNoExpiredStreams() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);
            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList());

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isZero();
            verify(streamRepository, never()).save(any());
        }

        @Test
        @DisplayName("should rescue partial content via interrupt() BEFORE any Redis deletion for an expired stream")
        void shouldRescueViaInterruptBeforeDeletion() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);

            Stream expiredStream = new Stream();
            expiredStream.setStreamId("stream-1");
            expiredStream.setStatus(Stream.StreamStatus.ACTIVE);
            expiredStream.setUpdatedAt(LocalDateTime.now().minusMinutes(60));

            // Same list for the timeout pass and the heartbeat-grace pass - the second pass
            // must skip the already-handled stream.
            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredStream));
            when(streamInterruptionService.interrupt(eq("stream-1"), anyString())).thenReturn(true);
            when(streamRepository.save(any(Stream.class))).thenReturn(expiredStream);

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isEqualTo(1);
            verify(streamInterruptionService).interrupt("stream-1", "Stream timeout after 30 minutes of inactivity");
            // interrupt() already handled error+delete in Redis - the legacy block must NOT run
            verify(stateService, never()).error(anyString(), anyString());
            verify(stateService, never()).delete(anyString());
            // DB row ends up INTERRUPTED, not ERROR
            assertThat(expiredStream.getStatus()).isEqualTo(Stream.StreamStatus.INTERRUPTED);
            verify(streamRepository).save(expiredStream);
        }

        @Test
        @DisplayName("M1: should skip an absolutely-expired stream whose heartbeat is still alive (long-running execution)")
        void shouldSkipAbsoluteTimeoutWhenHeartbeatAlive() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);

            Stream expiredStream = new Stream();
            expiredStream.setStreamId("stream-1");
            expiredStream.setStatus(Stream.StreamStatus.ACTIVE);
            expiredStream.setUpdatedAt(LocalDateTime.now().minusMinutes(65));

            // Same list for the timeout pass AND the heartbeat pass
            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredStream));
            // Producer is still beating: a 65-minute agent execution is legitimate
            when(stringRedisTemplate.hasKey("stream:hb:stream-1")).thenReturn(true);
            // Heartbeat pass sees it too - alive heartbeat must keep it running there as well
            when(stateService.getMetadata("stream-1")).thenReturn(Mono.just(metadata("stream-1", "remote-agent")));

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isZero();
            verify(streamInterruptionService, never()).interrupt(anyString(), anyString());
            verify(stateService, never()).error(anyString(), anyString());
            verify(stateService, never()).delete(anyString());
            verify(streamRepository, never()).save(any());
            assertThat(expiredStream.getStatus()).isEqualTo(Stream.StreamStatus.ACTIVE);
        }

        @Test
        @DisplayName("should fall back to legacy ERROR+delete when interrupt() finds nothing to rescue")
        void shouldFallBackToLegacyTimeoutWhenInterruptReturnsFalse() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);

            Stream expiredStream = new Stream();
            expiredStream.setStreamId("stream-1");
            expiredStream.setStatus(Stream.StreamStatus.ACTIVE);
            expiredStream.setUpdatedAt(LocalDateTime.now().minusMinutes(60));

            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredStream));
            when(streamInterruptionService.interrupt(eq("stream-1"), anyString())).thenReturn(false);
            when(streamRepository.save(any(Stream.class))).thenReturn(expiredStream);
            when(stateService.error(anyString(), anyString())).thenReturn(Mono.empty());
            when(stateService.delete(anyString())).thenReturn(Mono.empty());

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isEqualTo(1);
            assertThat(expiredStream.getStatus()).isEqualTo(Stream.StreamStatus.ERROR);
            assertThat(expiredStream.getErrorMessage()).contains("Stream timeout after 30 minutes");
            assertThat(expiredStream.getCompletedAt()).isNotNull();
            verify(streamRepository).save(expiredStream);

            // The rescue attempt must come BEFORE the destructive Redis error+delete
            InOrder inOrder = inOrder(streamInterruptionService, stateService);
            inOrder.verify(streamInterruptionService).interrupt(eq("stream-1"), anyString());
            inOrder.verify(stateService).error(eq("stream-1"), anyString());
        }

        @Test
        @DisplayName("should fall back to legacy ERROR+delete when interrupt() throws")
        void shouldFallBackWhenInterruptThrows() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);

            Stream expiredStream = new Stream();
            expiredStream.setStreamId("stream-1");
            expiredStream.setStatus(Stream.StreamStatus.ACTIVE);

            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredStream));
            when(streamInterruptionService.interrupt(eq("stream-1"), anyString()))
                    .thenThrow(new RuntimeException("Redis down"));
            when(streamRepository.save(any(Stream.class))).thenReturn(expiredStream);
            when(stateService.error(anyString(), anyString())).thenReturn(Mono.empty());
            when(stateService.delete(anyString())).thenReturn(Mono.empty());

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isEqualTo(1);
            assertThat(expiredStream.getStatus()).isEqualTo(Stream.StreamStatus.ERROR);
        }

        @Test
        @DisplayName("should continue processing when one stream fails")
        void shouldContinueWhenOneStreamFails() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);

            Stream failingStream = new Stream();
            failingStream.setId("id-fail");
            failingStream.setStreamId("stream-fail");
            failingStream.setStatus(Stream.StreamStatus.ACTIVE);

            Stream okStream = new Stream();
            okStream.setId("id-ok");
            okStream.setStreamId("stream-ok");
            okStream.setStatus(Stream.StreamStatus.ACTIVE);

            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(List.of(failingStream, okStream));
            when(streamRepository.save(failingStream)).thenThrow(new RuntimeException("DB error"));
            when(streamRepository.save(okStream)).thenReturn(okStream);
            when(stateService.error(eq("stream-ok"), anyString())).thenReturn(Mono.empty());
            when(stateService.delete("stream-ok")).thenReturn(Mono.empty());
            // Heartbeat pass re-sees the failing stream - give it no Redis trace so it is skipped
            when(stateService.getMetadata("stream-fail")).thenReturn(Mono.empty());

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("heartbeat-loss detection")
    class HeartbeatDetection {

        private Stream staleAgentStream() {
            Stream stream = new Stream();
            stream.setStreamId("stream-hb");
            stream.setStatus(Stream.StreamStatus.ACTIVE);
            stream.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
            return stream;
        }

        @Test
        @DisplayName("should interrupt an agent stream past the grace period whose heartbeat key is gone")
        void shouldInterruptWhenHeartbeatLost() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);
            Stream stream = staleAgentStream();

            // First call = 30-min timeout pass (nothing expired), second call = 2-min grace pass
            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList())
                    .thenReturn(List.of(stream));
            when(stateService.getMetadata("stream-hb")).thenReturn(Mono.just(metadata("stream-hb", "remote-agent")));
            when(stringRedisTemplate.hasKey("stream:hb:stream-hb")).thenReturn(false);
            when(streamInterruptionService.interrupt("stream-hb", "Agent heartbeat lost")).thenReturn(true);
            when(streamRepository.save(any(Stream.class))).thenReturn(stream);

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isEqualTo(1);
            verify(streamInterruptionService).interrupt("stream-hb", "Agent heartbeat lost");
            assertThat(stream.getStatus()).isEqualTo(Stream.StreamStatus.INTERRUPTED);
        }

        @Test
        @DisplayName("should leave an agent stream alive when its heartbeat key is present (long tool-call)")
        void shouldNotInterruptWhenHeartbeatPresent() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);
            Stream stream = staleAgentStream();

            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList())
                    .thenReturn(List.of(stream));
            when(stateService.getMetadata("stream-hb")).thenReturn(Mono.just(metadata("stream-hb", "remote-agent")));
            when(stringRedisTemplate.hasKey("stream:hb:stream-hb")).thenReturn(true);

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isZero();
            verify(streamInterruptionService, never()).interrupt(anyString(), anyString());
            verify(streamRepository, never()).save(any());
        }

        @Test
        @DisplayName("B1: should NOT interrupt a 'workflow' stream without heartbeat - orchestrator does not emit heartbeats (yet)")
        void shouldNotApplyHeartbeatDetectionToWorkflowStreams() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);
            Stream stream = staleAgentStream();

            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList())
                    .thenReturn(List.of(stream));
            // Orchestrator registers streams with provider "workflow" but never writes
            // stream:hb:{id} - the heartbeat pass must leave them alone (absolute timeout only).
            when(stateService.getMetadata("stream-hb")).thenReturn(Mono.just(metadata("stream-hb", "workflow")));

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isZero();
            verify(stringRedisTemplate, never()).hasKey(anyString());
            verify(streamInterruptionService, never()).interrupt(anyString(), anyString());
            verify(streamRepository, never()).save(any());
        }

        @Test
        @DisplayName("should NOT apply heartbeat detection to direct chat streams (real LLM provider, no heartbeat by design)")
        void shouldSkipChatStreams() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);
            Stream stream = staleAgentStream();

            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList())
                    .thenReturn(List.of(stream));
            // ChatStreamInitializer stores the real LLM provider, not "workflow"/"remote-agent"
            when(stateService.getMetadata("stream-hb")).thenReturn(Mono.just(metadata("stream-hb", "openai")));

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isZero();
            verify(stringRedisTemplate, never()).hasKey(anyString());
            verify(streamInterruptionService, never()).interrupt(anyString(), anyString());
        }

        @Test
        @DisplayName("should skip heartbeat check when the stream has no Redis trace (left to the absolute timeout)")
        void shouldSkipWhenNoRedisTrace() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);
            Stream stream = staleAgentStream();

            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList())
                    .thenReturn(List.of(stream));
            when(stateService.getMetadata("stream-hb")).thenReturn(Mono.empty());

            int result = streamTTLService.processTimeoutStreams();

            assertThat(result).isZero();
            verify(stringRedisTemplate, never()).hasKey(anyString());
            verify(streamInterruptionService, never()).interrupt(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("processExpiredStreams")
    class ProcessExpiredStreams {

        @Test
        @DisplayName("should catch exceptions and not throw")
        void shouldCatchExceptions() {
            when(streamConfig.getTimeoutMinutes()).thenReturn(30);
            when(streamRepository.findActiveStreamsOlderThan(any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("DB unavailable"));

            // Should not throw
            streamTTLService.processExpiredStreams();
        }
    }

}
