package com.apimarketplace.agent.service.execution;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A card raised by the approval gate is the only thing standing between the user and a call
 * the agent is holding. It therefore has to reach three places, not two: the live SSE
 * channel, the live WS channel, and the stream's replay buffer - because a held call can
 * outlive the page it was shown on.
 */
@DisplayName("ApprovalCardPublisher - where an approval card has to land")
class ApprovalCardPublisherTest {

    private static final String STREAM_ID = "stream-1";
    private static final String CONVERSATION_ID = "conv-1";

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOps;
    private EventBus eventBus;
    private ApprovalCardPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        eventBus = mock(EventBus.class);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
        publisher = new ApprovalCardPublisher(redisTemplate, eventBus, new ObjectMapper());
    }

    private static Map<String, Object> authorizationMetadata() {
        return Map.of("rule", "workflow:execute", "toolName", "workflow",
                "action", "execute", "toolCallId", "call-9");
    }

    @Test
    @DisplayName("An authorization card is buffered so it survives a page reload")
    void authorizationCardIsBuffered() {
        publisher.publishToolAuthorization(STREAM_ID, CONVERSATION_ID, authorizationMetadata(), true, "call-9");

        ArgumentCaptor<String> buffered = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq(StreamRedisKeys.toolsKey(STREAM_ID)), buffered.capture());
        // The held call is identified in the buffered copy too - a replay that lost the gate
        // key would put back a card that can no longer release anything.
        assertThat(buffered.getValue()).contains("workflow:execute").contains("call-9")
                .contains("\"blocking\":true");
        verify(redisTemplate).expire(StreamRedisKeys.toolsKey(STREAM_ID), StreamRedisKeys.STREAM_TTL);
    }

    @Test
    @DisplayName("A connect-a-service card is buffered too")
    void serviceApprovalCardIsBuffered() {
        publisher.publishServiceApproval(STREAM_ID, CONVERSATION_ID,
                List.of(Map.of("serviceType", "gmail", "serviceName", "Gmail")),
                "Connect Gmail", false, true, "call-7");

        ArgumentCaptor<String> buffered = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq(StreamRedisKeys.toolsKey(STREAM_ID)), buffered.capture());
        assertThat(buffered.getValue()).contains("gmail").contains("call-7");
    }

    @Test
    @DisplayName("The buffer is trimmed, so a long turn cannot grow it without bound")
    void bufferIsTrimmed() {
        publisher.publishToolAuthorization(STREAM_ID, CONVERSATION_ID, authorizationMetadata(), true, "call-9");

        verify(listOps).trim(StreamRedisKeys.toolsKey(STREAM_ID), -StreamRedisKeys.MAX_TOOL_EVENTS, -1);
    }

    @Test
    @DisplayName("A NON-blocking card is not buffered - it has nothing to survive a reload for")
    void nonBlockingCardIsNotBuffered() {
        // Its turn ends immediately and conversation-service writes its row a moment later,
        // which is how it survived before. Buffering it would put a card the user already
        // dealt with (an install they completed) back on screen, with nothing to remove it:
        // only a PARKED card is ever taken back out.
        publisher.publishToolAuthorization(STREAM_ID, CONVERSATION_ID, authorizationMetadata(), false, null);
        publisher.publishServiceApproval(STREAM_ID, CONVERSATION_ID,
                List.of(Map.of("serviceType", "gmail")), "Connect Gmail", false, false, null);

        verify(listOps, org.mockito.Mockito.never()).rightPush(anyString(), anyString());
        // The live channels still carry it - only the replay copy is skipped.
        verify(redisTemplate, org.mockito.Mockito.times(2)).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("Un-buffering removes exactly the card that was published, and nothing else")
    void unbufferRemovesThePublishedCard() {
        String buffered = publisher.publishToolAuthorization(
                STREAM_ID, CONVERSATION_ID, authorizationMetadata(), true, "call-9");

        publisher.unbuffer(STREAM_ID, buffered);

        // count=1, not 0: an identical card could legitimately have been raised twice in a
        // turn, and settling one must not silently drop the other.
        verify(listOps).remove(StreamRedisKeys.toolsKey(STREAM_ID), 1, buffered);
    }

    @Test
    @DisplayName("Un-buffering nothing is a no-op, not a stray Redis call")
    void unbufferIgnoresAnAbsentCard() {
        publisher.unbuffer(STREAM_ID, null);
        publisher.unbuffer(STREAM_ID, "   ");
        publisher.unbuffer(null, "{\"card\":1}");
        publisher.unbuffer("  ", "{\"card\":1}");

        verify(listOps, org.mockito.Mockito.never()).remove(anyString(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("A failed un-buffer is swallowed - the card is already answered either way")
    void unbufferFailureIsSwallowed() {
        // The user has dealt with the card; failing their click over a stale replay copy
        // would turn a cosmetic problem into a broken action.
        org.mockito.Mockito.when(listOps.remove(anyString(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        publisher.unbuffer(STREAM_ID, "{\"card\":1}");
    }

    @Test
    @DisplayName("A buffer failure never costs the live card")
    void bufferFailureDoesNotStopTheLiveCard() {
        // Anyone currently watching already has the card; failing the whole publish over the
        // replay copy would take it away from them too.
        doThrow(new IllegalStateException("redis down"))
                .when(listOps).rightPush(anyString(), anyString());

        publisher.publishToolAuthorization(STREAM_ID, CONVERSATION_ID, authorizationMetadata(), true, "call-9");

        verify(redisTemplate).convertAndSend(anyString(), anyString());
        verify(eventBus).publish(eq("ws:conversation:" + CONVERSATION_ID), anyString());
    }

    @Test
    @DisplayName("With no stream there is nowhere to publish and nothing is buffered")
    void noStreamPublishesNothing() {
        publisher.publishToolAuthorization(null, CONVERSATION_ID, authorizationMetadata(), true, "call-9");
        publisher.publishToolAuthorization("  ", CONVERSATION_ID, authorizationMetadata(), true, "call-9");

        verify(listOps, org.mockito.Mockito.never()).rightPush(anyString(), anyString());
        verify(redisTemplate, org.mockito.Mockito.never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("A non-blocking card carries no hold and no key")
    void nonBlockingCardClaimsNoHold() {
        publisher.publishServiceApproval(STREAM_ID, CONVERSATION_ID,
                List.of(Map.of("serviceType", "gmail")), "Connect Gmail", false, false, null);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(anyString(), sent.capture());
        // Claiming a hold that does not exist makes the frontend skip its resume message,
        // and the user's answer would then do nothing at all.
        assertThat(sent.getValue()).doesNotContain("blocking").doesNotContain("gateKey");
    }

    @Test
    @DisplayName("The TTL keeps the buffered card exactly as long as the stream itself")
    void bufferedCardExpiresWithTheStream() {
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        publisher.publishToolAuthorization(STREAM_ID, CONVERSATION_ID, authorizationMetadata(), true, "call-9");

        verify(redisTemplate).expire(StreamRedisKeys.toolsKey(STREAM_ID), StreamRedisKeys.STREAM_TTL);
    }

    @Test
    @DisplayName("The card carries its SUBJECT, so the user is told which workflow goes live")
    void authorizationCardCarriesTheSubject() {
        // Without this the card reaches the screen saying "run this action?" about a pin,
        // which is a question nobody can answer. The publisher relays the subject opaquely,
        // so this also pins that a rule it has never heard of needs no change here.
        Map<String, Object> metadata = Map.of("rule", "workflow:pin", "toolName", "workflow",
                "action", "pin", "toolCallId", "call-11",
                "subject", Map.of("kind", "workflow", "id", "w-1", "version", 12));

        publisher.publishToolAuthorization(STREAM_ID, CONVERSATION_ID, metadata, true, "call-11");

        ArgumentCaptor<String> buffered = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq(StreamRedisKeys.toolsKey(STREAM_ID)), buffered.capture());
        assertThat(buffered.getValue())
                .contains("\"subject\"").contains("\"id\":\"w-1\"").contains("\"version\":12");
    }

    @Test
    @DisplayName("A rule with no subject OMITS the key, which is the shape the bridge also sends")
    void aCardWithoutASubjectOmitsTheKey() {
        // Every rule that shipped before the subject existed goes through this same hop, and
        // both routes have to put the same thing on the wire: the bridge omits the key
        // (asserted in redisPublisherToolAuthorization.test.mjs) and so does this one. The
        // frontend type declares `subject?:`, i.e. undefined, and tsconfig has strict:false -
        // a null arriving from one route only would not be caught where it is read.
        publisher.publishToolAuthorization(STREAM_ID, CONVERSATION_ID, authorizationMetadata(), true, "call-9");

        ArgumentCaptor<String> buffered = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq(StreamRedisKeys.toolsKey(STREAM_ID)), buffered.capture());
        assertThat(buffered.getValue()).contains("workflow:execute").doesNotContain("subject");
    }
}
