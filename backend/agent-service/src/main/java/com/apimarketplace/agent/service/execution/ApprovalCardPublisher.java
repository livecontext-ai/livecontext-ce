package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.tools.authz.AuthorizationSubject;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the two chat approval cards (connect-a-service, authorize-an-action) onto a
 * live stream.
 *
 * <p><b>Why it is its own component.</b> The cards used to be painted only by the
 * CONSUMER of a tool result - {@code ConversationRedisStreamingCallback} on the Java
 * loop, {@code redis-publisher.mjs} on the CLI-bridge path. That works while the gated
 * call returns immediately, but {@link ToolApprovalGate} parks INSIDE the call: the
 * result the consumer would react to does not exist yet, so a gate that relied on the
 * consumer would wait for a card that nobody will ever paint. The gate therefore paints
 * its own card before parking, and this class is the one implementation both it and the
 * streaming callback share.
 *
 * <p>Event shapes are byte-for-byte what the frontend already consumes: it discriminates
 * a service approval by {@code services} + {@code reason} and an authorization by
 * {@code toolAuthorization}. Nothing here is a new contract.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalCardPublisher {

    private static final String STREAM_CHANNEL_PREFIX = "stream:events:";
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";

    private final StringRedisTemplate redisTemplate;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    /**
     * "Connect this service to continue" card.
     *
     * @param blocking true when the calling tool is parked waiting for this very card.
     *                 The frontend uses it to release the parked call instead of falling
     *                 back to injecting a synthetic user message.
     * @param gateKey  the parked call's identifier, echoed back by the frontend on answer.
     */
    public String publishServiceApproval(String streamId, String conversationId,
                                         List<Map<String, Object>> services, String reason,
                                         boolean needsAttention, boolean blocking, String gateKey) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("services", services);
        event.put("reason", reason != null ? reason : "Services need approval");
        event.put("needsAttention", needsAttention);
        if (blocking) {
            event.put("blocking", true);
            event.put("gateKey", gateKey);
        }
        event.put("timestamp", Instant.now().toString());
        return publish(streamId, conversationId, event, "service_approval");
    }

    /**
     * "Authorize this action" card. {@code metadata} is the gate metadata assembled by
     * {@code RemoteToolExecutionService} (rule / toolName / action / toolCallId /
     * argsSummary / applicationId / subject).
     */
    public String publishToolAuthorization(String streamId, String conversationId,
                                           Map<String, Object> metadata,
                                           boolean blocking, String gateKey) {
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("rule", metadata.get("rule"));
        authorization.put("toolName", metadata.get("toolName"));
        authorization.put("action", metadata.get("action"));
        authorization.put("toolCallId", metadata.get("toolCallId"));
        authorization.put("argsSummary", metadata.get("argsSummary"));
        // Only present for application:acquire - lets the card open the marketplace install
        // modal on the publication the user is about to install.
        authorization.put("applicationId", metadata.get("applicationId"));
        // What the card names (which workflow/version, which cron). Opaque here on purpose:
        // this publisher relays subjects it has never heard of, so a new gated rule needs no
        // change at this hop.
        //
        // OMITTED rather than written as null when a rule names nothing, so both routes put
        // the same shape on the wire: the bridge builds its card from a JS object literal and
        // an absent value simply disappears. The frontend type says `subject?:`, which means
        // undefined - and tsconfig has strict:false, so a null slipping in would not be caught
        // where it is read.
        Object subject = metadata.get(AuthorizationSubject.METADATA_KEY);
        if (subject != null) {
            authorization.put(AuthorizationSubject.METADATA_KEY, subject);
        }
        if (blocking) {
            authorization.put("blocking", true);
            authorization.put("gateKey", gateKey);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("toolAuthorization", authorization);
        event.put("timestamp", Instant.now().toString());
        return publish(streamId, conversationId, event, "tool_authorization");
    }

    /**
     * "Pick one" card: a question the agent put to the person ({@code ask_user}).
     *
     * <p>{@code question} is the channel-agnostic payload ({@code toolCallId} +
     * {@code questions}); the event nests it under {@code askUser}, which is the frontend's
     * discriminant for this card, alongside {@code blocking}/{@code gateKey} exactly like the
     * authorization card.
     */
    public String publishUserQuestion(String streamId, String conversationId,
                                      Map<String, Object> question, boolean blocking, String gateKey) {
        Map<String, Object> askUser = new LinkedHashMap<>(question);
        if (blocking) {
            askUser.put("blocking", true);
            askUser.put("gateKey", gateKey);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("askUser", askUser);
        event.put("timestamp", Instant.now().toString());
        return publish(streamId, conversationId, event, "ask_user_required");
    }

    /**
     * Take a card back out of the replay buffer once it is settled.
     *
     * <p>The buffer is what makes a card survive a reload, and that is exactly why an
     * ANSWERED card has to leave it: the turn keeps running after a release, so the next
     * reload would replay a card the user already dealt with, ask them again, and - since
     * the park is long gone - their second click would release nothing and silently queue a
     * redundant turn instead.
     *
     * @param buffered the JSON returned when the card was published; null/blank is a no-op
     */
    public void unbuffer(String streamId, String buffered) {
        if (streamId == null || streamId.isBlank() || buffered == null || buffered.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForList().remove(StreamRedisKeys.toolsKey(streamId), 1, buffered);
        } catch (Exception e) {
            log.warn("[APPROVAL_CARD] Could not drop the settled card from stream {}: {}", streamId, e.getMessage());
        }
    }

    /** @return the exact JSON that was buffered, so it can be removed again once settled */
    private String publish(String streamId, String conversationId, Map<String, Object> event, String eventType) {
        if (streamId == null || streamId.isBlank()) {
            return null;
        }
        String json;
        Long sseReceivers;
        try {
            json = objectMapper.writeValueAsString(event);
            sseReceivers = redisTemplate.convertAndSend(STREAM_CHANNEL_PREFIX + streamId, json);
        } catch (Exception e) {
            // The card went nowhere. Returning null tells the caller not to park on it.
            log.warn("[APPROVAL_CARD] Failed to publish {} for stream {}: {}", eventType, streamId, e.getMessage());
            return null;
        }
        try {
            String wsChannel = conversationId != null ? WS_CHANNEL_PREFIX + conversationId : null;
            if (wsChannel != null) {
                eventBus.publish(wsChannel, json);
            }
            // Only a card the agent is HOLDING needs to survive a reload. A non-blocking card
            // ends its turn immediately and conversation-service writes its row a moment
            // later, which is how it survived before - buffering those too would put cards
            // the user already dealt with (an install they completed, say) back on screen,
            // and nothing would ever take them out again.
            if (Boolean.TRUE.equals(event.get("blocking"))
                    || isBlockingAuthorization(event)
                    || isBlockingUserQuestion(event)) {
                buffer(streamId, json);
            }
            log.info("[APPROVAL_CARD] Published {} - stream={} ({} recv), ws={}",
                    eventType, streamId, sseReceivers, wsChannel);
        } catch (Exception e) {
            // The card IS out on the SSE channel; only a secondary sink failed. Reporting a
            // failure here would make the caller abandon a park the card already points at,
            // and the result consumer would then paint a SECOND card for the same call.
            log.warn("[APPROVAL_CARD] Partially published {} for stream {}: {}", eventType, streamId, e.getMessage());
        }
        return json;
    }

    /** The authorization card nests its own flag, so the top-level check misses it. */
    @SuppressWarnings("unchecked")
    private static boolean isBlockingAuthorization(Map<String, Object> event) {
        return event.get("toolAuthorization") instanceof Map<?, ?> authorization
                && Boolean.TRUE.equals(((Map<String, Object>) authorization).get("blocking"));
    }

    /** Same nesting for the question card. */
    @SuppressWarnings("unchecked")
    private static boolean isBlockingUserQuestion(Map<String, Object> event) {
        return event.get("askUser") instanceof Map<?, ?> askUser
                && Boolean.TRUE.equals(((Map<String, Object>) askUser).get("blocking"));
    }

    /**
     * Keep the card in the stream's replay buffer, so reloading the page brings it back.
     *
     * <p>The two channels above are live-only: whoever is not listening at that instant
     * never learns the card exists. That was survivable while the row conversation-service
     * writes at end of turn landed a second later - but a parked call delays the end of the
     * turn by minutes, and a user who refreshes inside that window would be left staring at
     * a tool spinning with nothing to click and no way to release it.
     */
    private void buffer(String streamId, String json) {
        try {
            String key = StreamRedisKeys.toolsKey(streamId);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -StreamRedisKeys.MAX_TOOL_EVENTS, -1);
            redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL);
        } catch (Exception e) {
            // Best effort: the card is already on screen for anyone currently connected.
            log.warn("[APPROVAL_CARD] Could not buffer the card for stream {}: {}", streamId, e.getMessage());
        }
    }
}
