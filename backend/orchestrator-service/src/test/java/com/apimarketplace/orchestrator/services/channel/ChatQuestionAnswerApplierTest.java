package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Handing a chat answer back to the conversation that asked for it.
 *
 * <p>The half that matters is the one the app does not need. In the app, the frontend starts the
 * turn that reads an answer; from a phone there is no frontend, so if this does not start it the
 * answer is recorded and nothing ever acts on it. The person sees "Answered", the agent never
 * learns, and there is no error anywhere.
 */
@DisplayName("ChatQuestionAnswerApplier - getting the answer back to the agent")
class ChatQuestionAnswerApplierTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";
    private static final UUID AGENT_ID = UUID.randomUUID();

    private ConversationClient conversationClient;
    private AgentClient agentClient;
    private ChatQuestionAnswerApplier applier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversationClient = mock(ConversationClient.class);
        agentClient = mock(AgentClient.class);
        ObjectProvider<ConversationClient> conversations = mock(ObjectProvider.class);
        ObjectProvider<AgentClient> agents = mock(ObjectProvider.class);
        when(conversations.getIfAvailable()).thenReturn(conversationClient);
        when(agents.getIfAvailable()).thenReturn(agentClient);
        applier = new ChatQuestionAnswerApplier(conversations, agents);
    }

    @Test
    @DisplayName("a released park needs no follow-up turn, because the asking turn is still running")
    void releasedParkStartsNothing() {
        when(conversationClient.answerUserQuestion(anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(true);

        assertThat(applier.apply(List.of(row("Tone", "Formal")))).isTrue();

        // The turn that asked reads the answers as its own tool result and carries on. Starting
        // another would run the same turn twice.
        verify(conversationClient, never()).sendChatSync(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("nothing parked means somebody has to start the turn, and that is this")
    void startsTheFollowUpTurn() {
        when(conversationClient.answerUserQuestion(anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(false);

        assertThat(applier.apply(List.of(row("Tone", "Formal")))).isTrue();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(conversationClient).sendChatSync(eq(TENANT), eq("conv-1"), message.capture(),
                eq(AGENT_ID.toString()), any(), any(), source.capture(), any(), eq(ORG));
        // The same words the in-app path sends as the person's next message, so the agent reads
        // its answer identically whichever surface it came from.
        assertThat(message.getValue()).isEqualTo("Answer to Tone: Formal");
        // And marked as a run nobody is watching, so the turn's OWN next question goes back to
        // the chat instead of onto a screen with nobody in front of it.
        assertThat(source.getValue()).isEqualTo("CHANNEL_REPLY");
    }

    @Test
    @DisplayName("the call it answers comes from the gate key, never from the group key")
    void namesTheCallFromTheGateKey() {
        when(conversationClient.answerUserQuestion(anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(true);
        ChatAuthorizationRequestEntity row = row("Tone", "Formal");

        applier.apply(List.of(row));

        // The group key is minted per delivery precisely because a provider's tool call id is
        // not unique (Gemini numbers them call_0, call_1). Passing it here would name a call
        // the answer endpoint has never heard of.
        verify(conversationClient).answerUserQuestion(eq("conv-1"), eq(TENANT), eq(ORG),
                eq("call-1"), eq("call-1:ask"), any());
        assertThat(row.getGroupKey()).isNotEqualTo("call-1");
    }

    @Test
    @DisplayName("a gate key of another shape names no call, so nothing is applied")
    void refusesAGateKeyOfAnotherShape() {
        ChatAuthorizationRequestEntity row = row("Tone", "Formal");
        // An AUTHORIZATION park's key is the bare call id. The suffix is a security check, so
        // a lenient parse here would hand that key back as if it were a question's.
        row.setGateKey("call-1");

        assertThat(applier.apply(List.of(row))).isFalse();
        verify(conversationClient, never()).answerUserQuestion(anyString(), anyString(),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("every question of the call travels, in the order they were asked")
    void carriesEveryAnswer() {
        when(conversationClient.answerUserQuestion(anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(false);

        applier.apply(List.of(row("Tone", "Formal"), row("Length", "Short")));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(conversationClient).sendChatSync(anyString(), anyString(), message.capture(),
                anyString(), any(), any(), anyString(), any(), anyString());
        assertThat(message.getValue()).isEqualTo("Answer to Tone: Formal\nAnswer to Length: Short");
    }

    @Test
    @DisplayName("a free-text answer travels as what the person wrote")
    void carriesFreeText() {
        when(conversationClient.answerUserQuestion(anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(false);
        ChatAuthorizationRequestEntity row = row("Tone", null);
        row.getAnswer().put("freeText", "something else entirely");
        row.getAnswer().put("custom", true);

        applier.apply(List.of(row));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(conversationClient).sendChatSync(anyString(), anyString(), message.capture(),
                anyString(), any(), any(), anyString(), any(), anyString());
        assertThat(message.getValue()).isEqualTo("Answer to Tone: something else entirely");
    }

    @Test
    @DisplayName("an agent whose model cannot be read still gets its turn")
    void survivesAnAgentLookupFailure() {
        when(conversationClient.answerUserQuestion(anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(false);
        when(agentClient.getAgent(any(UUID.class), anyString()))
                .thenThrow(new IllegalStateException("agent-service down"));

        assertThat(applier.apply(List.of(row("Tone", "Formal")))).isTrue();

        // The turn runs on the conversation's own defaults. Losing the model is not a reason to
        // drop an answer a person already gave.
        verify(conversationClient).sendChatSync(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("with no conversation client wired, nothing is claimed to have been applied")
    @SuppressWarnings("unchecked")
    void reportsFailureWithoutAClient() {
        ObjectProvider<ConversationClient> none = mock(ObjectProvider.class);
        ObjectProvider<AgentClient> agents = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        applier = new ChatQuestionAnswerApplier(none, agents);

        assertThat(applier.apply(List.of(row("Tone", "Formal")))).isFalse();
    }

    @Test
    @DisplayName("an empty group applies nothing")
    void emptyGroup() {
        assertThat(applier.apply(List.of())).isFalse();
        assertThat(applier.apply(null)).isFalse();
    }

    /** A settled question row, answered with one label. */
    private static ChatAuthorizationRequestEntity row(String header, String selected) {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT);
        row.setOrganizationId(ORG);
        row.setConversationId("conv-1");
        row.setGateKey("call-1:ask");
        row.setGroupKey(UUID.randomUUID().toString());
        row.setAgentId(AGENT_ID);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("header", header);
        answer.put("selected", selected != null ? List.of(selected) : List.of());
        answer.put("freeText", null);
        answer.put("custom", false);
        row.setAnswer(answer);
        return row;
    }
}
