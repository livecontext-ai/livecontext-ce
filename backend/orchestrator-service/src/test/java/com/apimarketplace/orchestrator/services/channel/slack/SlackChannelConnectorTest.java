package com.apimarketplace.orchestrator.services.channel.slack;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChatCandidate;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SlackChannelConnector")
class SlackChannelConnectorTest {

    private CatalogCalls calls;
    private RestTemplate http;
    private SlackChannelConnector connector;

    @BeforeEach
    void setUp() {
        calls = mock(CatalogCalls.class);
        http = mock(RestTemplate.class);
        connector = new SlackChannelConnector(calls, http);
    }

    private void answers(ToolRef tool, Map<String, Object> body) {
        when(calls.call(eq("slack"), eq(tool), any(), anyString(), anyLong()))
                .thenReturn(Outcome.of(new ExecutionResult(true, body, List.of(), List.of())));
    }

    @Test
    @DisplayName("Slack's ok:false on an HTTP 200 is a failure, explained in words the person can act on")
    void okFalseIsAFailure() {
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", false, "error", "not_in_channel"));

        Outcome<String> sent = connector.sendDecisionRequest("t", 1L, "C1", "Deploy?", "lcapr:x:a", "lcapr:x:r");

        // Taken as success, the approval would be recorded as sent while nothing was posted.
        assertThat(sent.ok()).isFalse();
        assertThat(sent.error()).contains("/invite");
    }

    @Test
    @DisplayName("an unknown Slack code is reported as Slack gave it")
    void unknownCodeIsPassedOn() {
        assertThat(SlackChannelConnector.explain("ratelimited")).isEqualTo("Slack refused the call: ratelimited.");
        assertThat(SlackChannelConnector.explain("token_revoked")).contains("Reconnect Slack");
    }

    @Test
    @DisplayName("a decision is a section plus one button per choice, each carrying our payload as its value")
    @SuppressWarnings("unchecked")
    void decisionBlocksCarryThePayloads() {
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", true, "ts", "1700.01"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        Outcome<String> sent = connector.sendDecisionRequest("t", 1L, "C1", "Deploy?", "lcapr:x:a", "lcapr:x:r");

        assertThat(sent.value()).isEqualTo("1700.01");
        verify(calls).call(eq("slack"), eq(SlackChannelConnector.TOOL_POST_MESSAGE), params.capture(), eq("t"), eq(1L));
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) params.getValue().get("blocks");
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) blocks.get(1).get("elements");
        assertThat(buttons).extracting(b -> b.get("value")).containsExactly("lcapr:x:a", "lcapr:x:r");
        // Slack refuses a block whose action_ids repeat.
        assertThat(buttons).extracting(b -> b.get("action_id")).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("closing keeps the body, removes the buttons and adds the verdict underneath")
    void closingRemovesTheButtons() {
        List<Map<String, Object>> blocks = SlackChannelConnector.blocksOf("Deploy?", List.of(), "Approved");

        assertThat(blocks).extracting(b -> b.get("type")).containsExactly("section", "context");
    }

    @Test
    @DisplayName("a long button label is shortened to Slack's 75 characters")
    @SuppressWarnings("unchecked")
    void longLabelIsCapped() {
        List<Map<String, Object>> blocks = SlackChannelConnector.blocksOf("Q",
                List.of(new ChoiceOption("x".repeat(100), "lcask:t:o0")), null);

        Map<String, Object> text = (Map<String, Object>) ((List<Map<String, Object>>) blocks.get(1).get("elements"))
                .get(0).get("text");
        assertThat((String) text.get("text")).hasSize(75).endsWith("...");
    }

    @Test
    @DisplayName("discover lists direct messages and the channels the app is in, not the others")
    void discoverKeepsOnlyPostableChats() {
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", true, "channels", List.of(
                Map.of("id", "C1", "name", "ops", "is_member", true),
                Map.of("id", "C2", "name", "secret", "is_member", false),
                Map.of("id", "D1", "is_im", true, "user", "U9"))));

        List<ChatCandidate> chats = connector.discoverChats("t", 1L).value();

        // A channel the app is not in would be offered and then refuse the test message.
        assertThat(chats).extracting(ChatCandidate::chatId).containsExactly("C1", "D1");
        assertThat(chats.get(0).title()).isEqualTo("#ops");
        assertThat(chats.get(1).fromUsername()).isEqualTo("U9");
    }

    @Test
    @DisplayName("verify reads the identity auth.test answers with")
    void verifyReadsTheIdentity() {
        answers(SlackChannelConnector.TOOL_AUTH_TEST, Map.of("ok", true, "user_id", "U1", "user", "ops", "team", "Acme"));

        assertThat(connector.verifyBot("t", 1L).value().providerId()).isEqualTo("U1");
    }

    @Test
    @DisplayName("acknowledges through a response_url only when it is Slack's own hook host")
    void refusesAResponseUrlThatIsNotSlacks() {
        // The URL comes out of an unauthenticated callback body: posting wherever it says would
        // make this endpoint a way to have the server call any address.
        Outcome<Void> refused = connector.ackButton("t", 1L, "http://169.254.169.254/latest", "Approved", false);

        assertThat(refused.ok()).isFalse();
        verify(http, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    @DisplayName("the acknowledgement is ephemeral and leaves the original message alone")
    @SuppressWarnings("unchecked")
    void acknowledgementIsEphemeral() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> sent = ArgumentCaptor.forClass(HttpEntity.class);

        Outcome<Void> ack = connector.ackButton("t", 1L, "https://hooks.slack.com/actions/T/1/x", "Approved", false);

        assertThat(ack.ok()).isTrue();
        verify(http).postForEntity(eq("https://hooks.slack.com/actions/T/1/x"), sent.capture(), eq(String.class));
        assertThat(sent.getValue().getBody())
                .containsEntry("response_type", "ephemeral")
                .containsEntry("replace_original", false)
                .containsEntry("text", "Approved");
    }

    @Test
    @DisplayName("declares what Slack cannot do: no typed replies, no per-bot webhook")
    void capabilities() {
        assertThat(connector.capabilities().acceptsTypedReplies()).isFalse();
        assertThat(connector.capabilities().managesWebhook()).isFalse();
        assertThat(connector.capabilities().editsMessages()).isTrue();
    }

    @Test
    @DisplayName("regression: text is escaped for mrkdwn, so an agent's <!channel> cannot notify a whole channel")
    void textIsEscaped() {
        assertThat(SlackChannelConnector.fallback("Deploy <!channel> & <@U1> now"))
                .isEqualTo("Deploy &lt;!channel&gt; &amp; &lt;@U1&gt; now");
    }

    @Test
    @DisplayName("a cap that falls inside an escaped entity drops it rather than send it broken")
    void capNeverSplitsAnEntity() {
        String text = "x".repeat(SlackChannelConnector.SECTION_TEXT_MAX_CHARS - 2) + "<tail";

        String capped = SlackChannelConnector.fallback(text);

        assertThat(capped).hasSizeLessThanOrEqualTo(SlackChannelConnector.SECTION_TEXT_MAX_CHARS).doesNotContain("&l");
    }

    @Test
    @DisplayName("without a signing secret, connect is told presses will be refused; with one, nothing is said")
    void inboundProblemFollowsTheSecret() {
        assertThat(new SlackChannelConnector(calls, http, "").inboundProblem()).contains("SLACK_SIGNING_SECRET");
        assertThat(new SlackChannelConnector(calls, http, null).inboundProblem()).isNotNull();
        assertThat(new SlackChannelConnector(calls, http, "secret").inboundProblem()).isNull();
    }
}
