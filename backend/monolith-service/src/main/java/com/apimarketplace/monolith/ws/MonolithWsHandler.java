package com.apimarketplace.monolith.ws;

import com.apimarketplace.auth.security.JwtTokenProvider;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monolith WebSocket handler - implements the same protocol as the Gateway's
 * WsProtocolHandler but using imperative Spring WebSocket and Redis Pub/Sub.
 *
 * Protocol:
 * - Client sends: subscribe, unsubscribe, action, pong, auth.refresh
 * - Server sends: hello, subscribed, unsubscribed, event, ping, error
 *
 * Redis bridge: subscribes to "ws:{channel}" topics and forwards to WS sessions.
 */
@Component
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithWsHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(MonolithWsHandler.class);
    private static final String REDIS_PREFIX = "ws:";
    private static final String AUTH_SUBPROTOCOL = "lc.auth";
    private static final String TOKEN_SUBPROTOCOL_PREFIX = "lc.jwt.";
    private static final String ORG_SUBPROTOCOL_PREFIX = "lc.org.";
    private static final int HEARTBEAT_MS = 30_000;
    private static final int MAX_SUBSCRIPTIONS = 50;

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final MonolithWsActionHandler actionHandler;
    private final MonolithChannelAuthorizer channelAuthorizer;
    private final ObjectMapper objectMapper;

    // Session state
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    // Channel to set of session IDs subscribed.
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();

    public MonolithWsHandler(JwtTokenProvider jwtTokenProvider,
                             RedisMessageListenerContainer redisMessageListenerContainer,
                             MonolithWsActionHandler actionHandler,
                             MonolithChannelAuthorizer channelAuthorizer,
                             ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisMessageListenerContainer = redisMessageListenerContainer;
        this.actionHandler = actionHandler;
        this.channelAuthorizer = channelAuthorizer;
        this.objectMapper = objectMapper;

        startRedisBridge();
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        String token = extractToken(session);
        String userId;
        try {
            if (token == null) throw new Exception("No token");
            userId = jwtTokenProvider.getUsernameFromToken(token);
            // Check expiration
            var expiration = jwtTokenProvider.getExpirationDateFromToken(token);
            if (expiration.before(new java.util.Date())) throw new Exception("Token expired");
        } catch (Exception e) {
            log.warn("[MonolithWS] Rejected: invalid token (sessionId={}, reason={})", session.getId(), e.getMessage());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        // Resolve the NUMERIC user id (JWT `userId` claim == X-User-ID) and the active org for
        // per-channel authorization. `userId` above is the JWT subject (provider id / OAuth sub),
        // which does NOT match the numeric ids the channels (user:/task:board:) and ScopeGuard use.
        String numericUserId = null;
        try {
            Long uid = jwtTokenProvider.getUserIdFromToken(token);
            if (uid != null) numericUserId = String.valueOf(uid);
        } catch (Exception ignored) {
            // numericUserId stays null, channel authz fail-closed below
        }
        String orgId = resolveActiveOrgId(token, extractActiveOrg(session));
        sessions.put(session.getId(), new SessionState(session, userId, numericUserId, orgId));

        // Send hello
        var hello = Envelope.server("hello", null, Map.of(
                "sessionId", session.getId(),
                "userId", userId,
                "heartbeatMs", HEARTBEAT_MS,
                "maxSubscriptions", MAX_SUBSCRIPTIONS
        ));
        sendJson(session, hello);

        log.info("[MonolithWS] Connected: sessionId={}, userId={}", session.getId(), userId);

        // Start heartbeat
        scheduleHeartbeat(session);
    }

    @Override
    public @NonNull List<String> getSubProtocols() {
        return List.of(AUTH_SUBPROTOCOL);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;

        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), Map.class);
        } catch (JsonProcessingException e) {
            sendJson(session, Envelope.error(null, "Invalid JSON"));
            return;
        }

        String type = (String) envelope.get("type");
        String id = (String) envelope.get("id");
        String channel = (String) envelope.get("channel");

        if (type == null) {
            sendJson(session, Envelope.error(id, "Missing type"));
            return;
        }

        log.debug("[MonolithWS] Message received: type={}, channel={}, sessionId={}", type, channel, session.getId());

        switch (type) {
            case "subscribe" -> handleSubscribe(session, id, channel, envelope);
            case "unsubscribe" -> handleUnsubscribe(session, id, channel);
            case "action" -> handleAction(state, id, envelope);
            case "pong" -> { /* heartbeat ack - nothing to do */ }
            case "auth.refresh" -> sendJson(session, Envelope.reply("auth.refreshed", id, null));
            default -> sendJson(session, Envelope.error(id, "Unknown type: " + type));
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        SessionState state = sessions.remove(session.getId());
        if (state == null) return;

        // Unsubscribe from all channels
        for (String channel : state.channels) {
            removeSubscriber(session.getId(), channel);
        }
        log.info("[MonolithWS] Disconnected: sessionId={}, userId={}", session.getId(), state.userId);
    }

    // ── Subscribe / Unsubscribe ──────────────────────────────

    private void handleSubscribe(WebSocketSession session, String id, String channel, Map<String, Object> envelope) throws IOException {
        if (channel == null || channel.isBlank()) {
            sendJson(session, Envelope.error(id, "Missing channel"));
            return;
        }

        SessionState state = sessions.get(session.getId());
        if (state == null) return;

        if (state.channels.size() >= MAX_SUBSCRIPTIONS) {
            sendJson(session, Envelope.error(id, "Max subscriptions reached"));
            return;
        }

        // Per-channel authorization. CE has no gateway ChannelAuthorizer (the gateway is bypassed),
        // so authorize here in-JVM - else any authenticated user could subscribe to another tenant's
        // conversation:/workflow:run:/task:board: channel. Fail-closed.
        if (!channelAuthorizer.authorize(state.numericUserId, state.orgId, channel)) {
            log.warn("[MonolithWS] Subscription DENIED: sessionId={}, numericUserId={}, channel={}",
                    session.getId(), state.numericUserId, channel);
            sendJson(session, Envelope.error(id, "Forbidden: not authorized for channel " + channel));
            return;
        }

        state.channels.add(channel);
        addSubscriber(session.getId(), channel);
        log.info("[MonolithWS] Subscribed: sessionId={}, channel={}", session.getId(), channel);

        sendJson(session, Envelope.reply("subscribed", id, Map.of("channel", channel)));

        // Trigger snapshot if requested
        Object payload = envelope.get("payload");
        boolean wantsSnapshot = false;
        if (payload instanceof Map<?, ?> pm) {
            Object flag = pm.get("requestSnapshot");
            wantsSnapshot = Boolean.TRUE.equals(flag) || "true".equals(flag);
        }
        if (wantsSnapshot) {
            actionHandler.triggerSnapshot(channel, state.userId);
        }
    }

    private void handleUnsubscribe(WebSocketSession session, String id, String channel) throws IOException {
        if (channel == null) return;
        SessionState state = sessions.get(session.getId());
        if (state != null) state.channels.remove(channel);
        removeSubscriber(session.getId(), channel);
        sendJson(session, Envelope.reply("unsubscribed", id, Map.of("channel", channel)));
    }

    // ── Action handling ──────────────────────────────────────

    private void handleAction(SessionState state, String id, Map<String, Object> envelope) {
        Object payloadObj = envelope.get("payload");
        if (!(payloadObj instanceof Map<?, ?> payloadMap)) {
            try { sendJson(state.session, Envelope.error(id, "Action payload must be an object")); } catch (IOException ignored) {}
            return;
        }

        String action = (String) payloadMap.get("action");
        Object data = payloadMap.get("data");

        // numericUserId (== X-User-ID) + active org - the internal controllers'
        // run-scope guards and credit checks key on the numeric id, not the JWT
        // subject; passing state.userId (provider sub) here would fail them.
        actionHandler.handle(state.numericUserId, state.orgId, state.session, id, action, data);
    }

    // ── Redis bridge ─────────────────────────────────────────

    private void addSubscriber(String sessionId, String channel) {
        channelSubscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    private void removeSubscriber(String sessionId, String channel) {
        Set<String> subs = channelSubscribers.get(channel);
        if (subs == null) return;
        subs.remove(sessionId);

        if (subs.isEmpty()) {
            channelSubscribers.remove(channel);
            log.info("[MonolithWS] Subscriptions removed: channel={}{}", REDIS_PREFIX, channel);
        }
    }

    private void startRedisBridge() {
        // EventBus is Redis-backed in CE, and direct stream publishers also use Redis.
        // Register once with Spring's listener container instead of opening a hot pSubscribe
        // loop, which can churn connections and miss broadcasts under load.
        redisMessageListenerContainer.addMessageListener((message, pattern) -> {
            String redisChannel = new String(message.getChannel(), java.nio.charset.StandardCharsets.UTF_8);
            if (redisChannel.startsWith(REDIS_PREFIX)) {
                String wsChannel = redisChannel.substring(REDIS_PREFIX.length());
                String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                try {
                    broadcastToChannel(wsChannel, body);
                } catch (Exception e) {
                    log.warn("[MonolithWS] Redis bridge failed for channel {}: {}", redisChannel, e.getMessage());
                }
            }
        }, new PatternTopic(REDIS_PREFIX + "*"));
        log.info("[MonolithWS] Registered Redis pattern subscriber: {}*", REDIS_PREFIX);
    }

    private void broadcastToChannel(String channel, String rawMessage) {
        Set<String> subs = channelSubscribers.get(channel);
        log.debug("[MonolithWS] broadcastToChannel: channel={}, hasSubs={}, allChannels={}",
                channel, subs != null ? subs.size() : "null", channelSubscribers.keySet());
        if (subs == null || subs.isEmpty()) return;

        // Parse JSON payload (same as gateway's RedisChannelBridge.handleRedisMessage)
        Object payload;
        try {
            payload = objectMapper.readValue(rawMessage, Map.class);
        } catch (JsonProcessingException e) {
            payload = Map.of("data", rawMessage);
        }

        var event = Envelope.event(channel, payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return;
        }

        TextMessage textMsg = new TextMessage(json);
        for (String sessionId : subs) {
            SessionState state = sessions.get(sessionId);
            if (state != null && state.session.isOpen()) {
                try {
                    synchronized (state.session) {
                        state.session.sendMessage(textMsg);
                    }
                } catch (IOException e) {
                    log.debug("[MonolithWS] Send failed to session {}: {}", sessionId, e.getMessage());
                }
            }
        }
    }

    // ── Heartbeat ────────────────────────────────────────────

    private void scheduleHeartbeat(WebSocketSession session) {
        Thread.ofVirtual().name("ws-heartbeat-" + session.getId()).start(() -> {
            while (session.isOpen()) {
                try {
                    Thread.sleep(HEARTBEAT_MS);
                    if (session.isOpen()) {
                        var ping = Envelope.server("ping", null, null);
                        sendJson(session, ping);
                    }
                } catch (Exception e) {
                    break;
                }
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────

    private void sendJson(WebSocketSession session, Object obj) throws IOException {
        String json = objectMapper.writeValueAsString(obj);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    static String extractToken(WebSocketSession session) {
        String protocolToken = extractProtocolValue(session, TOKEN_SUBPROTOCOL_PREFIX);
        if (protocolToken != null) {
            return protocolToken;
        }
        return extractQueryParam(session == null ? null : session.getUri(), "token");
    }

    static String extractActiveOrg(WebSocketSession session) {
        String protocolOrg = extractProtocolValue(session, ORG_SUBPROTOCOL_PREFIX);
        if (protocolOrg != null) {
            return protocolOrg;
        }
        return extractQueryParam(session == null ? null : session.getUri(), "activeOrg");
    }

    private static String extractQueryParam(URI uri, String name) {
        if (uri == null) return null;
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        return params.getFirst(name);
    }

    private static String extractProtocolValue(WebSocketSession session, String prefix) {
        if (session == null || session.getHandshakeHeaders() == null) {
            return null;
        }
        for (String headerValue : session.getHandshakeHeaders().getOrEmpty("Sec-WebSocket-Protocol")) {
            if (headerValue == null) {
                continue;
            }
            for (String protocol : headerValue.split(",")) {
                String trimmed = protocol.trim();
                if (trimmed.startsWith(prefix)) {
                    String value = trimmed.substring(prefix.length());
                    return value.isBlank() ? null : value;
                }
            }
        }
        return null;
    }

    /**
     * Resolve the session's active organization id for channel authorization. Mirrors the gateway's
     * WsHandshakeAuthInterceptor: honor the client's {@code activeOrg} query param ONLY if it's a
     * real, non-paused membership in the token; otherwise fall back to the token's default org. This
     * prevents a user from passing an arbitrary org to subscribe to org-scoped channels.
     */
    @SuppressWarnings("unchecked")
    private String resolveActiveOrgId(String token, String activeOrgParam) {
        try {
            String defaultOrg = jwtTokenProvider.getClaimFromToken(token, c -> c.get("defaultOrganizationId", String.class));
            if (activeOrgParam != null && !activeOrgParam.isBlank()) {
                List<Map<String, Object>> memberships =
                        jwtTokenProvider.getClaimFromToken(token, c -> (List<Map<String, Object>>) c.get("memberships", List.class));
                if (memberships != null) {
                    for (Map<String, Object> m : memberships) {
                        if (activeOrgParam.equals(m.get("orgId")) && !Boolean.TRUE.equals(m.get("paused"))) {
                            return activeOrgParam;
                        }
                    }
                }
            }
            return defaultOrg;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Inner types ──────────────────────────────────────────

    static class SessionState {
        final WebSocketSession session;
        final String userId;          // JWT subject (provider id / OAuth sub) - used for hello + action handlers
        final String numericUserId;   // numeric DB user id (JWT `userId` claim == X-User-ID) - used for channel authz
        final String orgId;           // resolved active organization id - used for org-scoped channel authz
        final Set<String> channels = ConcurrentHashMap.newKeySet();

        SessionState(WebSocketSession session, String userId, String numericUserId, String orgId) {
            this.session = session;
            this.userId = userId;
            this.numericUserId = numericUserId;
            this.orgId = orgId;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Envelope(int v, String type, String id, String ref, String channel, Long channelSeq, long ts, Object payload) {
        static Envelope server(String type, String channel, Object payload) {
            return new Envelope(1, type, UUID.randomUUID().toString(), null, channel, null, System.currentTimeMillis(), payload);
        }
        static Envelope reply(String type, String ref, Object payload) {
            return new Envelope(1, type, UUID.randomUUID().toString(), ref, null, null, System.currentTimeMillis(), payload);
        }
        static Envelope event(String channel, Object payload) {
            return new Envelope(1, "event", UUID.randomUUID().toString(), null, channel, null, System.currentTimeMillis(), payload);
        }
        static Envelope error(String ref, String message) {
            return new Envelope(1, "error", UUID.randomUUID().toString(), ref, null, null, System.currentTimeMillis(), Map.of("message", message));
        }
    }
}
