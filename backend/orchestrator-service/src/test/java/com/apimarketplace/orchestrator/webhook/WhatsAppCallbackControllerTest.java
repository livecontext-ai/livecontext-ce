package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import com.apimarketplace.orchestrator.services.channel.whatsapp.WhatsAppChannelConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WhatsAppCallbackController")
class WhatsAppCallbackControllerTest {

    private static final String PHONE_ID = "106540352242922";
    private static final String PAYLOAD = "lcapr:AbCdEfGhIjKlMnOpQrStUv:a";

    private UUID botId;
    private ChannelInboundRouter router;
    private WhatsAppChannelConnector connector;
    private WhatsAppCallbackController controller;

    @BeforeEach
    void setUp() {
        botId = UUID.randomUUID();
        ChatChannelBotEntity bot = new ChatChannelBotEntity();
        bot.setId(botId);
        bot.setBotIdentity(PHONE_ID);
        bot.setInboundKey("verify-token");
        bot.setCredentialId(5L);
        ChatChannelBotRepository bots = mock(ChatChannelBotRepository.class);
        when(bots.findByIdAndChannel(botId, "whatsapp")).thenReturn(Optional.of(bot));
        router = mock(ChannelInboundRouter.class);
        when(router.isOurPayload(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).startsWith("lc"));
        connector = mock(WhatsAppChannelConnector.class);
        controller = new WhatsAppCallbackController(bots, router, connector, Runnable::run);
    }

    private static Map<String, Object> notification(String phoneId, Map<String, Object> message) {
        return Map.of("object", "whatsapp_business_account", "entry", List.of(Map.of("changes", List.of(Map.of(
                "field", "messages",
                "value", Map.of("metadata", Map.of("phone_number_id", phoneId), "messages", List.of(message)))))));
    }

    private static Map<String, Object> buttonPress(String id) {
        return Map.of("from", "33612345678", "id", "wamid.press", "type", "interactive",
                "context", Map.of("id", "wamid.question"),
                "interactive", Map.of("type", "button_reply", "button_reply", Map.of("id", id, "title", "Approve")));
    }

    @Test
    @DisplayName("completes Meta's subscription handshake only with the bot's verify token")
    void handshake() {
        assertThat(controller.verify(botId.toString(), "subscribe", "verify-token", "12345").getBody()).isEqualTo("12345");
        assertThat(controller.verify(botId.toString(), "subscribe", "wrong", "12345").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.verify(botId.toString(), "unsubscribe", "verify-token", "1").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.verify(UUID.randomUUID().toString(), "subscribe", "verify-token", "1")
                .getStatusCode().value()).isEqualTo(403);
        assertThat(controller.verify("not-a-uuid", "subscribe", "verify-token", "1").getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("a button press is decided and the presser is answered as a reply to their press")
    void buttonPressIsDecided() {
        AtomicBoolean afterAck = new AtomicBoolean();
        when(router.press(new com.apimarketplace.orchestrator.services.channel.PressOrigin("whatsapp", 5L, "33612345678"), PAYLOAD, "33612345678")).thenReturn(new ChannelInboundRouter.Result(true,
                "Approved", false, "tenant", "org", 5L, "33612345678", () -> afterAck.set(true)));

        assertThat(controller.notification(botId.toString(), notification(PHONE_ID, buttonPress(PAYLOAD)))
                .getStatusCode().value()).isEqualTo(200);

        verify(connector).ackButton("tenant", 5L, "33612345678|wamid.press", "Approved", false);
        assertThat(afterAck).isTrue();
    }

    @Test
    @DisplayName("a typed reply to a live question is handed over as the answer")
    void typedReplyIsAnAnswer() {
        when(router.isOurReply("33612345678", "wamid.question", PHONE_ID)).thenReturn(true);
        when(router.reply(new com.apimarketplace.orchestrator.services.channel.PressOrigin("whatsapp", 5L, "33612345678"), "33612345678", "wamid.question", PHONE_ID, "Tuesday", "33612345678"))
                .thenReturn(new ChannelInboundRouter.Result(true, null, false, "tenant", "org", 5L, "336", () -> { }));

        controller.notification(botId.toString(), notification(PHONE_ID, Map.of("from", "33612345678", "id", "wamid.r",
                "type", "text", "context", Map.of("id", "wamid.question"), "text", Map.of("body", "Tuesday"))));

        verify(router).reply(new com.apimarketplace.orchestrator.services.channel.PressOrigin("whatsapp", 5L, "33612345678"), "33612345678", "wamid.question", PHONE_ID, "Tuesday", "33612345678");
        // Recorded without a line of its own: the closing follow-up says what was recorded.
        verify(connector, never()).ackButton(any(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("a reply to a message that is not a live question of ours is left alone")
    void unrelatedReplyIsIgnored() {
        controller.notification(botId.toString(), notification(PHONE_ID, Map.of("from", "336", "id", "wamid.r",
                "type", "text", "context", Map.of("id", "wamid.other"), "text", Map.of("body", "hello"))));

        verify(router, never()).reply(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a notification for another phone number than this bot's is ignored")
    void otherNumberIsIgnored() {
        controller.notification(botId.toString(), notification("999", buttonPress(PAYLOAD)));

        verify(router, never()).press(any(), anyString(), any());
    }

    @Test
    @DisplayName("an unknown bot is answered 200 so Meta does not retry, and nothing happens")
    void unknownBotIsAcknowledged() {
        assertThat(controller.notification(UUID.randomUUID().toString(), notification(PHONE_ID, buttonPress(PAYLOAD)))
                .getStatusCode().value()).isEqualTo(200);
        verify(router, never()).press(any(), anyString(), any());
    }

    @Test
    @DisplayName("reads button and list replies and quoted text; skips plain messages and status updates")
    void inboundParsing() {
        Map<String, Object> listReply = Map.of("from", "1", "id", "w1", "type", "interactive",
                "interactive", Map.of("type", "list_reply", "list_reply", Map.of("id", PAYLOAD)));
        Map<String, Object> plainText = Map.of("from", "1", "id", "w2", "type", "text", "text", Map.of("body", "hi"));
        Map<String, Object> statusesOnly = Map.of("entry", List.of(Map.of("changes", List.of(Map.of("value",
                Map.of("metadata", Map.of("phone_number_id", PHONE_ID), "statuses", List.of(Map.of("id", "w"))))))));

        assertThat(WhatsAppCallbackController.inboundOf(notification(PHONE_ID, listReply), PHONE_ID))
                .containsExactly(new WhatsAppCallbackController.Inbound("1", PAYLOAD, null, null, "w1"));
        assertThat(WhatsAppCallbackController.inboundOf(notification(PHONE_ID, plainText), PHONE_ID)).isEmpty();
        assertThat(WhatsAppCallbackController.inboundOf(statusesOnly, PHONE_ID)).isEmpty();
        assertThat(WhatsAppCallbackController.inboundOf(null, PHONE_ID)).isEmpty();
    }
}
