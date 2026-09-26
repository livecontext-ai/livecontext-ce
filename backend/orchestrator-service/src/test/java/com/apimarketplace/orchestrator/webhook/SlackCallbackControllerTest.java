package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import com.apimarketplace.orchestrator.services.channel.slack.SlackChannelConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SlackCallbackController")
class SlackCallbackControllerTest {

    private static final String SECRET = "signing-secret";
    private static final long NOW = 1_700_000_000L;
    private static final String PAYLOAD = "lcapr:AbCdEfGhIjKlMnOpQrStUv:a";
    private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T/1/abc";

    private ChannelInboundRouter router;
    private SlackChannelConnector connector;
    private TaskExecutor inline;

    @BeforeEach
    void setUp() {
        router = mock(ChannelInboundRouter.class);
        connector = mock(SlackChannelConnector.class);
        inline = Runnable::run;
        when(router.isOurPayload(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).startsWith("lc"));
    }

    private SlackCallbackController controller(String secret) {
        return new SlackCallbackController(router, connector, inline, new ObjectMapper(), secret,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private static String body(String value) throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of(
                "type", "block_actions",
                "user", Map.of("id", "U42"),
                "response_url", RESPONSE_URL,
                "actions", List.of(Map.of("action_id", "lc_0", "value", value))));
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    private static String sign(String secret, long ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(("v0:" + ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a correctly signed, fresh request verifies")
    void signedRequestVerifies() throws Exception {
        String body = body(PAYLOAD);

        assertThat(SlackCallbackController.verifies(SECRET, String.valueOf(NOW), sign(SECRET, NOW, body), body, NOW))
                .isTrue();
    }

    @Test
    @DisplayName("refuses a wrong signature, a tampered body, a stale timestamp and missing headers")
    void refusesEverythingElse() throws Exception {
        String body = body(PAYLOAD);
        String good = sign(SECRET, NOW, body);

        assertThat(SlackCallbackController.verifies("other", String.valueOf(NOW), good, body, NOW)).isFalse();
        assertThat(SlackCallbackController.verifies(SECRET, String.valueOf(NOW), good, body + "x", NOW)).isFalse();
        // Signed by Slack, but six minutes ago: a replay of a captured request.
        long old = NOW - 360;
        assertThat(SlackCallbackController.verifies(SECRET, String.valueOf(old), sign(SECRET, old, body), body, NOW))
                .isFalse();
        assertThat(SlackCallbackController.verifies(SECRET, null, good, body, NOW)).isFalse();
        assertThat(SlackCallbackController.verifies(SECRET, "soon", good, body, NOW)).isFalse();
    }

    @Test
    @DisplayName("with a signing secret configured, an unsigned request is refused and nothing is pressed")
    void unsignedRequestIsRefused() throws Exception {
        ResponseEntity<Void> response = controller(SECRET).interactivity(body(PAYLOAD), String.valueOf(NOW), "v0=bad");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(router, never()).press(any(), anyString(), any());
    }

    @Test
    @DisplayName("a press of ours is decided, acknowledged privately through its response_url, then followed up")
    void pressIsDecidedAndAcknowledged() throws Exception {
        AtomicBoolean afterAck = new AtomicBoolean();
        when(router.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), PAYLOAD, "U42")).thenReturn(new ChannelInboundRouter.Result(true, "Approved",
                false, "tenant", "org", 5L, "C1", () -> afterAck.set(true)));
        String body = body(PAYLOAD);

        ResponseEntity<Void> response = controller(SECRET).interactivity(body, String.valueOf(NOW), sign(SECRET, NOW, body));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(connector).ackButton("tenant", 5L, RESPONSE_URL, "Approved", false);
        assertThat(afterAck).isTrue();
    }

    @Test
    @DisplayName("regression: with no signing secret configured every press is refused, since the presser cannot be trusted")
    void noSecretFailsClosed() throws Exception {
        ResponseEntity<Void> response = controller("").interactivity(body(PAYLOAD), String.valueOf(NOW), "v0=x");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(router, never()).press(any(), anyString(), any());
    }

    @Test
    @DisplayName("the press is bound to the channel it came from, as Slack signed it")
    void pressCarriesItsChannel() throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of("type", "block_actions",
                "user", Map.of("id", "U42"), "channel", Map.of("id", "C9"), "response_url", RESPONSE_URL,
                "actions", List.of(Map.of("value", PAYLOAD))));
        String signed = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        when(router.press(any(), anyString(), any())).thenReturn(new ChannelInboundRouter.Result(false, null,
                false, null, null, null, null, () -> { }));

        controller(SECRET).interactivity(signed, String.valueOf(NOW), sign(SECRET, NOW, signed));

        verify(router).press(new com.apimarketplace.orchestrator.services.channel.PressOrigin("slack", null, "C9"),
                PAYLOAD, "U42");
    }

    @Test
    @DisplayName("somebody else's button in the same app is answered 200 and left alone")
    void foreignButtonIsIgnored() throws Exception {
        String foreign = body("their_button");
        ResponseEntity<Void> response = controller(SECRET).interactivity(foreign, String.valueOf(NOW),
                sign(SECRET, NOW, foreign));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(router, never()).press(any(), anyString(), any());
    }

    @Test
    @DisplayName("an unknown token is not acknowledged: there is no credential to acknowledge with")
    void unknownTokenIsNotAcknowledged() throws Exception {
        when(router.press(any(), anyString(), any())).thenReturn(new ChannelInboundRouter.Result(false, null,
                false, null, null, null, null, () -> { }));

        String signed = body(PAYLOAD);
        controller(SECRET).interactivity(signed, String.valueOf(NOW), sign(SECRET, NOW, signed));

        verify(connector, never()).ackButton(any(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("pressOf reads the first button of a block_actions payload and nothing else")
    void pressOfReadsBlockActionsOnly() {
        assertThat(SlackCallbackController.pressOf(Map.of("type", "view_submission"))).isNull();
        assertThat(SlackCallbackController.pressOf(Map.of("type", "block_actions", "actions", List.of()))).isNull();

        SlackCallbackController.Press press = SlackCallbackController.pressOf(Map.of("type", "block_actions",
                "user", Map.of("id", "U1"), "response_url", RESPONSE_URL,
                "actions", List.of(Map.of("value", PAYLOAD))));
        assertThat(press).isEqualTo(new SlackCallbackController.Press(PAYLOAD, "U1", RESPONSE_URL, null));
        verify(connector, never()).ackButton(any(), anyLong(), eq(RESPONSE_URL), any(), anyBoolean());
    }

    @Test
    @DisplayName("regression: verifies over the exact bytes Slack sent, not a body Spring re-encoded from the form")
    void verifiesTheRawBytes() throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of(
                "type", "block_actions", "user", Map.of("id", "U*42"), "response_url", RESPONSE_URL,
                "actions", List.of(Map.of("value", PAYLOAD))));
        // Slack percent-encodes '*' as %2A; Java's URLEncoder leaves it as '*'. A body rebuilt
        // from the parsed form therefore differs from what Slack signed.
        String slackBytes = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8).replace("*", "%2A");
        assertThat("payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8)).isNotEqualTo(slackBytes);
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(slackBytes.getBytes(StandardCharsets.UTF_8));
        when(router.press(any(), anyString(), any())).thenReturn(new ChannelInboundRouter.Result(true, null,
                false, "tenant", "org", 5L, "C1", () -> { }));

        ResponseEntity<Void> response = controller(SECRET).interactivity(request, String.valueOf(NOW),
                sign(SECRET, NOW, slackBytes));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(router).press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), PAYLOAD, "U*42");
    }

    @Test
    @DisplayName("a body drained upstream is rebuilt to read the press, and then fails closed on a signature")
    void drainedBodyFailsClosedWhenSigned() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addParameter("payload", "{\"type\":\"block_actions\"}");

        assertThat(SlackCallbackController.rawBodyOf(request)).startsWith("payload=%7B");
        assertThat(controller(SECRET).interactivity(request, String.valueOf(NOW), "v0=anything")
                .getStatusCode().value()).isEqualTo(401);
    }
}
