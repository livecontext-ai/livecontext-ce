package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.service.execution.ChannelAuthorizationClient.Delivery;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an agent nobody is watching is told when it asks for permission.
 *
 * <p>The behaviour under test is entirely about what lands in the agent's own
 * conversation, because that conversation is the only memory the next run has. An
 * agent told "it timed out" asks again tomorrow; an agent told "you already asked
 * and it is still waiting" does not, and reads the same sentence again on its next
 * run.
 */
@DisplayName("RemoteToolExecutionService - asking outside the app")
class RemoteToolExecutionChannelAuthorizationTest {

    private static final String TENANT = "42";

    private RemoteToolExecutionService service;
    private ToolApprovalGate gate;
    private ApprovalCardPublisher publisher;
    private ChannelAuthorizationClient channelClient;
    private NotificationClient notificationClient;

    @BeforeEach
    void setUp() {
        service = spy(new RemoteToolExecutionService(new ObjectMapper()));
        gate = mock(ToolApprovalGate.class);
        publisher = mock(ApprovalCardPublisher.class);
        channelClient = mock(ChannelAuthorizationClient.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.beginPark(any())).thenReturn(true);
        lenient().when(publisher.publishToolAuthorization(anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenReturn("{\"card\":1}");
        notificationClient = mock(NotificationClient.class);
        service.configureApprovalGateForTest(gate, publisher, new ApprovalCardExtractor(new ObjectMapper()));
        service.configureChannelAuthorizationForTest(channelClient);
        service.configureNotificationClientForTest(notificationClient);
    }

    /**
     * A SCHEDULED run, in the exact shape one really has.
     *
     * <p>It carries a conversation AND a stream id and NO task id, because the sync
     * path mints a stream id unconditionally and the schedule fire passes no task.
     * That shape is indistinguishable from someone chatting, which is why the marker
     * below exists and why nothing here may be derived from the other three keys.
     */
    private static Map<String, Object> scheduledRunCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "sync-conv-1");
        creds.put("__agentId__", "11111111-1111-1111-1111-111111111111");
        creds.put("__orgId__", "org-1");
        creds.put("__requireToolAuthorization__", true);
        creds.put("__unattendedRun__", true);
        return creds;
    }

    /** A task run: unattended for a second, independent reason (it carries a task id). */
    private static Map<String, Object> taskRunCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        creds.put("__taskId__", "task-9");
        creds.put("__agentId__", "11111111-1111-1111-1111-111111111111");
        creds.put("__orgId__", "org-1");
        creds.put("__requireToolAuthorization__", true);
        return creds;
    }

    /** Somebody is in front of the screen: the in-app card is the right surface. */
    private static Map<String, Object> watchedChatCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        creds.put("__orgId__", "org-1");
        return creds;
    }

    private static ToolCall call() {
        return new ToolCall("call-1", "workflow", Map.of("action", "execute"), null);
    }

    private static ToolResult gateResult() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolAuthorizationRequired", true);
        metadata.put("rule", "workflow:execute");
        metadata.put("toolCallId", "call-1");
        metadata.put("argsSummary", "workflow_id=abc");
        return ToolResult.builder()
                .toolCall(call())
                .success(true)
                .content("{\"status\":\"authorization_required\"}")
                .metadata(metadata)
                .build();
    }

    private ToolResult park(Map<String, Object> credentials) {
        return service.parkForAuthorization(call(), TENANT, credentials, gateResult(), 0L);
    }

    @Test
    @DisplayName("also asks when the run is unattended for the other reason (a task)")
    void asksTheChannelOnATaskRun() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.SENT, "telegram", "Ops room", null));
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        park(taskRunCredentials());

        verify(channelClient).request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("asks the workspace's chat when nobody is watching the run")
    void asksTheChannelWhenUnattended() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.SENT, "telegram", "-100", "2026-09-17T10:00:00Z"));
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        ToolResult result = park(scheduledRunCredentials());

        verify(channelClient).request(eq(TENANT), eq("org-1"), eq("conv-1"), eq("call-1"),
                eq("workflow:execute"), eq("11111111-1111-1111-1111-111111111111"), eq("workflow_id=abc"),
                // The identity of the ask comes from the CALL, so two different publishes
                // are two questions even when neither carries a display summary.
                eq("workflow|action=execute"));
        // The sentence the next run will read in its own history.
        assertThat(result.content()).contains("authorization_requested")
                .contains("was sent to the user on telegram")
                .contains("Do NOT ask for it again");
    }

    @Test
    @DisplayName("names the destination, not only the app, when the workspace gave it a title")
    void namesTheDestinationItWasSentTo() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.SENT, "telegram", "Ops room", null));
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        ToolResult result = park(scheduledRunCredentials());

        // Orchestrator resolves that title on every delivery for exactly this sentence. A
        // workspace with a bot in three rooms gets "telegram" three times otherwise, and the
        // person the agent reports back to has to guess which one to open.
        assertThat(result.content()).contains("on telegram (Ops room)");
    }

    @Test
    @DisplayName("says the app alone when the destination has no title of its own")
    void fallsBackToTheAppName() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.SENT, "telegram", null, null));
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        ToolResult result = park(scheduledRunCredentials());

        // No empty parentheses where the room name would be. A one-to-one chat has no title.
        assertThat(result.content()).contains("on telegram ").doesNotContain("telegram (");
    }

    @Test
    @DisplayName("still names somewhere when the delivery named nothing at all")
    void neverTrailsOffWhereThePlaceShouldBe() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.SENT, null, null, null));
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        ToolResult result = park(scheduledRunCredentials());

        assertThat(result.content()).contains("their chat channel");
    }

    @Test
    @DisplayName("names the destination on the already-asked sentence too")
    void namesTheDestinationWhenAlreadyPending() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.ALREADY_PENDING, "telegram", "Ops room",
                        "2026-09-16T22:00:00Z"));

        ToolResult result = park(scheduledRunCredentials());

        // The two sentences are read by the same agent on consecutive runs. Naming the place
        // in one and not the other reads as two different destinations.
        assertThat(result.content()).contains("on telegram (Ops room)");
    }

    @Test
    @DisplayName("never goes outside the app when the person is watching the chat")
    void doesNotAskTheChannelInAWatchedChat() {
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        park(watchedChatCredentials());

        // The in-app card IS the surface here; a message on their phone for a card
        // they are looking at is noise.
        verify(channelClient, never()).request(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("does not paint a second card when the same question is already waiting")
    void doesNotRepaintWhenAlreadyPending() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.ALREADY_PENDING, "telegram", "-100",
                        "2026-09-16T22:00:00Z"));

        ToolResult result = park(scheduledRunCredentials());

        verify(publisher, never()).publishToolAuthorization(anyString(), anyString(), any(),
                anyBoolean(), anyString());
        // The park is given back: holding a slot for minutes on a question that is
        // already out there helps nobody.
        verify(gate).abandonPark("conv-1", "call-1");
        verify(gate, never()).awaitDecision(any());
        assertThat(result.content()).contains("authorization_pending")
                .contains("You ALREADY asked")
                .contains("2026-09-16T22:00:00Z");
        assertThat(result.metadata()).containsEntry("authorizationAlreadyPending", true);
        // No card was painted, so nothing may claim one was: the app would then show none.
        assertThat(result.metadata()).doesNotContainKey(ToolApprovalGate.META_CARD_EMITTED);
    }

    @Test
    @DisplayName("keeps the refusal wording when the user says no, whatever the channel did")
    void refusalWinsOverTheChannelSentence() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.SENT, "telegram", "-100", null));
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        ToolResult result = park(scheduledRunCredentials());

        // A settled decision is the truth; "still waiting there" would be a lie the
        // agent then repeats to the user.
        assertThat(result.content()).doesNotContain("still waiting there");
        assertThat(result.content()).contains("REFUSED");
    }

    @Test
    @DisplayName("says it has nobody to ask when the workspace has no chat connected")
    void saysSoWhenThereIsNowhereToAsk() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Delivery.none());

        ToolResult result = park(scheduledRunCredentials());

        // This used to fall through to the plain "authorization_required" refusal, which
        // reads as "the user has not answered yet" and is the wrong thing to learn: nobody
        // was ever asked, and no later run can be answered either. The agent now gets the
        // one sentence that is true, plus the two actions that would lift the block.
        assertThat(result.content()).contains("authorization_unreachable");
        assertThat(result.content()).contains("no chat connected");
        assertThat(result.content()).contains("channel(action='connect')");
        assertThat(result.content()).doesNotContain("was sent to the user");
    }

    @Test
    @DisplayName("does not hold the call waiting for an answer that cannot arrive")
    void doesNotParkWithNowhereToAsk() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Delivery.none());

        park(scheduledRunCredentials());

        // The park's whole purpose is to hold the call while somebody decides. With nobody
        // asked there is nobody deciding, so holding it spends the gate's full budget on a
        // certainty, and the agent is then told its request "ran out of time".
        verify(gate, never()).awaitDecision(any());
        verify(gate).abandonPark(anyString(), anyString());
    }

    @Test
    @DisplayName("rings the bell, because the conversation this run writes to is not read")
    void ringsTheBellWithNowhereToAsk() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Delivery.none());

        park(scheduledRunCredentials());

        // Telling the agent is not telling the person. An unattended run is unattended by
        // definition, so its transcript is the one place the news does not reach anybody.
        ArgumentCaptor<NotificationEmitRequest> emitted = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(emitted.capture());
        assertThat(emitted.getValue().getSubjectType()).isEqualTo("AGENT");
        assertThat(emitted.getValue().getSeverity()).isEqualTo("warning");
        assertThat(emitted.getValue().getTenantId()).isEqualTo(TENANT);
        // Keyed on the agent and the rule, not on the call: an hourly agent hits the same
        // wall every hour, and the endpoint dedupes on this, so one row per wall is what
        // the person sees instead of a bell filling up overnight.
        assertThat(emitted.getValue().getSourceId()).contains("agent-auth-unreachable:");
    }

    @Test
    @DisplayName("a delivery failure reaches nobody either, and says so")
    void deliveryFailureIsNotAToolFailure() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new Delivery(Delivery.Status.FAILED, "telegram", null, null));

        ToolResult result = park(scheduledRunCredentials());

        // "Nobody could be asked" and "the tool failed" lead the agent to completely
        // different behaviour, and only the first one happened: the call is still a success
        // carrying a refusal, never an error. A destination that exists but could not be
        // written to reaches the person exactly as little as no destination at all.
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("authorization_unreachable");
        assertThat(result.content()).doesNotContain("was sent to the user");
        verify(gate, never()).awaitDecision(any());
    }

    @Test
    @DisplayName("the bell being down never fails the call on top of the block")
    void aBellFailureIsSwallowed() {
        when(channelClient.request(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Delivery.none());
        doThrow(new IllegalStateException("orchestrator down")).when(notificationClient).emit(any());

        ToolResult result = park(scheduledRunCredentials());

        // The block is already the bad outcome. Failing the tool because the bell could not
        // be rung would replace one problem with two, and the agent would then report a
        // transport error for something that is really a configuration gap.
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("authorization_unreachable");
    }

    @Test
    @DisplayName("works with no delivery client at all, exactly as before the feature")
    void degradesWithoutTheClient() {
        service.configureChannelAuthorizationForTest(null);
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        ToolResult result = park(scheduledRunCredentials());

        assertThat(result.content()).contains("authorization_required");
        assertThat(result.metadata()).containsEntry(ToolApprovalGate.META_CARD_EMITTED, true);
    }
}
