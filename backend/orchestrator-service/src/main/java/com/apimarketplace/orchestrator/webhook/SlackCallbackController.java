package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import com.apimarketplace.orchestrator.services.channel.PressOrigin;
import com.apimarketplace.orchestrator.services.channel.slack.SlackChannelConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Presses on our Slack buttons, sent to the platform Slack app's Interactivity Request URL.
 *
 * <p>Route: {@code /approval-callback/slack}, public through the same gateway rule as Telegram's.
 *
 * <p><b>Authenticity.</b> Slack signs every request: {@code X-Slack-Signature} is
 * {@code v0=} + hex HMAC-SHA256 of {@code v0:<timestamp>:<raw body>} under the app's signing secret.
 * A request that does not verify under {@code orchestrator.approval.slack.signing-secret} is refused,
 * and so is one whose timestamp is more than five minutes off, which is what stops a captured
 * request being replayed later. With no secret configured EVERY press is refused (and a warning is
 * logged at startup): the body names the presser and the channel, and unsigned they are whatever
 * the sender wrote. The response URL a press acknowledges through is restricted separately, in
 * {@link SlackChannelConnector#ackButton}.
 *
 * <p><b>Timing.</b> Slack waits three seconds for a 200 and then shows the person an error. So this
 * answers at once and does the work on the approval executor, acknowledging afterwards through the
 * press's {@code response_url}, which stays valid for thirty minutes.
 */
@RestController
@RequestMapping("/api/internal/approval-callback")
public class SlackCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(SlackCallbackController.class);

    /** How far a request's timestamp may be from now, Slack's own recommendation. */
    static final long MAX_SKEW_SECONDS = 300;

    private final ChannelInboundRouter router;
    private final SlackChannelConnector connector;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final String signingSecret;
    private final Clock clock;

    // Two constructors: without this Spring has no way to choose and the whole application
    // fails to start ("No default constructor found"), which no unit test sees.
    @org.springframework.beans.factory.annotation.Autowired
    public SlackCallbackController(ChannelInboundRouter router, SlackChannelConnector connector,
                                   @Qualifier("approvalDelegationExecutor") TaskExecutor executor,
                                   ObjectMapper objectMapper,
                                   @Value("${orchestrator.approval.slack.signing-secret:${SLACK_SIGNING_SECRET:}}") String signingSecret) {
        this(router, connector, executor, objectMapper, signingSecret, Clock.systemUTC());
    }

    SlackCallbackController(ChannelInboundRouter router, SlackChannelConnector connector, TaskExecutor executor,
                            ObjectMapper objectMapper, String signingSecret, Clock clock) {
        this.router = router;
        this.connector = connector;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.signingSecret = signingSecret;
        this.clock = clock;
        if (signingSecret == null || signingSecret.isBlank()) {
            logger.warn("[approval-callback-slack] orchestrator.approval.slack.signing-secret is not set: every "
                    + "Slack press will be refused until it is (without it, who pressed cannot be trusted)");
        }
    }

    /**
     * The body is read from the request stream, never through {@code @RequestBody}: for a form
     * POST Spring rebuilds the body from the parsed parameters, re-encoded its own way, and a
     * signature Slack computed over ITS encoding would then fail on every request that
     * contains a character the two encoders write differently.
     */
    @PostMapping(value = "/slack", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Void> interactivity(
            HttpServletRequest request,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature) {
        return interactivity(rawBodyOf(request), timestamp, signature);
    }

    ResponseEntity<Void> interactivity(String rawBody, String timestamp, String signature) {
        // Fail closed. Slack's body names the presser and the channel, and both decide who may
        // answer; unsigned, anybody holding a copied button value could claim to be anyone.
        if (signingSecret == null || signingSecret.isBlank()
                || !verifies(signingSecret, timestamp, signature, rawBody, clock.instant().getEpochSecond())) {
            logger.warn("[approval-callback-slack] rejected a request that does not carry Slack's signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> payload = payloadOf(rawBody);
        Press press = pressOf(payload);
        if (press == null || !router.isOurPayload(press.value())) {
            // Not a press of ours (a shortcut, a modal, somebody else's button in the same app).
            return ResponseEntity.ok().build();
        }
        executor.execute(() -> handle(press));
        return ResponseEntity.ok().build();
    }

    private void handle(Press press) {
        try {
            // The platform app is shared by every workspace, so there is no bot to bind to; the
            // channel the button was pressed in is, and it must be the one the message went to.
            ChannelInboundRouter.Result result = router.press(
                    new PressOrigin(SlackChannelConnector.CHANNEL_ID, null, press.channelId()),
                    press.value(), press.userId());
            if (!result.handled()) {
                return;
            }
            if (result.replyToUser() != null) {
                // Ephemeral, so only the presser sees it: a refusal is about them, not the channel.
                connector.ackButton(result.tenantId(), result.credentialId(), press.responseUrl(),
                        result.replyToUser(), result.asAlert());
            }
            result.afterAck().run();
        } catch (Exception ex) {
            logger.warn("[approval-callback-slack] swallowed press handling: {}", ex.getMessage());
        }
    }

    /**
     * Slack's request signature, compared in constant time.
     *
     * <p>Refused as well when the timestamp is missing, unreadable or more than five minutes away,
     * because a signature over an old request proves only that Slack once sent it.
     */
    static boolean verifies(String secret, String timestamp, String signature, String rawBody, long nowEpochSeconds) {
        if (timestamp == null || signature == null || rawBody == null) {
            return false;
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - sent) > MAX_SKEW_SECONDS) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "v0=" + HexFormat.of().formatHex(
                    mac.doFinal(("v0:" + sent + ":" + rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * The exact bytes Slack sent. If something upstream already parsed the form (which drains
     * the stream), the body is rebuilt from the {@code payload} parameter: good enough to read
     * the press, never good enough to verify a signature, which then fails closed.
     */
    static String rawBodyOf(HttpServletRequest request) {
        try {
            String raw = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
            if (!raw.isEmpty()) {
                return raw;
            }
        } catch (IOException ex) {
            logger.info("[approval-callback-slack] could not read the request body: {}", ex.getMessage());
        }
        String payload = request.getParameter("payload");
        return payload != null ? "payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8) : null;
    }

    /** The {@code payload} form field, parsed. Slack sends interactions as one url-encoded JSON field. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(String rawBody) {
        if (rawBody == null) {
            return Map.of();
        }
        for (String pair : rawBody.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "payload".equals(pair.substring(0, eq))) {
                try {
                    return objectMapper.readValue(
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8), Map.class);
                } catch (Exception ex) {
                    return Map.of();
                }
            }
        }
        return Map.of();
    }

    record Press(String value, String userId, String responseUrl, String channelId) {}

    /** The first button action of a {@code block_actions} payload, or null. */
    @SuppressWarnings("unchecked")
    static Press pressOf(Map<String, Object> payload) {
        if (payload == null || !"block_actions".equals(payload.get("type"))) {
            return null;
        }
        Object actions = payload.get("actions");
        if (!(actions instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> action)) {
            return null;
        }
        Object user = payload.get("user");
        String userId = user instanceof Map<?, ?> u && u.get("id") != null ? String.valueOf(u.get("id")) : null;
        Object value = ((Map<String, Object>) action).get("value");
        Object responseUrl = payload.get("response_url");
        Object channel = payload.get("channel");
        Object channelId = channel instanceof Map<?, ?> c ? c.get("id") : null;
        return new Press(value != null ? String.valueOf(value) : null, userId,
                responseUrl != null ? String.valueOf(responseUrl) : null,
                channelId != null ? String.valueOf(channelId) : null);
    }
}
