package com.apimarketplace.orchestrator.services.channel.telegram;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChatCandidate;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TelegramChannelConnector}, focused on the provider rules
 * that make a connect silently useless when they are missed: the webhook/updates
 * conflict, and reading a group's name out of a shape where only a private chat
 * carries a first name.
 */
class TelegramChannelConnectorTest {

    private static final String TENANT = "42";

    private ToolsGateway gateway;
    /** A field, so a case can build a second connector with a different secret. */
    private ObjectProvider<ToolsGateway> provider;
    private TelegramChannelConnector connector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gateway = mock(ToolsGateway.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        connector = new TelegramChannelConnector(provider, "shh");
    }

    private static ExecutionResult ok(Map<String, Object> output) {
        return new ExecutionResult(true, output, List.of(), List.of());
    }

    private static ExecutionResult failed(String message) {
        return new ExecutionResult(false, Map.of(), List.of(Map.of("message", message)), List.of());
    }

    private void answers(String toolId, ExecutionResult result) {
        when(gateway.executeTool(eq(new ToolRef(toolId, 1)), any(), anyString(), any())).thenReturn(result);
    }

    @Nested
    @DisplayName("verifyBot()")
    class VerifyBot {

        @Test
        @DisplayName("reads the identity the provider reports")
        void readsIdentity() {
            answers("telegram/telegram-get-me", ok(Map.of("ok", true,
                    "result", Map.of("id", 77L, "username", "ops_bot", "first_name", "Ops"))));

            var identity = connector.verifyBot(TENANT, 9L);

            assertThat(identity.ok()).isTrue();
            assertThat(identity.value().username()).isEqualTo("ops_bot");
            assertThat(identity.value().providerId()).isEqualTo("77");
        }

        @Test
        @DisplayName("refuses an answer carrying no bot, rather than storing a nameless one")
        void refusesAnswerWithoutBot() {
            answers("telegram/telegram-get-me", ok(Map.of("ok", true)));

            var identity = connector.verifyBot(TENANT, 9L);

            assertThat(identity.ok()).isFalse();
            assertThat(identity.error()).contains("without a bot identity");
        }

        @Test
        @DisplayName("relays the provider's own wording on a refusal")
        void relaysProviderError() {
            answers("telegram/telegram-get-me", failed("Unauthorized"));

            assertThat(connector.verifyBot(TENANT, 9L).error()).isEqualTo("Unauthorized");
        }
    }

    @Nested
    @DisplayName("currentWebhookUrl()")
    class CurrentWebhook {

        @Test
        @DisplayName("an empty url means the slot is free, not that the call failed")
        void blankUrlIsEmpty() {
            answers("telegram/telegram-get-webhook-info", ok(Map.of("ok", true, "result", Map.of("url", ""))));

            Outcome<Optional<String>> result = connector.currentWebhookUrl(TENANT, 9L);

            assertThat(result.ok()).isTrue();
            assertThat(result.value()).isEmpty();
        }

        @Test
        @DisplayName("reports the url a bot is already pointed at")
        void reportsExistingUrl() {
            answers("telegram/telegram-get-webhook-info",
                    ok(Map.of("ok", true, "result", Map.of("url", "https://other.example.org/hook"))));

            assertThat(connector.currentWebhookUrl(TENANT, 9L).value())
                    .contains("https://other.example.org/hook");
        }
    }

    @Nested
    @DisplayName("applyWebhook()")
    class ApplyWebhook {

        @Test
        @DisplayName("sends the shared secret and narrows the update kinds")
        void sendsSecretAndAllowedUpdates() {
            answers("telegram/telegram-set-webhook", ok(Map.of("ok", true, "result", true)));

            connector.applyWebhook(TENANT, 9L, "https://app.example.com/approval-callback/telegram");

            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(gateway).executeTool(
                    eq(new ToolRef("telegram/telegram-set-webhook", 1)), params.capture(), anyString(), any());
            assertThat(params.getValue()).containsEntry("secret_token", "shh");
            // callback_query is the button press: without it the whole point is lost.
            assertThat((List<Object>) params.getValue().get("allowed_updates")).contains("callback_query");
        }

        @Test
        @DisplayName("omits the secret when none is configured, instead of sending a blank one")
        void omitsBlankSecret() {
            answers("telegram/telegram-set-webhook", ok(Map.of("ok", true, "result", true)));

            // A deployment that never set the property. Sending secret_token="" would make
            // Telegram echo a blank header that the inbound check then compares against.
            new TelegramChannelConnector(provider, "")
                    .applyWebhook(TENANT, 9L, "https://app.example.com/approval-callback/telegram");

            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(gateway).executeTool(
                    eq(new ToolRef("telegram/telegram-set-webhook", 1)), params.capture(), anyString(), any());
            assertThat(params.getValue()).doesNotContainKey("secret_token");
        }
    }

    @Nested
    @DisplayName("discoverChats()")
    class DiscoverChats {

        @Test
        @DisplayName("explains the webhook conflict instead of passing Telegram's wording through")
        void explainsWebhookConflict() {
            answers("telegram/telegram-get-updates",
                    failed("409 Conflict: can't use getUpdates method while webhook is active"));

            Outcome<List<ChatCandidate>> result = connector.discoverChats(TENANT, 9L);

            assertThat(result.ok()).isFalse();
            // Not a failure: Telegram keeps no list in this state, and the caller offers the id instead.
            assertThat(result.unavailable()).isTrue();
            // Says what to do next, in the words the person needs: the id to give and where to get it.
            assertThat(result.error()).contains("the bot is already connected")
                    .contains("@userinfobot")
                    .contains("-100")
                    .doesNotContain("Disconnect the webhook");
        }

        @Test
        @DisplayName("any other failure of the list stays a failure, not a list that is unavailable")
        void otherFailureIsNotUnavailable() {
            answers("telegram/telegram-get-updates", failed("429 Too Many Requests: retry after 5"));

            Outcome<List<ChatCandidate>> result = connector.discoverChats(TENANT, 9L);

            assertThat(result.ok()).isFalse();
            assertThat(result.unavailable()).isFalse();
            assertThat(result.error()).contains("429");
        }

        @Test
        @DisplayName("names a group by its title, which only exists on a group")
        void readsGroupTitle() {
            answers("telegram/telegram-get-updates", ok(Map.of("ok", true, "result", List.of(
                    Map.of("update_id", 1, "message", Map.of(
                            "chat", Map.of("id", -100123L, "title", "Ops room", "type", "group"),
                            "from", Map.of("username", "lea")))))));

            List<ChatCandidate> chats = connector.discoverChats(TENANT, 9L).value();

            assertThat(chats).singleElement()
                    .extracting(ChatCandidate::chatId, ChatCandidate::title, ChatCandidate::type)
                    .containsExactly("-100123", "Ops room", "group");
        }

        @Test
        @DisplayName("names a private chat from the person's name")
        void readsPrivateChatName() {
            answers("telegram/telegram-get-updates", ok(Map.of("ok", true, "result", List.of(
                    Map.of("update_id", 1, "message", Map.of(
                            "chat", Map.of("id", 555L, "first_name", "Lea", "last_name", "Roy",
                                    "type", "private")))))));

            assertThat(connector.discoverChats(TENANT, 9L).value())
                    .singleElement()
                    .extracting(ChatCandidate::title)
                    .isEqualTo("Lea Roy");
        }

        @Test
        @DisplayName("offers each chat once however many messages it sent")
        void deduplicatesChats() {
            Map<String, Object> chat = Map.of("id", 555L, "first_name", "Lea", "type", "private");
            answers("telegram/telegram-get-updates", ok(Map.of("ok", true, "result", List.of(
                    Map.of("update_id", 1, "message", Map.of("chat", chat)),
                    Map.of("update_id", 2, "message", Map.of("chat", chat)),
                    Map.of("update_id", 3, "edited_message", Map.of("chat", chat))))));

            assertThat(connector.discoverChats(TENANT, 9L).value()).hasSize(1);
        }

        @Test
        @DisplayName("ignores updates that carry no chat")
        void ignoresChatlessUpdates() {
            answers("telegram/telegram-get-updates", ok(Map.of("ok", true, "result", List.of(
                    Map.of("update_id", 1, "poll", Map.of("id", "x")),
                    Map.of("update_id", 2, "message", Map.of("text", "hi"))))));

            assertThat(connector.discoverChats(TENANT, 9L).value()).isEmpty();
        }
    }

    @Nested
    @DisplayName("sendDecisionRequest()")
    class SendDecisionRequest {

        @Test
        @DisplayName("carries both buttons with the payloads that identify the answer")
        void carriesBothButtons() {
            answers("telegram/telegram-send-message",
                    ok(Map.of("ok", true, "result", Map.of("message_id", 555))));

            Outcome<String> sent = connector.sendDecisionRequest(TENANT, 9L, "-100123", "May I publish?",
                    "lcaut:tok:a", "lcaut:tok:r");

            assertThat(sent.value()).isEqualTo("555");
            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(gateway).executeTool(
                    eq(new ToolRef("telegram/telegram-send-message", 1)), params.capture(), anyString(), any());
            Map<String, Object> markup = (Map<String, Object>) params.getValue().get("reply_markup");
            List<List<Map<String, Object>>> keyboard =
                    (List<List<Map<String, Object>>>) markup.get("inline_keyboard");
            assertThat(keyboard.get(0)).hasSize(2);
            assertThat(keyboard.get(0).get(0)).containsEntry("callback_data", "lcaut:tok:a");
            assertThat(keyboard.get(0).get(1)).containsEntry("callback_data", "lcaut:tok:r");
        }

        @Test
        @DisplayName("holds room back so the verdict still fits after the longest body")
        void holdsRoomForTheVerdict() {
            answers("telegram/telegram-send-message",
                    ok(Map.of("ok", true, "result", Map.of("message_id", 1))));

            connector.sendDecisionRequest(TENANT, 9L, "-1", "x".repeat(6000), "lcaut:t:a", "lcaut:t:r");

            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(gateway).executeTool(
                    eq(new ToolRef("telegram/telegram-send-message", 1)), params.capture(), anyString(), any());
            // A message sent at exactly the cap leaves the closing edit nothing to add, so the
            // person never learns what was decided. Asserted against the cap this connector
            // ADVERTISES rather than a copy of the arithmetic: a second copy of the number is
            // how the reserve and the cap drift apart, and VerdictReserve below is what checks
            // the number is big enough for the verdicts that exist.
            assertThat((String) params.getValue().get("text"))
                    .hasSize(connector.maxDecisionTextChars());
        }

        @Test
        @DisplayName("reports no message id rather than pretending it has one")
        void missingMessageId() {
            // A null id makes every later close and expire a silent no-op, so the
            // buttons stay live forever: the caller has to be able to see it.
            answers("telegram/telegram-send-message", ok(Map.of("ok", true)));

            assertThat(connector.sendDecisionRequest(TENANT, 9L, "-1", "hi", "a", "r").value()).isNull();
        }
    }

    @Nested
    @DisplayName("the send cap leaves room for every verdict the close can append")
    class VerdictReserve {

        @Test
        @DisplayName("a body at the cap plus the longest verdict still fits Telegram's limit")
        void theReserveCoversTheLongestVerdict() {
            // The reserve exists so the closing edit can append the verdict without the provider
            // cap eating it. It was a picked number (64) and the expiry verdict is 70 characters,
            // so the line the reserve was invented to protect was the line it truncated. Derived
            // from the real strings now, and asserted here so the next verdict that grows fails a
            // test instead of losing its tail in somebody's chat.
            // Every member of the closed type, which is the only thing a close site can pass.
            // Three earlier shapes of this test all went stale: three strings copied here missed
            // two real verdicts; a hand-written list in the service moved the drift one file
            // over; and deriving that list by reflection still let a call site pass a string
            // that was never a constant at all. An enum leaves nothing else to pass.
            int separator = "\n\n".length();

            for (AgentAuthorizationChannelService.Verdict verdict
                    : AgentAuthorizationChannelService.Verdict.values()) {
                assertThat(connector.maxDecisionTextChars() + separator + verdict.line().length())
                        .as("a body at the cap plus %s must fit Telegram's 4096", verdict)
                        .isLessThanOrEqualTo(4096);
            }
        }

        @Test
        @DisplayName("the cap it advertises is the cap it applies")
        void advertisedCapIsTheAppliedCap() {
            answers("telegram/telegram-send-message", ok(Map.of("ok", true,
                    "result", Map.of("message_id", 555))));
            String tooLong = "x".repeat(connector.maxDecisionTextChars() + 500);

            connector.sendDecisionRequest(TENANT, 9L, "-100123", tooLong, "a", "r");

            // The caller stores the body it was told this connector accepts. If the send capped
            // to a different number, the stored body would not be the sent body and the closing
            // edit would be built from a message nobody saw, which is the drift
            // maxDecisionTextChars() exists to make impossible.
            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(gateway).executeTool(
                    eq(new ToolRef("telegram/telegram-send-message", 1)), params.capture(),
                    anyString(), any());
            assertThat((String) params.getValue().get("text"))
                    .hasSize(connector.maxDecisionTextChars());
        }
    }

    @Nested
    @DisplayName("closeDecisionRequest()")
    class CloseDecisionRequest {

        @Test
        @DisplayName("appends the verdict and takes the buttons away")
        void appendsVerdictAndStripsButtons() {
            answers("telegram/telegram-edit-message-text", ok(Map.of("ok", true)));

            connector.closeDecisionRequest(TENANT, 9L, "-100123", "555", "May I publish?", "✅ Approved");

            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(gateway).executeTool(
                    eq(new ToolRef("telegram/telegram-edit-message-text", 1)), params.capture(),
                    anyString(), any());
            assertThat((String) params.getValue().get("text")).contains("May I publish?").contains("✅ Approved");
            Map<String, Object> markup = (Map<String, Object>) params.getValue().get("reply_markup");
            // An EMPTY keyboard is how buttons are removed; omitting the field leaves
            // the original ones live on a question that is already settled.
            assertThat((List<?>) markup.get("inline_keyboard")).isEmpty();
        }

        @Test
        @DisplayName("says there is nothing to close instead of calling with a null id")
        void refusesWithoutMessageId() {
            Outcome<Void> closed = connector.closeDecisionRequest(TENANT, 9L, "-1", null, "body", "verdict");

            assertThat(closed.ok()).isFalse();
            org.mockito.Mockito.verifyNoInteractions(gateway);
        }
    }

    @Nested
    @DisplayName("ackButton()")
    class AckButton {

        @Test
        @DisplayName("does nothing when there is no press to acknowledge")
        void noEventId() {
            assertThat(connector.ackButton(TENANT, 9L, null, "Approved", false).ok()).isTrue();
            org.mockito.Mockito.verifyNoInteractions(gateway);
        }

        @Test
        @DisplayName("raises an alert only when asked")
        void alertIsOptional() {
            answers("telegram/telegram-answer-callback-query", ok(Map.of("ok", true)));

            connector.ackButton(TENANT, 9L, "cbq-1", "Not allowed", true);

            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(gateway).executeTool(
                    eq(new ToolRef("telegram/telegram-answer-callback-query", 1)), params.capture(),
                    anyString(), any());
            assertThat(params.getValue()).containsEntry("show_alert", true);
        }
    }

    @Test
    @DisplayName("pins the bot strictly, so an unresolvable id never falls back to another")
    void pinsTheCredentialStrictly() {
        answers("telegram/telegram-get-me", ok(Map.of("ok", true, "result", Map.of("id", 1))));

        connector.verifyBot(TENANT, 9L);

        ArgumentCaptor<Map<String, Object>> credentials = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(gateway).executeTool(any(), any(), anyString(), credentials.capture());
        // Without strict, the catalog softens an id it cannot match into the
        // integration default: the row would then carry one credential and the webhook
        // that brings presses back would belong to a different bot.
        assertThat(credentials.getValue())
                .containsEntry("__selectedCredentialId__", 9L)
                .containsEntry("__credentialSelectionStrict__", true);
        // And no billing scope: verifying a bot is setup, not work somebody pays for.
        assertThat(credentials.getValue())
                .doesNotContainKeys("__streamId__", "__workflowRunId__", "__nodeId__");
    }

    @Test
    @DisplayName("reports a missing tools gateway as a sentence, never as a crash")
    @SuppressWarnings("unchecked")
    void missingGatewayIsReported() {
        ObjectProvider<ToolsGateway> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        Outcome<?> result = new TelegramChannelConnector(empty, "shh").verifyBot(TENANT, 9L);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("gateway is unavailable");
    }
}
