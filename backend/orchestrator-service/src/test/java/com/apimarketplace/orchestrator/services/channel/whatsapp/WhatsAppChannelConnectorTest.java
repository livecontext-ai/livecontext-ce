package com.apimarketplace.orchestrator.services.channel.whatsapp;

import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

@DisplayName("WhatsAppChannelConnector")
class WhatsAppChannelConnectorTest {

    private static final String PHONE_ID = "106540352242922";

    private CatalogCalls calls;
    private ChatChannelBotRepository bots;
    private WhatsAppChannelConnector connector;

    @BeforeEach
    void setUp() {
        calls = mock(CatalogCalls.class);
        bots = mock(ChatChannelBotRepository.class);
        connector = new WhatsAppChannelConnector(calls, bots);
    }

    private void connected() {
        ChatChannelBotEntity bot = new ChatChannelBotEntity();
        bot.setBotIdentity(PHONE_ID);
        when(bots.findFirstByChannelAndCredentialId("whatsapp", 1L)).thenReturn(Optional.of(bot));
    }

    private void sendAnswers(Outcome<ExecutionResult> outcome) {
        when(calls.call(eq("whatsapp"), any(), any(), anyString(), anyLong())).thenReturn(outcome);
    }

    private static Outcome<ExecutionResult> sent(String id) {
        return Outcome.of(new ExecutionResult(true, Map.of("messages", List.of(Map.of("id", id))), List.of(), List.of()));
    }

    @Test
    @DisplayName("a number is stored as the bare digits Meta sends back, however it was typed")
    void numbersAreNormalized() {
        assertThat(connector.normalizeChatId("+33 6 12-34-56-78")).isEqualTo("33612345678");
        assertThat(connector.normalizeChatId("0033612345678")).isEqualTo("33612345678");
        assertThat(connector.normalizeChatId("call me")).isEmpty();
    }

    @Test
    @DisplayName("sends from the number the bot row was connected with, to the destination's digits")
    @SuppressWarnings("unchecked")
    void sendsFromTheConnectedNumber() {
        connected();
        sendAnswers(sent("wamid.1"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        Outcome<String> result = connector.sendDecisionRequest("t", 1L, "33612345678", "Deploy?", "lcapr:x:a", "lcapr:x:r");

        assertThat(result.value()).isEqualTo("wamid.1");
        verify(calls).call(eq("whatsapp"), eq(WhatsAppChannelConnector.TOOL_SEND_INTERACTIVE), params.capture(),
                eq("t"), eq(1L));
        assertThat(params.getValue()).containsEntry("phone_number_id", PHONE_ID).containsEntry("to", "33612345678")
                .containsEntry("messaging_product", "whatsapp");
    }

    @Test
    @DisplayName("without a connected row there is no sending number, so nothing is called")
    void refusesWithoutAConnectedNumber() {
        Outcome<Void> test = connector.sendTest("t", 1L, "33612345678", "hello");

        assertThat(test.error()).isEqualTo(WhatsAppChannelConnector.NOT_CONNECTED);
        verify(calls, never()).call(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("three choices or fewer are reply buttons; more are a list")
    @SuppressWarnings("unchecked")
    void buttonsThenList() {
        List<ChoiceOption> three = options(3);
        Map<String, Object> buttons = WhatsAppChannelConnector.interactiveOf("Pick", three);
        assertThat(buttons).containsEntry("type", "button");
        List<Map<String, Object>> replies = (List<Map<String, Object>>) ((Map<String, Object>) buttons.get("action")).get("buttons");
        assertThat(replies).hasSize(3);
        assertThat((Map<String, Object>) replies.get(0).get("reply")).containsEntry("id", "lcask:tok:o0");

        Map<String, Object> list = WhatsAppChannelConnector.interactiveOf("Pick", options(4));
        assertThat(list).containsEntry("type", "list");
    }

    @Test
    @DisplayName("a button title is cut to WhatsApp's 20 characters, since Meta refuses a longer one")
    @SuppressWarnings("unchecked")
    void longTitlesAreCut() {
        Map<String, Object> interactive = WhatsAppChannelConnector.interactiveOf("Pick",
                List.of(new ChoiceOption("An option with a very long label", "lcask:tok:o0")));
        Map<String, Object> reply = (Map<String, Object>) ((List<Map<String, Object>>)
                ((Map<String, Object>) interactive.get("action")).get("buttons")).get(0).get("reply");

        assertThat((String) reply.get("title")).hasSize(20);
    }

    @Test
    @DisplayName("more than ten choices is refused before sending: WhatsApp cannot show them")
    void tooManyChoicesIsRefused() {
        connected();

        assertThat(connector.sendChoiceRequest("t", 1L, "336", "Pick", options(11)).error()).contains("10");
        verify(calls, never()).call(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("outside the 24-hour window the person is told what to do, not given a Meta code")
    void explainsTheWindow() {
        connected();
        sendAnswers(Outcome.failed("(#131047) Re-engagement message"));

        assertThat(connector.sendTest("t", 1L, "336", "hi").error()).contains("24 hours").doesNotContain("131047");
        assertThat(WhatsAppChannelConnector.explain("(#131030) Recipient not in allowed list")).contains("test recipients");
        assertThat(WhatsAppChannelConnector.explain("something else")).isEqualTo("something else");
    }

    @Test
    @DisplayName("closing is a follow-up quoting the original, since a WhatsApp message cannot be edited")
    @SuppressWarnings("unchecked")
    void closingIsAQuotedFollowUp() {
        connected();
        sendAnswers(sent("wamid.2"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        connector.closeDecisionRequest("t", 1L, "336", "wamid.1", "Deploy?", "Approved");

        verify(calls).call(eq("whatsapp"), eq(WhatsAppChannelConnector.TOOL_SEND_TEXT), params.capture(), eq("t"), eq(1L));
        assertThat(params.getValue()).containsEntry("context", Map.of("message_id", "wamid.1"));
        assertThat(connector.updateChoiceMarkup("t", 1L, "336", "wamid.1", "Q", List.of()).ok()).isTrue();
    }

    @Test
    @DisplayName("the acknowledgement replies to the press, addressed as phone|message id")
    @SuppressWarnings("unchecked")
    void acknowledgementRepliesToThePress() {
        connected();
        sendAnswers(sent("wamid.3"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        connector.ackButton("t", 1L, "33612345678|wamid.press", "Approved", false);

        verify(calls).call(eq("whatsapp"), eq(WhatsAppChannelConnector.TOOL_SEND_TEXT), params.capture(), eq("t"), eq(1L));
        assertThat(params.getValue()).containsEntry("to", "33612345678")
                .containsEntry("context", Map.of("message_id", "wamid.press"));
    }

    @Test
    @DisplayName("the verify token is generated once and kept on reconnect, since it is pasted in the Meta app")
    void verifyTokenIsStable() {
        String generated = connector.newInboundKey(PHONE_ID, null);

        assertThat(generated).hasSizeGreaterThanOrEqualTo(24);
        assertThat(connector.newInboundKey(PHONE_ID, generated)).isEqualTo(generated);
        assertThat(connector.newInboundKey(PHONE_ID, null)).isNotEqualTo(generated);
        assertThat(connector.storedAccountSetting(PHONE_ID, generated)).isEqualTo(PHONE_ID);
    }

    @Test
    @DisplayName("verifying needs the phone number id and reads the number's display name")
    void verifyNeedsThePhoneNumberId() {
        assertThat(connector.verifyBot("t", 1L).error()).contains("phone number id");
        when(calls.call(eq("whatsapp"), eq(WhatsAppChannelConnector.TOOL_GET_PHONE_NUMBER), any(), anyString(), anyLong()))
                .thenReturn(Outcome.of(new ExecutionResult(true,
                        Map.of("display_phone_number", "+1 555 0100", "verified_name", "Acme"), List.of(), List.of())));

        assertThat(connector.verifyBot("t", 1L, PHONE_ID).value().providerId()).isEqualTo(PHONE_ID);
        assertThat(connector.setupInstructions("https://x/approval-callback/whatsapp/b1", "tok"))
                .contains("https://x/approval-callback/whatsapp/b1").contains("tok").contains("messages");
    }

    private static List<ChoiceOption> options(int count) {
        return IntStream.range(0, count).mapToObj(i -> new ChoiceOption("Option " + i, "lcask:tok:o" + i)).toList();
    }

    @Test
    @DisplayName("regression: two long options that start alike still get distinct titles, which Meta requires")
    void titlesStayUnique() {
        List<ChoiceOption> alike = List.of(new ChoiceOption("Deploy to production now", "lcask:t:o0"),
                new ChoiceOption("Deploy to production later", "lcask:t:o1"),
                new ChoiceOption("Deploy to production never", "lcask:t:o2"));

        List<String> titles = WhatsAppChannelConnector.uniqueTitles(alike, WhatsAppChannelConnector.BUTTON_TITLE_MAX);

        assertThat(titles).doesNotHaveDuplicates().allSatisfy(t -> assertThat(t).hasSizeLessThanOrEqualTo(20));
        assertThat(titles.get(1)).endsWith(" 2");
    }
}
