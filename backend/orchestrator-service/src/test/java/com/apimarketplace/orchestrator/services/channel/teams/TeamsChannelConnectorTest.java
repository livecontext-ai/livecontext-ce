package com.apimarketplace.orchestrator.services.channel.teams;

import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChatCandidate;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TeamsChannelConnector")
class TeamsChannelConnectorTest {

    private CatalogCalls calls;
    private TeamsChannelConnector connector;

    @BeforeEach
    void setUp() {
        calls = mock(CatalogCalls.class);
        connector = new TeamsChannelConnector(calls, new ObjectMapper(), "https://app.example.com/");
    }

    private void answers(Map<String, Object> body) {
        when(calls.call(eq("teams"), any(), any(), anyString(), anyLong()))
                .thenReturn(Outcome.of(new ExecutionResult(true, body, List.of(), List.of())));
    }

    @Test
    @DisplayName("each choice is a link to this installation's decision page, carrying its payload")
    @SuppressWarnings("unchecked")
    void choicesAreDecisionLinks() {
        Map<String, Object> card = connector.cardOf("Deploy?", List.of(
                new ChoiceOption("Approve", "lcapr:tok:a"), new ChoiceOption("Reject", "lcapr:tok:r")));

        List<Map<String, Object>> actions = (List<Map<String, Object>>) card.get("actions");
        assertThat(actions).extracting(a -> a.get("type")).containsOnly("Action.OpenUrl");
        assertThat(actions.get(0).get("url"))
                .isEqualTo("https://app.example.com/approval-callback/teams/decide?p=lcapr%3Atok%3Aa");
    }

    @Test
    @DisplayName("sends an Adaptive Card attachment referenced from the message body")
    @SuppressWarnings("unchecked")
    void sendsACardAttachment() {
        answers(Map.of("id", "1700000000000"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        Outcome<String> sent = connector.sendDecisionRequest("t", 1L, "19:abc@thread.v2", "Deploy?", "lcapr:x:a", "lcapr:x:r");

        assertThat(sent.value()).isEqualTo("1700000000000");
        verify(calls).call(eq("teams"), eq(TeamsChannelConnector.TOOL_SEND_CHAT_MESSAGE), params.capture(), eq("t"), eq(1L));
        Map<String, Object> attachment = ((List<Map<String, Object>>) params.getValue().get("attachments")).get(0);
        assertThat(attachment).containsEntry("contentType", "application/vnd.microsoft.card.adaptive");
        // Graph only renders an attachment the body points at by id.
        assertThat((String) ((Map<String, Object>) params.getValue().get("body")).get("content"))
                .contains("<attachment id=\"" + attachment.get("id") + "\">");
        assertThat((String) attachment.get("content")).contains("Action.OpenUrl");
    }

    @Test
    @DisplayName("declares that a press does not identify its presser, and that it cannot edit or hear replies")
    void capabilities() {
        assertThat(connector.identifiesPresser()).isFalse();
        assertThat(connector.capabilities().editsMessages()).isFalse();
        assertThat(connector.capabilities().acceptsTypedReplies()).isFalse();
        assertThat(connector.credentialIntegration()).isEqualTo("microsoftteams");
    }

    @Test
    @DisplayName("discover titles a chat by its topic, else by the people in it")
    void discoverTitlesChats() {
        answers(Map.of("value", List.of(
                Map.of("id", "19:a", "topic", "Release crew", "chatType", "group"),
                Map.of("id", "19:b", "chatType", "oneOnOne", "members", List.of(
                        Map.of("displayName", "Ada"), Map.of("displayName", "Linus"))))));

        List<ChatCandidate> chats = connector.discoverChats("t", 1L).value();

        assertThat(chats).extracting(ChatCandidate::title).containsExactly("Release crew", "Ada, Linus");
    }

    @Test
    @DisplayName("closing is a follow-up naming what it closes, since the card's links stay on it")
    @SuppressWarnings("unchecked")
    void closingIsAFollowUp() {
        answers(Map.of("id", "2"));
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);

        connector.closeDecisionRequest("t", 1L, "19:a", "1", "Deploy   the\nrelease?", "Approved");

        verify(calls).call(eq("teams"), eq(TeamsChannelConnector.TOOL_SEND_CHAT_MESSAGE), params.capture(), eq("t"), eq(1L));
        assertThat(((Map<String, Object>) params.getValue().get("body")).get("content"))
                .isEqualTo("Approved: Deploy the release?");
    }

    @Test
    @DisplayName("verify proves the account by listing a chat and names it by its credential")
    void verifyNamesTheCredential() {
        answers(Map.of("value", List.of()));

        assertThat(connector.verifyBot("t", 42L).value().providerId()).isEqualTo("credential-42");
    }
}
