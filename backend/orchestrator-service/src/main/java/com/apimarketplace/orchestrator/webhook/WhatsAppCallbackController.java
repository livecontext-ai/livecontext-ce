package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import com.apimarketplace.orchestrator.services.channel.PressOrigin;
import com.apimarketplace.orchestrator.services.channel.whatsapp.WhatsAppChannelConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Presses and replies from WhatsApp, delivered by the webhook the person subscribed in their own
 * Meta app: {@code /approval-callback/whatsapp/{botId}}.
 *
 * <p><b>Subscription.</b> Meta first calls GET with {@code hub.mode=subscribe}, the verify token
 * and a challenge, and saves the webhook only if the challenge comes back. The verify token is the
 * random key generated at connect and shown in the setup steps, compared in constant time.
 *
 * <p><b>Authenticity of a notification.</b> Meta signs notifications with the Meta app's secret,
 * which this product never holds (the app is the person's). What protects a press is what protects
 * a Telegram press without a secret: the button id is an unguessable single-use token, and the
 * allow-list is checked on the presser. A typed reply is accepted only as a reply to the id of a
 * live question message, which Meta gives to the recipient alone, and only from that chat. A
 * notification for another phone number than this bot's is ignored.
 *
 * <p><b>Timing.</b> Meta retries anything not answered 200 quickly, so this answers at once and
 * handles the messages on the approval executor.
 */
@RestController
@RequestMapping("/api/internal/approval-callback")
public class WhatsAppCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppCallbackController.class);

    private final ChatChannelBotRepository botRepository;
    private final ChannelInboundRouter router;
    private final WhatsAppChannelConnector connector;
    private final TaskExecutor executor;

    public WhatsAppCallbackController(ChatChannelBotRepository botRepository, ChannelInboundRouter router,
                                      WhatsAppChannelConnector connector,
                                      @Qualifier("approvalDelegationExecutor") TaskExecutor executor) {
        this.botRepository = botRepository;
        this.router = router;
        this.connector = connector;
        this.executor = executor;
    }

    @GetMapping(value = "/whatsapp/{botId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@PathVariable String botId,
                                         @RequestParam(value = "hub.mode", required = false) String mode,
                                         @RequestParam(value = "hub.verify_token", required = false) String token,
                                         @RequestParam(value = "hub.challenge", required = false) String challenge) {
        Optional<ChatChannelBotEntity> bot = botOf(botId);
        if (bot.isEmpty() || !"subscribe".equals(mode) || challenge == null
                || !sameKey(bot.get().getInboundKey(), token)) {
            logger.info("[approval-callback-whatsapp] refused a subscription handshake for bot {}", botId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(challenge);
    }

    @PostMapping("/whatsapp/{botId}")
    public ResponseEntity<Void> notification(@PathVariable String botId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        Optional<ChatChannelBotEntity> bot = botOf(botId);
        if (bot.isEmpty()) {
            // 200 anyway: an error only makes Meta retry, for a bot that is gone.
            return ResponseEntity.ok().build();
        }
        List<Inbound> inbound = inboundOf(body, bot.get().getBotIdentity());
        if (!inbound.isEmpty()) {
            executor.execute(() -> inbound.forEach(event -> handle(bot.get(), event)));
        }
        return ResponseEntity.ok().build();
    }

    private void handle(ChatChannelBotEntity bot, Inbound event) {
        try {
            // Bound to this bot and to the sender: a WhatsApp chat IS one person, so a press or a
            // reply counts only from the number the message was sent to, whatever the body claims.
            PressOrigin origin = new PressOrigin(WhatsAppChannelConnector.CHANNEL_ID, bot.getCredentialId(),
                    event.from());
            ChannelInboundRouter.Result result;
            if (event.payload() != null) {
                if (!router.isOurPayload(event.payload())) {
                    return;
                }
                result = router.press(origin, event.payload(), event.from());
            } else {
                if (!router.isOurReply(event.from(), event.contextId(), bot.getBotIdentity())) {
                    return;
                }
                result = router.reply(origin, event.from(), event.contextId(), bot.getBotIdentity(),
                        event.text(), event.from());
            }
            if (!result.handled()) {
                return;
            }
            if (result.replyToUser() != null) {
                connector.ackButton(result.tenantId(), result.credentialId(),
                        event.from() + "|" + event.messageId(), result.replyToUser(), result.asAlert());
            }
            result.afterAck().run();
        } catch (Exception ex) {
            logger.warn("[approval-callback-whatsapp] swallowed message handling: {}", ex.getMessage());
        }
    }

    /**
     * One press or typed reply from a notification.
     *
     * @param payload   the pressed button's or list row's id; null for a typed reply
     * @param text      the typed reply; null for a press
     * @param contextId the message this answers (for a press, the message the button was on)
     * @param messageId the incoming message's own id, which the acknowledgement replies to
     */
    record Inbound(String from, String payload, String text, String contextId, String messageId) {}

    /**
     * Every press and every typed reply-to-a-message in a notification for this phone number.
     * Status updates (sent, delivered, read) and messages that answer nothing are left out.
     */
    static List<Inbound> inboundOf(Map<String, Object> body, String phoneNumberId) {
        List<Inbound> out = new ArrayList<>();
        if (body == null || !(body.get("entry") instanceof List<?> entries)) {
            return out;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> e) || !(e.get("changes") instanceof List<?> changes)) {
                continue;
            }
            for (Object change : changes) {
                if (!(change instanceof Map<?, ?> c) || !(c.get("value") instanceof Map<?, ?> value)) {
                    continue;
                }
                Object metadata = value.get("metadata");
                String to = metadata instanceof Map<?, ?> m ? str(m.get("phone_number_id")) : null;
                if (phoneNumberId == null || !phoneNumberId.equals(to)
                        || !(value.get("messages") instanceof List<?> messages)) {
                    continue;
                }
                for (Object message : messages) {
                    if (message instanceof Map<?, ?> msg) {
                        Inbound inbound = inboundOf(msg);
                        if (inbound != null) {
                            out.add(inbound);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Inbound inboundOf(Map<?, ?> msg) {
        String from = str(msg.get("from"));
        String id = str(msg.get("id"));
        String contextId = msg.get("context") instanceof Map<?, ?> ctx ? str(ctx.get("id")) : null;
        if (from == null) {
            return null;
        }
        if ("interactive".equals(msg.get("type")) && msg.get("interactive") instanceof Map<?, ?> interactive) {
            Object reply = interactive.get("button_reply") != null
                    ? interactive.get("button_reply") : interactive.get("list_reply");
            String payload = reply instanceof Map<?, ?> r ? str(r.get("id")) : null;
            return payload != null ? new Inbound(from, payload, null, contextId, id) : null;
        }
        if ("text".equals(msg.get("type")) && contextId != null && msg.get("text") instanceof Map<?, ?> text) {
            String typed = str(text.get("body"));
            return typed != null && !typed.isBlank() ? new Inbound(from, null, typed, contextId, id) : null;
        }
        return null;
    }

    private Optional<ChatChannelBotEntity> botOf(String botId) {
        try {
            return botRepository.findByIdAndChannel(UUID.fromString(botId), WhatsAppChannelConnector.CHANNEL_ID);
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    static boolean sameKey(String expected, String given) {
        return expected != null && given != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
