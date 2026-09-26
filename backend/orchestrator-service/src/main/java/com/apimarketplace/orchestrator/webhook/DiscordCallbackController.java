package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import com.apimarketplace.orchestrator.services.channel.PressOrigin;
import com.apimarketplace.orchestrator.services.channel.discord.DiscordChannelConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Presses on our Discord buttons, sent to the Interactions Endpoint URL the person set on their
 * own Discord application: {@code /approval-callback/discord/{botId}}.
 *
 * <p><b>Authenticity.</b> Discord signs every interaction with Ed25519 over
 * {@code <X-Signature-Timestamp><raw body>}, verified here with the application's public key stored
 * on the bot row at connect. Discord probes the URL with bad signatures when it is saved and refuses
 * a URL that accepts them, so this refuses anything that does not verify, PINGs included.
 *
 * <p><b>Timing.</b> Discord waits three seconds, and the answer to the interaction IS the
 * acknowledgement. So the press is decided on the approval executor for up to
 * {@link #DECIDE_WITHIN_MILLIS}: done in time, the presser's line goes back as an ephemeral message
 * in the response itself; too slow, the response says "deferred" and the line follows through the
 * interaction's webhook once the decision lands. Either way the message's buttons are redrawn or
 * closed by the shared services, not here.
 */
@RestController
@RequestMapping("/api/internal/approval-callback")
public class DiscordCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(DiscordCallbackController.class);

    /** DER prefix that turns a raw 32-byte Ed25519 key into an X.509 SubjectPublicKeyInfo. */
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    static final int PING = 1;
    static final int MESSAGE_COMPONENT = 3;
    static final int PONG = 1;
    static final int CHANNEL_MESSAGE = 4;
    static final int DEFERRED_UPDATE = 6;

    /** Leaves room under Discord's three seconds for the response to travel back. */
    static final long DECIDE_WITHIN_MILLIS = 2_200;

    /**
     * How far a signed timestamp may be from now. A signature proves Discord sent the request
     * once; the bound is what stops a captured one (a multi-choice toggle, say) being replayed.
     */
    static final long MAX_SKEW_SECONDS = 300;

    private final ChatChannelBotRepository botRepository;
    private final ChannelInboundRouter router;
    private final DiscordChannelConnector connector;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DiscordCallbackController(ChatChannelBotRepository botRepository, ChannelInboundRouter router,
                                     DiscordChannelConnector connector,
                                     @Qualifier("approvalDelegationExecutor") TaskExecutor executor,
                                     ObjectMapper objectMapper) {
        this(botRepository, router, connector, executor, objectMapper, Clock.systemUTC());
    }

    DiscordCallbackController(ChatChannelBotRepository botRepository, ChannelInboundRouter router,
                              DiscordChannelConnector connector, TaskExecutor executor,
                              ObjectMapper objectMapper, Clock clock) {
        this.clock = clock;
        this.botRepository = botRepository;
        this.router = router;
        this.connector = connector;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/discord/{botId}")
    public ResponseEntity<Map<String, Object>> interaction(
            @PathVariable String botId,
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "X-Signature-Ed25519", required = false) String signature,
            @RequestHeader(value = "X-Signature-Timestamp", required = false) String timestamp) {
        Optional<ChatChannelBotEntity> bot = botOf(botId);
        if (bot.isEmpty() || !fresh(timestamp, clock.instant().getEpochSecond())
                || !verifies(bot.get().getInboundKey(), signature, timestamp, rawBody)) {
            logger.warn("[approval-callback-discord] rejected an interaction that does not verify");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> interaction = parse(rawBody);
        Object type = interaction.get("type");
        if (type instanceof Number n && n.intValue() == PING) {
            return ResponseEntity.ok(Map.of("type", PONG));
        }
        Press press = pressOf(interaction);
        if (press == null || !router.isOurPayload(press.value())) {
            // Somebody else's component on the same application: acknowledge it and do nothing.
            return ResponseEntity.ok(Map.of("type", DEFERRED_UPDATE));
        }
        return ResponseEntity.ok(decide(press, bot.get()));
    }

    private Map<String, Object> decide(Press press, ChatChannelBotEntity bot) {
        // Bound to this bot and to the channel the press came from: this endpoint is per bot and
        // verified with that bot's key, so it can only ever decide that bot's own messages.
        PressOrigin origin = new PressOrigin(DiscordChannelConnector.CHANNEL_ID, bot.getCredentialId(),
                press.channelId());
        CompletableFuture<ChannelInboundRouter.Result> decision = CompletableFuture.supplyAsync(
                () -> router.press(origin, press.value(), press.userId()), executor);
        ChannelInboundRouter.Result result;
        try {
            result = decision.get(DECIDE_WITHIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            // Chained, not waited on: a pool thread parked on another task of the same small pool
            // is how two slow presses would freeze every channel delivery behind them.
            decision.whenCompleteAsync((late, error) -> {
                if (late != null) {
                    followUp(late, press);
                } else {
                    logger.warn("[approval-callback-discord] late press handling failed: {}",
                            error != null ? error.getMessage() : "no result");
                }
            }, executor);
            return Map.of("type", DEFERRED_UPDATE);
        } catch (Exception ex) {
            logger.warn("[approval-callback-discord] press handling failed: {}", ex.getMessage());
            return Map.of("type", DEFERRED_UPDATE);
        }
        executor.execute(result.afterAck());
        return responseFor(result);
    }

    /** The slow path: the response already said "deferred", so the line follows as a follow-up. */
    void followUp(ChannelInboundRouter.Result result, Press press) {
        try {
            if (result.handled() && result.replyToUser() != null) {
                connector.ackButton(result.tenantId(), result.credentialId(),
                        press.applicationId() + ":" + press.interactionToken(), result.replyToUser(),
                        result.asAlert());
            }
            result.afterAck().run();
        } catch (Exception ex) {
            logger.warn("[approval-callback-discord] swallowed late press handling: {}", ex.getMessage());
        }
    }

    /** What Discord shows the presser: their line, privately, or a silent acknowledgement. */
    static Map<String, Object> responseFor(ChannelInboundRouter.Result result) {
        if (!result.handled() || result.replyToUser() == null) {
            return Map.of("type", DEFERRED_UPDATE);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.replyToUser());
        data.put("flags", 64);
        return Map.of("type", CHANNEL_MESSAGE, "data", data);
    }

    private Optional<ChatChannelBotEntity> botOf(String botId) {
        try {
            return botRepository.findByIdAndChannel(UUID.fromString(botId), DiscordChannelConnector.CHANNEL_ID);
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /** Whether a signed timestamp is within {@link #MAX_SKEW_SECONDS} of now; unreadable is not. */
    static boolean fresh(String timestamp, long nowEpochSeconds) {
        if (timestamp == null) {
            return false;
        }
        try {
            return Math.abs(nowEpochSeconds - Long.parseLong(timestamp.trim())) <= MAX_SKEW_SECONDS;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Discord's Ed25519 signature over timestamp + body, under the application's public key.
     * Any missing or malformed piece is a refusal, never an exception.
     */
    static boolean verifies(String publicKeyHex, String signatureHex, String timestamp, String rawBody) {
        if (publicKeyHex == null || signatureHex == null || timestamp == null || rawBody == null) {
            return false;
        }
        try {
            byte[] raw = HexFormat.of().parseHex(publicKeyHex.trim());
            if (raw.length != 32) {
                return false;
            }
            byte[] der = new byte[ED25519_X509_PREFIX.length + raw.length];
            System.arraycopy(ED25519_X509_PREFIX, 0, der, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(raw, 0, der, ED25519_X509_PREFIX.length, raw.length);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((timestamp + rawBody).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(HexFormat.of().parseHex(signatureHex.trim()));
        } catch (Exception ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    record Press(String value, String userId, String applicationId, String interactionToken,
                 String channelId) {}

    /**
     * The button of a MESSAGE_COMPONENT interaction, or null. The presser is {@code member.user}
     * in a server and {@code user} in a direct message.
     */
    static Press pressOf(Map<String, Object> interaction) {
        Object type = interaction.get("type");
        if (!(type instanceof Number n) || n.intValue() != MESSAGE_COMPONENT
                || !(interaction.get("data") instanceof Map<?, ?> data) || data.get("custom_id") == null) {
            return null;
        }
        Object user = interaction.get("member") instanceof Map<?, ?> member ? member.get("user") : interaction.get("user");
        String userId = user instanceof Map<?, ?> u && u.get("id") != null ? String.valueOf(u.get("id")) : null;
        return new Press(String.valueOf(data.get("custom_id")), userId,
                str(interaction.get("application_id")), str(interaction.get("token")),
                str(interaction.get("channel_id")));
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
