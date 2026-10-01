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
import org.mockito.InOrder;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", false, "error", "channel_not_found"));

        Outcome<String> sent = connector.sendDecisionRequest("t", 1L, "C1", "Deploy?", "lcapr:x:a", "lcapr:x:r");

        // Taken as success, the approval would be recorded as sent while nothing was posted.
        assertThat(sent.ok()).isFalse();
        assertThat(sent.error()).contains("cannot find that channel");
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
    @DisplayName("discover lists direct messages and every channel the app can post in, not private ones it is not in")
    void discoverKeepsOnlyPostableChats() {
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", true, "channels", List.of(
                Map.of("id", "C1", "name", "ops", "is_member", true),
                Map.of("id", "C2", "name", "secret", "is_member", false, "is_private", true),
                Map.of("id", "C3", "name", "about", "is_member", false, "is_private", false),
                Map.of("id", "D1", "is_im", true, "user", "U9"))));

        List<ChatCandidate> chats = connector.discoverChats("t", 1L).value();

        // A private channel the app is not in would be offered and then refuse the test message.
        // A public one is joined by the app when it is connected, so it is offered, after the
        // chats the app is already in.
        assertThat(chats).extracting(ChatCandidate::chatId).containsExactly("C1", "D1", "C3");
        assertThat(chats.get(0).title()).isEqualTo("#ops");
        assertThat(chats.get(1).fromUsername()).isEqualTo("U9");
    }

    @Test
    @DisplayName("regression: discover reads every page, so a channel past the first 200 is still offered")
    @SuppressWarnings("unchecked")
    void discoverFollowsTheCursor() {
        when(calls.call(eq("slack"), eq(SlackChannelConnector.TOOL_LIST_CONVERSATIONS), any(), anyString(), anyLong()))
                .thenReturn(slackAnswer(Map.of("ok", true,
                        "channels", List.of(Map.of("id", "C1", "name", "general", "is_member", true)),
                        "response_metadata", Map.of("next_cursor", "page2"))))
                .thenReturn(slackAnswer(Map.of("ok", true,
                        "channels", List.of(Map.of("id", "C2", "name", "ops", "is_member", true)))));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        List<ChatCandidate> chats = connector.discoverChats("t", 1L).value();

        assertThat(chats).extracting(ChatCandidate::chatId).containsExactly("C1", "C2");
        verify(calls, org.mockito.Mockito.times(2)).call(eq("slack"),
                eq(SlackChannelConnector.TOOL_LIST_CONVERSATIONS), params.capture(), eq("t"), eq(1L));
        assertThat(params.getAllValues().get(0)).doesNotContainKey("cursor");
        assertThat(params.getAllValues().get(1)).containsEntry("cursor", "page2");
    }

    @Test
    @DisplayName("a listing stops after its page cap even when Slack keeps handing out cursors")
    void listingIsBounded() {
        when(calls.call(eq("slack"), eq(SlackChannelConnector.TOOL_LIST_CONVERSATIONS), any(), anyString(), anyLong()))
                .thenReturn(slackAnswer(Map.of("ok", true, "channels", List.of(),
                        "response_metadata", Map.of("next_cursor", "again"))));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "#missing");

        assertThat(resolved.ok()).isFalse();
        verify(calls, org.mockito.Mockito.times(SlackChannelConnector.MAX_LIST_PAGES)).call(eq("slack"),
                eq(SlackChannelConnector.TOOL_LIST_CONVERSATIONS), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("a typed member id is connected as the direct message Slack opens with that person")
    @SuppressWarnings("unchecked")
    void memberIdResolvesToItsDirectMessage() {
        // A press on a DM carries the D... id; a row stored under the U... id would never match it.
        answers(SlackChannelConnector.TOOL_OPEN_CONVERSATION, Map.of("ok", true, "channel", Map.of("id", "D0C5QQPBZJQ")));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "U0C55P51Q8P");

        assertThat(resolved.value().chatId()).isEqualTo("D0C5QQPBZJQ");
        assertThat(resolved.value().type()).isEqualTo("private");
        verify(calls).call(eq("slack"), eq(SlackChannelConnector.TOOL_OPEN_CONVERSATION), params.capture(),
                eq("t"), eq(1L));
        assertThat(params.getValue()).containsEntry("users", "U0C55P51Q8P");
    }

    @Test
    @DisplayName("an @name is refused with the two ways Slack does accept a person")
    void atNameIsRefused() {
        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "@alice");

        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.error()).contains("chats the app can see").contains("member ID");
        verify(calls, never()).call(anyString(), any(), any(), anyString(), anyLong());
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

    private static Outcome<ExecutionResult> slackAnswer(Map<String, Object> body) {
        return Outcome.of(new ExecutionResult(true, body, List.of(), List.of()));
    }

    @Test
    @DisplayName("regression: a typed \"#tous-about\" is connected as the channel's id, with its name as the title")
    @SuppressWarnings("unchecked")
    void typedChannelNameResolvesToItsId() {
        // Prod 2026-09-30: the name was stored and posted as typed, Slack refused it, and the
        // person was told only "Slack refused the call." while the channel was right there.
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", true, "channels", List.of(
                Map.of("id", "C0C5ERWFF3M", "name", "nouveau-canal", "is_member", true),
                Map.of("id", "C0C6FDRGYDN", "name", "Tous-About", "is_member", false))));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "#tous-about");

        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.value().chatId()).isEqualTo("C0C6FDRGYDN");
        assertThat(resolved.value().title()).isEqualTo("#Tous-About");
        verify(calls).call(eq("slack"), eq(SlackChannelConnector.TOOL_LIST_CONVERSATIONS), params.capture(),
                eq("t"), eq(1L));
        // Direct messages have no name to match, and listing them would only cost a page.
        assertThat(params.getValue()).containsEntry("types", "public_channel,private_channel");
    }

    @Test
    @DisplayName("a name is matched without its # too, and on a later page when the first has no match")
    void nameIsFoundOnALaterPage() {
        when(calls.call(eq("slack"), eq(SlackChannelConnector.TOOL_LIST_CONVERSATIONS), any(), anyString(), anyLong()))
                .thenReturn(slackAnswer(Map.of("ok", true,
                        "channels", List.of(Map.of("id", "C1", "name", "general")),
                        "response_metadata", Map.of("next_cursor", "page2"))))
                .thenReturn(slackAnswer(Map.of("ok", true,
                        "channels", List.of(Map.of("id", "C2", "name", "ops")),
                        "response_metadata", Map.of("next_cursor", ""))));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "ops");

        assertThat(resolved.value().chatId()).isEqualTo("C2");
    }

    @Test
    @DisplayName("a typed id is kept as it is, without asking Slack")
    void typedIdIsKept() {
        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "C0C6FDRGYDN");

        assertThat(resolved.value().chatId()).isEqualTo("C0C6FDRGYDN");
        verify(calls, never()).call(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("a name typed in capitals is looked up by name, not taken for an id")
    void capitalisedNameIsLookedUp() {
        // "GENERAL" has the shape of a channel id but no digit: Slack ids always carry one.
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", true,
                "channels", List.of(Map.of("id", "C0GENERAL1", "name", "general"))));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "GENERAL");

        assertThat(resolved.value().chatId()).isEqualTo("C0GENERAL1");
    }

    @Test
    @DisplayName("a name that matches no channel the app can see is refused with a sentence that says what to do")
    void unknownNameIsRefused() {
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", true,
                "channels", List.of(Map.of("id", "C1", "name", "general"))));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "#secret");

        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.error()).contains("#secret").contains("chats the app can see").contains("/invite");
    }

    @Test
    @DisplayName("a refused listing is reported as Slack explained it, not as an unknown name")
    void refusedListingIsReported() {
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", false, "error", "missing_scope"));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "#ops");

        assertThat(resolved.error()).contains("lacks a permission");
    }

    @Test
    @DisplayName("regression: connecting a channel joins it BEFORE the test message, so the post never meets not_in_channel")
    void connectJoinsThePublicChannelFirst() {
        // The join does not depend on reading Slack's error code: until the catalog declares it,
        // a post-then-join-on-refusal design could not see the refusal at all.
        answers(SlackChannelConnector.TOOL_JOIN_CONVERSATION, Map.of("ok", true));
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", true, "ts", "1700.02"));

        Outcome<Void> sent = connector.sendTest("t", 1L, "C0C6FDRGYDN", "hello");

        assertThat(sent.ok()).isTrue();
        InOrder order = inOrder(calls);
        order.verify(calls).call(eq("slack"), eq(SlackChannelConnector.TOOL_JOIN_CONVERSATION),
                eq(Map.of("channel", "C0C6FDRGYDN")), eq("t"), eq(1L));
        order.verify(calls).call(eq("slack"), eq(SlackChannelConnector.TOOL_POST_MESSAGE), any(), eq("t"), eq(1L));
    }

    @Test
    @DisplayName("a channel the app cannot join still gets its test message, whose refusal says to invite the app")
    void unjoinableChannelStillPostsAndExplains() {
        answers(SlackChannelConnector.TOOL_JOIN_CONVERSATION, Map.of("ok", false,
                "error", "method_not_supported_for_channel_type"));
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", false, "error", "not_in_channel"));

        Outcome<Void> sent = connector.sendTest("t", 1L, "C1PRIVATE", "hello");

        assertThat(sent.ok()).isFalse();
        assertThat(sent.error()).contains("/invite");
    }

    @Test
    @DisplayName("a direct message is never joined, it is posted to")
    void directMessageIsNotJoined() {
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", true, "ts", "1700.03"));

        connector.sendTest("t", 1L, "D0C5QQPBZJQ", "hello");

        verify(calls, never()).call(eq("slack"), eq(SlackChannelConnector.TOOL_JOIN_CONVERSATION), any(),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("an approval or a question never joins: an app removed from a channel stays removed")
    void decisionsNeverJoin() {
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", false, "error", "not_in_channel"));

        Outcome<String> sent = connector.sendDecisionRequest("t", 1L, "C1", "Deploy?", "lcapr:x:a", "lcapr:x:r");

        assertThat(sent.error()).contains("/invite");
        verify(calls, never()).call(eq("slack"), eq(SlackChannelConnector.TOOL_JOIN_CONVERSATION), any(),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("any other refusal of the test message is reported as Slack gave it")
    void otherRefusalIsReported() {
        answers(SlackChannelConnector.TOOL_JOIN_CONVERSATION, Map.of("ok", true));
        answers(SlackChannelConnector.TOOL_POST_MESSAGE, Map.of("ok", false, "error", "is_archived"));

        Outcome<Void> sent = connector.sendTest("t", 1L, "C1", "hello");

        assertThat(sent.error()).isEqualTo("Slack refused the call: is_archived.");
    }

    @Test
    @DisplayName("a name is also matched against Slack's normalized name")
    void nameNormalizedMatches() {
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", true, "channels", List.of(
                Map.of("id", "C7", "name", "Équipe-Ops", "name_normalized", "equipe-ops"))));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "#equipe-ops");

        assertThat(resolved.value().chatId()).isEqualTo("C7");
    }

    @Test
    @DisplayName("a member id Slack will not open a conversation with is refused with Slack's reason")
    void memberIdOpenRefused() {
        answers(SlackChannelConnector.TOOL_OPEN_CONVERSATION, Map.of("ok", false, "error", "user_not_found"));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "U0NOBODY1");

        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.error()).contains("user_not_found");
    }

    @Test
    @DisplayName("an open that names no conversation is refused rather than stored under the member id")
    void memberIdOpenWithoutChannel() {
        answers(SlackChannelConnector.TOOL_OPEN_CONVERSATION, Map.of("ok", true));

        Outcome<ChatCandidate> resolved = connector.resolveDestination("t", 1L, "U0C55P51Q8P");

        assertThat(resolved.ok()).isFalse();
        assertThat(resolved.error()).contains("U0C55P51Q8P");
    }

    @Test
    @DisplayName("discover puts the app's own channels and direct messages first, and caps the list")
    @SuppressWarnings("unchecked")
    void discoverOrdersAndCaps() {
        List<Map<String, Object>> channels = new java.util.ArrayList<>();
        for (int i = 0; i < SlackChannelConnector.MAX_DISCOVERED_CHATS + 50; i++) {
            channels.add(Map.of("id", "CP" + i, "name", "public-" + i, "is_member", false, "is_private", false));
        }
        channels.add(Map.of("id", "CMEMBER", "name", "ops", "is_member", true));
        channels.add(Map.of("id", "DME", "is_im", true, "user", "U9"));
        answers(SlackChannelConnector.TOOL_LIST_CONVERSATIONS, Map.of("ok", true, "channels", channels));

        List<ChatCandidate> chats = connector.discoverChats("t", 1L).value();

        assertThat(chats).hasSize(SlackChannelConnector.MAX_DISCOVERED_CHATS);
        assertThat(chats).extracting(ChatCandidate::chatId).startsWith("CMEMBER", "DME");
    }

    @Test
    @DisplayName("without a signing secret, connect is told presses will be refused; with one, nothing is said")
    void inboundProblemFollowsTheSecret() {
        assertThat(new SlackChannelConnector(calls, http, "").inboundProblem()).contains("SLACK_SIGNING_SECRET");
        assertThat(new SlackChannelConnector(calls, http, null).inboundProblem()).isNotNull();
        assertThat(new SlackChannelConnector(calls, http, "secret").inboundProblem()).isNull();
    }
}
