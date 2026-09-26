package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.orchestrator.services.channel.telegram.TelegramPressOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PressOrigin")
class PressOriginTest {

    @Test
    @DisplayName("a request is answerable only on the provider its message went out on")
    void bindsTheProvider() {
        // A token read out of a Slack client and replayed on the WhatsApp endpoint, whose body is
        // not signed, would otherwise decide with a presser id of the copier's choosing.
        assertThat(PressOrigin.of("whatsapp").admits("slack", 5L, "C1")).isFalse();
        assertThat(PressOrigin.of("SLACK").admits("slack", 5L, "C1")).isTrue();
    }

    @Test
    @DisplayName("a per-bot endpoint only answers that bot's messages")
    void bindsTheBot() {
        PressOrigin botA = new PressOrigin("discord", 5L, null);

        assertThat(botA.admits("discord", 5L, "C1")).isTrue();
        assertThat(botA.admits("discord", 6L, "C1")).isFalse();
        assertThat(botA.admits("discord", null, "C1")).isFalse();
    }

    @Test
    @DisplayName("a press counts only from the chat the message was sent to")
    void bindsTheChat() {
        PressOrigin fromNumber = new PressOrigin("whatsapp", 5L, "33612345678");

        assertThat(fromNumber.admits("whatsapp", 5L, "33612345678")).isTrue();
        assertThat(fromNumber.admits("whatsapp", 5L, "33699999999")).isFalse();
    }

    @Test
    @DisplayName("what the endpoint cannot know is not checked; a row with no channel is a Telegram row")
    void unknownPartsAreNotChecked() {
        assertThat(PressOrigin.of("teams").admits("teams", 9L, "19:a")).isTrue();
        assertThat(PressOrigin.of("telegram").admits(null, 9L, "-100")).isTrue();
        assertThat(PressOrigin.of("slack").admits(null, 9L, "-100")).isFalse();
    }

    @Test
    @DisplayName("an origin without a provider is a programming error, not an open door")
    void needsItsChannel() {
        assertThatThrownBy(() -> new PressOrigin(" ", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Telegram presses bind to the chat of the message the button was on, replies to their own chat")
    void telegramOrigins() {
        Map<String, Object> callback = Map.of("id", "q1", "message", Map.of("chat", Map.of("id", -100123)));

        assertThat(TelegramPressOrigin.ofCallback(callback)).isEqualTo(new PressOrigin("telegram", null, "-100123"));
        assertThat(TelegramPressOrigin.ofCallback(Map.of("id", "q1"))).isEqualTo(PressOrigin.of("telegram"));
        assertThat(TelegramPressOrigin.ofMessage(Map.of("chat", Map.of("id", 42))))
                .isEqualTo(new PressOrigin("telegram", null, "42"));
    }
}
