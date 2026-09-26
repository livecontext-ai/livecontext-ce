package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TeamsDecisionController")
class TeamsDecisionControllerTest {

    private static final String PAYLOAD = "lcapr:AbCdEfGhIjKlMnOpQrStUv:a";

    private ChannelInboundRouter router;
    private TeamsDecisionController controller;

    @BeforeEach
    void setUp() {
        router = mock(ChannelInboundRouter.class);
        when(router.isOurPayload(any())).thenAnswer(i -> i.getArgument(0) != null
                && ((String) i.getArgument(0)).startsWith("lc"));
        controller = new TeamsDecisionController(router, Runnable::run);
    }

    @Test
    @DisplayName("opening the link only shows a confirmation: link previews and scanners must not decide")
    void getNeverDecides() {
        ResponseEntity<String> page = controller.show(PAYLOAD);

        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Approve").contains("method=\"post\"").contains(PAYLOAD);
        verify(router, never()).press(any(), anyString(), any());
    }

    @Test
    @DisplayName("confirming presses through the shared router, with no presser identity to pass")
    void postDecides() {
        AtomicBoolean afterAck = new AtomicBoolean();
        when(router.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("teams"), PAYLOAD, null)).thenReturn(new ChannelInboundRouter.Result(true, "Approved",
                false, "t", "o", 1L, "19:a", () -> afterAck.set(true)));

        ResponseEntity<String> page = controller.decide(PAYLOAD);

        assertThat(page.getBody()).contains("Approved").contains("Done");
        assertThat(afterAck).isTrue();
    }

    @Test
    @DisplayName("a refusal is shown as not recorded")
    void refusalIsShown() {
        when(router.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("teams"), PAYLOAD, null)).thenReturn(new ChannelInboundRouter.Result(true,
                "You are not allowed to decide this approval.", true, "t", "o", 1L, "19:a", () -> { }));

        assertThat(controller.decide(PAYLOAD).getBody()).contains("Not recorded").contains("not allowed");
    }

    @Test
    @DisplayName("a request that no longer exists answers 410, and a foreign or cut link 404, without pressing")
    void goneAndUnknown() {
        when(router.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("teams"), PAYLOAD, null)).thenReturn(new ChannelInboundRouter.Result(false, null, false,
                null, null, null, null, () -> { }));

        assertThat(controller.decide(PAYLOAD).getStatusCode().value()).isEqualTo(410);
        assertThat(controller.decide("somebody-else").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.show(null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("the page is locked down: no script, no framing, not cached, not indexed, everything escaped")
    void pageIsLockedDown() {
        ResponseEntity<String> page = TeamsDecisionController.page(
                org.springframework.http.HttpStatus.OK, "<b>t</b>", "\"quoted\" & <script>", "p\"><x");

        assertThat(page.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'")
                .contains("frame-ancestors 'none'");
        assertThat(page.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(page.getBody()).doesNotContain("<script>").doesNotContain("<b>t</b>").doesNotContain("p\"><x")
                .contains("&lt;script&gt;").contains("&quot;quoted&quot; &amp;");
    }

    @Test
    @DisplayName("names the action of approval payloads, and stays generic for a question option")
    void actionNames() {
        assertThat(TeamsDecisionController.actionOf("lcapr:t:r")).isEqualTo("Reject");
        assertThat(TeamsDecisionController.actionOf("lcask:t:done")).isEqualTo("Done");
        assertThat(TeamsDecisionController.actionOf("lcask:t:o2")).isEqualTo("the option you picked");
    }
}
