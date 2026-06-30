package com.apimarketplace.agent.client.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisResultWaiter - sync-await pattern for queue results")
class RedisResultWaiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    private ObjectMapper objectMapper;
    private RedisResultWaiter waiter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        waiter = new RedisResultWaiter(redisTemplate, listenerContainer, objectMapper);
    }

    @Test
    @DisplayName("GETDEL pre-check picks up result already published before subscribe")
    void preCheckCatchesAlreadyPublishedResult() {
        String correlationId = "corr-pre";
        String json = "{\"success\":true,\"value\":42}";

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete("agent:result:" + correlationId)).thenReturn(json);

        DummyResponse response = waiter.await(correlationId, DummyResponse.class, Duration.ofSeconds(2));

        assertThat(response.success).isTrue();
        assertThat(response.value).isEqualTo(42);
        // Listener registered+removed regardless of which path completes the future
        verify(listenerContainer).addMessageListener(any(MessageListener.class), any(Topic.class));
        verify(listenerContainer).removeMessageListener(any(MessageListener.class), any(Topic.class));
    }

    @Test
    @DisplayName("Pub/sub message delivers result after empty GETDEL pre-check")
    void pubSubDeliversResultWhenKeyMissing() {
        String correlationId = "corr-pubsub";
        String json = "{\"success\":true,\"value\":7}";

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete("agent:result:" + correlationId)).thenReturn(null);

        // Simulate the worker firing a pub/sub message asynchronously right after
        // the listener registers. We invoke onMessage on a background thread.
        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            new Thread(() -> {
                // small delay so await() actually blocks
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                Message msg = new TestMessage(
                    ("agent:result:channel:" + correlationId).getBytes(StandardCharsets.UTF_8),
                    json.getBytes(StandardCharsets.UTF_8));
                listener.onMessage(msg, null);
            }).start();
            return null;
        }).when(listenerContainer).addMessageListener(any(MessageListener.class), any(Topic.class));

        DummyResponse response = waiter.await(correlationId, DummyResponse.class, Duration.ofSeconds(5));

        assertThat(response.success).isTrue();
        assertThat(response.value).isEqualTo(7);
        verify(listenerContainer).removeMessageListener(any(MessageListener.class), any(Topic.class));
    }

    @Test
    @DisplayName("Timeout throws AgentResultTimeoutException with correlationId surfaced")
    void timeoutExpiresAndCleansUp() {
        String correlationId = "corr-timeout";

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete("agent:result:" + correlationId)).thenReturn(null);
        // No pub/sub fires → future never completes

        assertThatThrownBy(() ->
                waiter.await(correlationId, DummyResponse.class, Duration.ofMillis(150)))
                .isInstanceOf(RedisResultWaiter.AgentResultTimeoutException.class)
                .hasMessageContaining(correlationId)
                .satisfies(ex -> {
                    RedisResultWaiter.AgentResultTimeoutException tex =
                            (RedisResultWaiter.AgentResultTimeoutException) ex;
                    assertThat(tex.getCorrelationId()).isEqualTo(correlationId);
                    assertThat(tex.getTimeout()).isEqualTo(Duration.ofMillis(150));
                });
        // Listener still gets cleaned up after timeout (finally block)
        verify(listenerContainer).removeMessageListener(any(MessageListener.class), any(Topic.class));
    }

    @Test
    @DisplayName("Rejects null/blank correlationId")
    void rejectsBadInputs() {
        assertThatThrownBy(() -> waiter.await(null, DummyResponse.class, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waiter.await("", DummyResponse.class, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waiter.await("c", null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waiter.await("c", DummyResponse.class, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> waiter.await("c", DummyResponse.class, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Subscribes to the channel keyed by correlationId")
    void subscribesToCorrectChannel() {
        String correlationId = "corr-channel";

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getAndDelete("agent:result:" + correlationId)).thenReturn("{\"success\":true}");

        waiter.await(correlationId, DummyResponse.class, Duration.ofSeconds(2));

        ArgumentCaptor<Topic> topicCaptor = ArgumentCaptor.forClass(Topic.class);
        verify(listenerContainer).addMessageListener(any(MessageListener.class), topicCaptor.capture());
        assertThat(topicCaptor.getValue().getTopic()).isEqualTo("agent:result:channel:" + correlationId);
    }

    // ─── Test helpers ────────────────────────────────────────────────────────

    /** Minimal POJO for JSON deserialization. */
    static class DummyResponse {
        public boolean success;
        public int value;
    }

    /** Minimal {@link Message} implementation for pub/sub simulation. */
    static class TestMessage implements Message {
        private final byte[] channel;
        private final byte[] body;

        TestMessage(byte[] channel, byte[] body) {
            this.channel = channel;
            this.body = body;
        }

        @Override public byte[] getBody() { return body; }
        @Override public byte[] getChannel() { return channel; }
    }
}
