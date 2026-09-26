package com.apimarketplace.orchestrator.services.channel.discord;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.BotIdentity;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChatCandidate;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DiscordChannelConnector")
class DiscordChannelConnectorTest {

    private static final String KEY = "a".repeat(64);

    private CatalogCalls calls;
    private DiscordChannelConnector connector;

    @BeforeEach
    void setUp() {
        calls = mock(CatalogCalls.class);
        connector = new DiscordChannelConnector(calls);
    }

    private void answers(ToolRef tool, Object body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> output = body instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("data", body);
        when(calls.call(eq("discord"), eq(tool), any(), anyString(), anyLong()))
                .thenReturn(Outcome.of(new ExecutionResult(true, output, List.of(), List.of())));
    }

    @Test
    @DisplayName("refuses a public key that can never verify a signature, before calling Discord")
    void refusesAMalformedKey() {
        Outcome<BotIdentity> outcome = connector.verifyBot("t", 1L, "my-app-key");

        assertThat(outcome.error()).contains("64 hexadecimal");
        verify(calls, never()).call(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("refuses a user token: a user cannot post buttons in a channel")
    void refusesAUserToken() {
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_USER, Map.of("id", "1", "username", "me"));

        assertThat(connector.verifyBot("t", 1L, KEY).error()).contains("bot token");
    }

    @Test
    @DisplayName("regression: a key that is not this bot's application key is refused, so nobody signs presses with their own")
    void refusesAKeyOfAnotherApplication() {
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_USER, Map.of("id", "9", "username", "ops", "bot", true));
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_APPLICATION, Map.of("id", "9", "verify_key", "b".repeat(64)));

        assertThat(connector.verifyBot("t", 1L, KEY).error()).contains("not this bot's application key");
    }

    @Test
    @DisplayName("an installation whose catalog lacks the application call refuses the connect and says how to fix it")
    void refusesWhenTheCatalogLacksTheCall() {
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_USER, Map.of("id", "9", "username", "ops", "bot", true));
        when(calls.call(eq("discord"), eq(DiscordChannelConnector.TOOL_GET_CURRENT_APPLICATION), any(), anyString(),
                anyLong())).thenReturn(Outcome.failed("Tool not found: discord/discord-get-current-application"));

        Outcome<BotIdentity> outcome = connector.verifyBot("t", 1L, KEY);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).contains("Tool not found").contains("API catalog");
    }

    @Test
    @DisplayName("an application answer without its key is a refusal, never a pass")
    void refusesWhenTheKeyCannotBeChecked() {
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_USER, Map.of("id", "9", "username", "ops", "bot", true));
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_APPLICATION, Map.of("id", "9"));

        assertThat(connector.verifyBot("t", 1L, KEY).ok()).isFalse();
    }

    @Test
    @DisplayName("no message text can ping a whole server: every send and edit parses no mentions")
    @SuppressWarnings("unchecked")
    void noMassMentions() {
        answers(DiscordChannelConnector.TOOL_SEND_MESSAGE, Map.of("id", "M1"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        connector.sendDecisionRequest("t", 1L, "C1", "@everyone deploy?", "lcapr:x:a", "lcapr:x:r");

        verify(calls).call(eq("discord"), eq(DiscordChannelConnector.TOOL_SEND_MESSAGE), params.capture(), eq("t"), eq(1L));
        assertThat(params.getValue()).containsEntry("allowed_mentions", Map.of("parse", List.of()));
    }

    @Test
    @DisplayName("accepts the bot token and stores the key lowercase, which is how it is compared")
    void acceptsABot() {
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_USER, Map.of("id", "9", "username", "ops", "bot", true));
        answers(DiscordChannelConnector.TOOL_GET_CURRENT_APPLICATION, Map.of("id", "9", "verify_key", KEY));

        assertThat(connector.verifyBot("t", 1L, KEY.toUpperCase()).value().providerId()).isEqualTo("9");
        assertThat(connector.newInboundKey(" " + KEY.toUpperCase() + " ", "old")).isEqualTo(KEY);
        assertThat(connector.newInboundKey(null, "old")).isEqualTo("old");
        assertThat(connector.storedAccountSetting("9", KEY)).isEqualTo(KEY);
    }

    @Test
    @DisplayName("discover lists the text channels of every server the bot is in, and nothing else")
    void discoverListsTextChannels() {
        answers(DiscordChannelConnector.TOOL_LIST_GUILDS, List.of(Map.of("id", "G1", "name", "Acme")));
        answers(DiscordChannelConnector.TOOL_GET_GUILD_CHANNELS, List.of(
                Map.of("id", "C1", "name", "ops", "type", 0),
                Map.of("id", "V1", "name", "voice", "type", 2),
                Map.of("id", "K1", "name", "category", "type", 4)));

        List<ChatCandidate> chats = connector.discoverChats("t", 1L).value();

        assertThat(chats).extracting(ChatCandidate::chatId).containsExactly("C1");
        assertThat(chats.get(0).title()).isEqualTo("Acme / #ops");
    }

    @Test
    @DisplayName("buttons are laid out five to a row, each carrying our payload as its custom_id")
    @SuppressWarnings("unchecked")
    void componentsAreRowsOfFive() {
        List<ChoiceOption> options = IntStream.range(0, 7)
                .mapToObj(i -> new ChoiceOption("Option " + i, "lcask:tok:o" + i)).toList();

        List<Map<String, Object>> rows = DiscordChannelConnector.componentsOf(options);

        assertThat(rows).hasSize(2);
        List<Map<String, Object>> first = (List<Map<String, Object>>) rows.get(0).get("components");
        assertThat(first).hasSize(5);
        assertThat(first.get(0)).containsEntry("custom_id", "lcask:tok:o0").containsEntry("type", 2);
    }

    @Test
    @DisplayName("closing edits the message with the verdict and an empty component list, which removes the buttons")
    @SuppressWarnings("unchecked")
    void closingRemovesTheButtons() {
        answers(DiscordChannelConnector.TOOL_EDIT_MESSAGE, Map.of("id", "M1"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        assertThat(connector.closeDecisionRequest("t", 1L, "C1", "M1", "Deploy?", "Approved").ok()).isTrue();

        verify(calls).call(eq("discord"), eq(DiscordChannelConnector.TOOL_EDIT_MESSAGE), params.capture(),
                eq("t"), eq(1L));
        assertThat(params.getValue()).containsEntry("components", List.of())
                .containsEntry("content", "Deploy?\n\nApproved");
    }

    @Test
    @DisplayName("a late acknowledgement is an ephemeral follow-up on the interaction's own webhook")
    @SuppressWarnings("unchecked")
    void lateAcknowledgementIsAFollowUp() {
        answers(DiscordChannelConnector.TOOL_EXECUTE_WEBHOOK, Map.of("id", "F1"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        connector.ackButton("t", 1L, "app-1:itoken", "Approved", false);

        verify(calls).call(eq("discord"), eq(DiscordChannelConnector.TOOL_EXECUTE_WEBHOOK), params.capture(),
                eq("t"), eq(1L));
        assertThat(params.getValue()).containsEntry("webhook_id", "app-1").containsEntry("webhook_token", "itoken")
                .containsEntry("flags", 64);
    }

    @Test
    @DisplayName("no interaction to follow up on is a failure, not a call with half an address")
    void acknowledgementWithoutInteraction() {
        assertThat(connector.ackButton("t", 1L, "app-1:", "Approved", false).ok()).isFalse();
        assertThat(connector.ackButton("t", 1L, null, "Approved", false).ok()).isFalse();
        assertThat(connector.ackButton("t", 1L, "app-1:tok", "  ", false).ok()).isTrue();
        verify(calls, never()).call(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("a sent decision is identified by the id Discord answers with")
    void sendReturnsTheMessageId() {
        answers(DiscordChannelConnector.TOOL_SEND_MESSAGE, Map.of("id", "M7"));

        assertThat(connector.sendDecisionRequest("t", 1L, "C1", "Deploy?", "lcapr:x:a", "lcapr:x:r").value())
                .isEqualTo("M7");
    }

    @Test
    @DisplayName("asks for the application's public key at connect, and gives the portal step afterwards")
    void setupIsGuided() {
        assertThat(connector.accountSettingLabel()).contains("Public Key");
        assertThat(connector.setupInstructions("https://x/approval-callback/discord/b1", KEY))
                .contains("Interactions Endpoint URL").contains("https://x/approval-callback/discord/b1");
        assertThat(connector.capabilities().acceptsTypedReplies()).isFalse();
    }
}
