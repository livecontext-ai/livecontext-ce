package com.apimarketplace.monolith.ws;

import com.apimarketplace.auth.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MonolithWsHandler")
class MonolithWsHandlerTest {

    private JwtTokenProvider jwtTokenProvider;
    private RedisMessageListenerContainer listenerContainer;
    private MonolithWsActionHandler actionHandler;
    private MonolithChannelAuthorizer channelAuthorizer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        listenerContainer = mock(RedisMessageListenerContainer.class);
        actionHandler = mock(MonolithWsActionHandler.class);
        channelAuthorizer = mock(MonolithChannelAuthorizer.class);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("extracts JWT and active org from WebSocket subprotocols like the gateway")
    void extractsTokenAndActiveOrgFromSubprotocols() {
        WebSocketSession session = mock(WebSocketSession.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Sec-WebSocket-Protocol", "lc.auth, lc.jwt.valid-token, lc.org.org-acme");
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/ws"));
        when(session.getHandshakeHeaders()).thenReturn(headers);

        assertThat(MonolithWsHandler.extractToken(session)).isEqualTo("valid-token");
        assertThat(MonolithWsHandler.extractActiveOrg(session)).isEqualTo("org-acme");
    }

    @Test
    @DisplayName("keeps query params as backward-compatible WebSocket auth fallback")
    void extractsTokenAndActiveOrgFromQueryParams() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/ws?token=query-token&activeOrg=org-query"));
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());

        assertThat(MonolithWsHandler.extractToken(session)).isEqualTo("query-token");
        assertThat(MonolithWsHandler.extractActiveOrg(session)).isEqualTo("org-query");
    }

    @Test
    @DisplayName("subscribe requestSnapshot triggers the CE snapshot path after authorization")
    void subscribeRequestSnapshotTriggersSnapshot() throws Exception {
        MonolithWsHandler handler = newHandler();
        WebSocketSession session = authenticatedSession("s-snapshot");
        String channel = "agent:activity:" + UUID.randomUUID();
        when(channelAuthorizer.authorize(eq("42"), any(), eq(channel))).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"v":1,"type":"subscribe","id":"sub-1","channel":"%s","ts":1,"payload":{"requestSnapshot":true}}
                """.formatted(channel)));

        verify(actionHandler).triggerSnapshot(channel, "sub-42");
    }

    @Test
    @DisplayName("Redis ws pattern listener forwards events to subscribed sessions")
    void redisPatternListenerForwardsEventsToSubscribedSessions() throws Exception {
        MonolithWsHandler handler = newHandler();
        WebSocketSession session = authenticatedSession("s-redis");
        String channel = "agent:activity:" + UUID.randomUUID();
        when(channelAuthorizer.authorize(eq("42"), any(), eq(channel))).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"v":1,"type":"subscribe","id":"sub-1","channel":"%s","ts":1}
                """.formatted(channel)));

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        ArgumentCaptor<PatternTopic> topicCaptor = ArgumentCaptor.forClass(PatternTopic.class);
        verify(listenerContainer).addMessageListener(listenerCaptor.capture(), topicCaptor.capture());
        assertThat(topicCaptor.getValue().getTopic()).isEqualTo("ws:*");

        Message redisMessage = mock(Message.class);
        when(redisMessage.getChannel()).thenReturn(("ws:" + channel).getBytes(StandardCharsets.UTF_8));
        when(redisMessage.getBody()).thenReturn("""
                {"event":"execution_started","executionId":"exec-1"}
                """.getBytes(StandardCharsets.UTF_8));

        when(session.isOpen()).thenReturn(true);
        listenerCaptor.getValue().onMessage(redisMessage, "ws:*".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(3)).sendMessage(sent.capture());
        List<String> payloads = sent.getAllValues().stream().map(TextMessage::getPayload).toList();
        assertThat(payloads).anySatisfy(payload -> {
            assertThat(payload).contains("\"type\":\"event\"");
            assertThat(payload).contains("\"channel\":\"" + channel + "\"");
            assertThat(payload).contains("\"execution_started\"");
            assertThat(payload).contains("\"exec-1\"");
        });
    }

    private MonolithWsHandler newHandler() {
        return new MonolithWsHandler(jwtTokenProvider, listenerContainer, actionHandler, channelAuthorizer, objectMapper);
    }

    private WebSocketSession authenticatedSession(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Sec-WebSocket-Protocol", "lc.auth, lc.jwt.jwt-token, lc.org.org-acme");
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/ws"));
        when(session.getHandshakeHeaders()).thenReturn(headers);
        when(session.isOpen()).thenReturn(false);
        doNothing().when(session).sendMessage(any(TextMessage.class));
        when(jwtTokenProvider.getUsernameFromToken("jwt-token")).thenReturn("sub-42");
        when(jwtTokenProvider.getExpirationDateFromToken("jwt-token")).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(jwtTokenProvider.getUserIdFromToken("jwt-token")).thenReturn(42L);
        return session;
    }
}
