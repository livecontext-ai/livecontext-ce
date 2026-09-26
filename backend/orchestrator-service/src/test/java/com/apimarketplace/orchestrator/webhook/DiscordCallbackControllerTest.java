package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import com.apimarketplace.orchestrator.services.channel.discord.DiscordChannelConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DiscordCallbackController")
class DiscordCallbackControllerTest {

    private static final String PAYLOAD = "lcapr:AbCdEfGhIjKlMnOpQrStUv:a";
    private static final String TS = "1700000000";
    /** This bot's credential and the channel the press came from: all a press here may decide. */
    private static final com.apimarketplace.orchestrator.services.channel.PressOrigin ORIGIN =
            new com.apimarketplace.orchestrator.services.channel.PressOrigin("discord", 42L, "C55");

    private KeyPair keys;
    private String publicKeyHex;
    private UUID botId;
    private ChatChannelBotRepository bots;
    private ChannelInboundRouter router;
    private DiscordChannelConnector connector;
    private TaskExecutor executor;
    private DiscordCallbackController controller;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = keys.getPublic().getEncoded();
        // The raw 32 bytes Discord shows as "Public Key", without the X.509 header.
        publicKeyHex = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        botId = UUID.randomUUID();
        ChatChannelBotEntity bot = new ChatChannelBotEntity();
        bot.setId(botId);
        bot.setInboundKey(publicKeyHex);
        bot.setCredentialId(42L);
        bots = mock(ChatChannelBotRepository.class);
        when(bots.findByIdAndChannel(botId, "discord")).thenReturn(Optional.of(bot));
        router = mock(ChannelInboundRouter.class);
        when(router.isOurPayload(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).startsWith("lc"));
        connector = mock(DiscordChannelConnector.class);
        executor = Executors.newFixedThreadPool(2)::execute;
        controller = new DiscordCallbackController(bots, router, connector, executor, new ObjectMapper(),
                java.time.Clock.fixed(java.time.Instant.ofEpochSecond(Long.parseLong(TS)), java.time.ZoneOffset.UTC));
    }

    private String sign(String body) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update((TS + body).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(signer.sign());
    }

    private static String press(String customId) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "type", 3, "application_id", "app-1", "token", "itoken",
                "member", Map.of("user", Map.of("id", "U7")), "channel_id", "C55",
                "data", Map.of("custom_id", customId)));
    }

    private ResponseEntity<Map<String, Object>> send(String body) throws Exception {
        return controller.interaction(botId.toString(), body, sign(body), TS);
    }

    @Test
    @DisplayName("verifies Discord's signature with the raw public key it shows, and nothing else")
    void signatureVerification() throws Exception {
        String body = "{\"type\":1}";
        String signature = sign(body);

        assertThat(DiscordCallbackController.verifies(publicKeyHex, signature, TS, body)).isTrue();
        assertThat(DiscordCallbackController.verifies(publicKeyHex, signature, TS, body + " ")).isFalse();
        assertThat(DiscordCallbackController.verifies(publicKeyHex, signature, "1700000001", body)).isFalse();
        String otherKey = HexFormat.of().formatHex(new byte[32]);
        assertThat(DiscordCallbackController.verifies(otherKey, signature, TS, body)).isFalse();
        assertThat(DiscordCallbackController.verifies("not-hex", signature, TS, body)).isFalse();
        assertThat(DiscordCallbackController.verifies(publicKeyHex, null, TS, body)).isFalse();
    }

    @Test
    @DisplayName("regression: a correctly signed request older than five minutes is refused, so it cannot be replayed")
    void staleSignatureIsRefused() throws Exception {
        String body = "{\"type\":1}";
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        String old = String.valueOf(Long.parseLong(TS) - 301);
        signer.update((old + body).getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response = controller.interaction(botId.toString(), body,
                HexFormat.of().formatHex(signer.sign()), old);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(DiscordCallbackController.fresh(TS, Long.parseLong(TS) + 300)).isTrue();
        assertThat(DiscordCallbackController.fresh("soon", Long.parseLong(TS))).isFalse();
        assertThat(DiscordCallbackController.fresh(null, Long.parseLong(TS))).isFalse();
    }

    @Test
    @DisplayName("answers Discord's PING with a PONG when signed")
    void pingIsAnswered() throws Exception {
        ResponseEntity<Map<String, Object>> response = send("{\"type\":1}");

        assertThat(response.getBody()).containsEntry("type", 1);
    }

    @Test
    @DisplayName("refuses an unsigned PING: Discord probes the URL with bad signatures and rejects one that accepts them")
    void unsignedPingIsRefused() {
        ResponseEntity<Map<String, Object>> response =
                controller.interaction(botId.toString(), "{\"type\":1}", "00", TS);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("an unknown or malformed bot id is refused like a bad signature")
    void unknownBotIsRefused() throws Exception {
        String body = "{\"type\":1}";
        assertThat(controller.interaction(UUID.randomUUID().toString(), body, sign(body), TS)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(controller.interaction("not-a-uuid", body, sign(body), TS).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("a fast decision answers the interaction with the presser's line, visible to them only")
    void fastDecisionAnswersEphemerally() throws Exception {
        CountDownLatch afterAck = new CountDownLatch(1);
        when(router.press(ORIGIN, PAYLOAD, "U7")).thenReturn(new ChannelInboundRouter.Result(true,
                "Approved", false, "tenant", "org", 5L, "C1", afterAck::countDown));

        ResponseEntity<Map<String, Object>> response = send(press(PAYLOAD));

        assertThat(response.getBody()).containsEntry("type", 4);
        assertThat(response.getBody().get("data")).isEqualTo(Map.of("content", "Approved", "flags", 64));
        verify(connector, never()).ackButton(any(), any(), anyString(), anyString(), anyBoolean());
        // The follow-up work (an agent's answer) runs after the response, never before it.
        assertThat(afterAck.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("a slow decision is deferred inside Discord's three seconds, and the line follows on the interaction webhook")
    void slowDecisionIsDeferredThenFollowedUp() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(router.press(ORIGIN, PAYLOAD, "U7")).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return new ChannelInboundRouter.Result(true, "Approved", false, "tenant", "org", 5L, "C1", () -> { });
        });

        long started = System.nanoTime();
        ResponseEntity<Map<String, Object>> response = send(press(PAYLOAD));
        long tookMillis = (System.nanoTime() - started) / 1_000_000;
        release.countDown();

        assertThat(response.getBody()).containsEntry("type", 6);
        assertThat(tookMillis).isLessThan(3_000);
        verify(connector, timeout(3000)).ackButton("tenant", 5L, "app-1:itoken", "Approved", false);
    }

    @Test
    @DisplayName("somebody else's component on the same application is acknowledged silently and not pressed")
    void foreignComponentIsIgnored() throws Exception {
        ResponseEntity<Map<String, Object>> response = send(press("their_button"));

        assertThat(response.getBody()).containsEntry("type", 6);
        verify(router, never()).press(any(), anyString(), any());
    }

    @Test
    @DisplayName("pressOf takes the presser from member.user in a server and from user in a direct message")
    void pressOfReadsBothPresserShapes() {
        assertThat(DiscordCallbackController.pressOf(Map.of("type", 3, "user", Map.of("id", "U1"),
                "data", Map.of("custom_id", PAYLOAD))).userId()).isEqualTo("U1");
        assertThat(DiscordCallbackController.pressOf(Map.of("type", 2, "data", Map.of("custom_id", PAYLOAD))))
                .isNull();
    }

    @Test
    @DisplayName("a refusal is shown to the presser only, and nothing is shown for an unknown token")
    void responseForShapes() {
        assertThat(DiscordCallbackController.responseFor(new ChannelInboundRouter.Result(false, null, false,
                null, null, null, null, () -> { }))).isEqualTo(Map.of("type", 6));
        assertThat(DiscordCallbackController.responseFor(new ChannelInboundRouter.Result(true,
                "You are not allowed to decide this approval.", true, "t", "o", 1L, "C", () -> { })))
                .containsEntry("type", 4);
        verify(connector, never()).ackButton(eq("t"), any(), anyString(), anyString(), anyBoolean());
    }
}
