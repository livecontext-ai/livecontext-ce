package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The behaviour the user actually asked for: a permission request should hold the call and
 * resolve in place, and every way that can fail should land back on the flow that shipped
 * before (synthetic result, turn ends, user resumes).
 */
@DisplayName("RemoteToolExecutionService - parking a call on the user's answer")
class RemoteToolExecutionApprovalGateTest {

    private static final String TENANT = "42";

    private RemoteToolExecutionService service;
    private ToolApprovalGate gate;
    private ApprovalCardPublisher publisher;

    @BeforeEach
    void setUp() {
        service = spy(new RemoteToolExecutionService(new ObjectMapper()));
        gate = mock(ToolApprovalGate.class);
        publisher = mock(ApprovalCardPublisher.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.beginPark(any())).thenReturn(true);
        // A non-null return means "the card reached the user". Publishing failures are their
        // own test (cardThatNeverReachedTheUserIsNotParkedOn); everywhere else the card lands.
        org.mockito.Mockito.lenient()
                .when(publisher.publishToolAuthorization(anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenReturn("{\"card\":1}");
        org.mockito.Mockito.lenient()
                .when(publisher.publishServiceApproval(anyString(), anyString(), any(), anyString(),
                        anyBoolean(), anyBoolean(), anyString()))
                .thenReturn("{\"card\":2}");
        service.configureApprovalGateForTest(gate, publisher, new ApprovalCardExtractor(new ObjectMapper()));
    }

    private static Map<String, Object> chatCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        return creds;
    }

    private static ToolCall call() {
        return new ToolCall("call-1", "workflow", Map.of("action", "execute"), null);
    }

    private static ToolResult authorizationGateResult(String rule) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolAuthorizationRequired", true);
        metadata.put("rule", rule);
        metadata.put("toolCallId", "call-1");
        return ToolResult.builder()
                .toolCall(call())
                .success(true)
                .content("{\"status\":\"authorization_required\"}")
                .metadata(metadata)
                .build();
    }

    private static ToolResult credentialGateResult() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("serviceApprovalRequested", true);
        metadata.put("reason", "Credential required for Gmail");
        metadata.put("services", List.of(Map.of("serviceType", "gmail", "serviceName", "Gmail")));
        return ToolResult.builder()
                .toolCall(call())
                .success(true)
                .content("{\"status\":\"approval_needed\"}")
                .metadata(metadata)
                .build();
    }

    // ---------- executeTool: the two gates wired into the real call path ----------

    @Test
    @DisplayName("Approving a held call makes executeTool actually RUN the tool, in the same call")
    void executeToolRunsTheToolAfterApproval() {
        // The whole point of the feature, exercised through the real entry point: the
        // authorization check fires, the call parks, the user approves, and the tool runs.
        // Every other test here calls the park methods directly, so removing the dispatch
        // that follows an approval would leave them all green while the feature does nothing.
        ToolResult realResult = ToolResult.builder()
                .toolCall(call()).success(true).content("{\"ran\":true}").build();
        doReturn(realResult).when(service).dispatch(any(), any(), anyString(), any(), anyLong());
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.APPROVED);

        ToolResult out = service.executeTool(call(), null, "tenant-1", chatCredentials());

        assertThat(out).isSameAs(realResult);
        verify(publisher).publishToolAuthorization(eq("stream-1"), eq("conv-1"), any(), eq(true), eq("call-1"));
        verify(service).dispatch(any(), any(), eq("tenant-1"), any(), anyLong());
    }

    @Test
    @DisplayName("Refusing a held call makes executeTool return without running the tool")
    void executeToolDoesNotRunTheToolAfterRefusal() {
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        ToolResult out = service.executeTool(call(), null, "tenant-1", chatCredentials());

        assertThat(out.metadata()).containsEntry(ToolApprovalGate.META_DECISION, "denied");
        // The sensitive action must not reach the outside world on a refusal.
        verify(service, never()).dispatch(any(), any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("A call that needs no authorization goes straight through, gate or no gate")
    void executeToolLeavesAnUngatedCallAlone() {
        ToolResult realResult = ToolResult.builder()
                .toolCall(call()).success(true).content("{\"ok\":true}").build();
        doReturn(realResult).when(service).dispatch(any(), any(), anyString(), any(), anyLong());

        ToolResult out = service.executeTool(
                new ToolCall("call-2", "files", Map.of("action", "list"), null),
                null, "tenant-1", chatCredentials());

        assertThat(out).isSameAs(realResult);
        verifyNoInteractions(publisher);
        verify(gate, never()).awaitDecision(any());
    }

    // ---------- authorization gate ----------

    @Test
    @DisplayName("Approving releases the call so the caller executes the tool for real")
    void approvingReleasesTheCall() {
        when(gate.awaitDecision(argThat(p -> "conv-1".equals(p.conversationId()) && "call-1".equals(p.gateKey()))))
                .thenReturn(ToolApprovalGate.Decision.APPROVED);

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);

        // null == "the user said yes, go run it" - the contract executeTool relies on.
        assertThat(held).isNull();
        verify(publisher).publishToolAuthorization(eq("stream-1"), eq("conv-1"), any(), eq(true), eq("call-1"));
    }

    @Test
    @DisplayName("Refusing keeps the pre-gate result and suppresses a duplicate card")
    void refusingKeepsThePreGateResult() {
        when(gate.awaitDecision(any()))
                .thenReturn(ToolApprovalGate.Decision.DENIED);

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);

        assertThat(held).isNotNull();
        // The content now states the refusal (see refusalTellsTheAgentTheAnswerWasNo); what
        // must survive untouched is the SIGNAL that nothing ran.
        assertThat(held.content()).contains("\"executed\":false");
        assertThat(held.metadata()).containsEntry(ToolApprovalGate.META_CARD_EMITTED, true);
        // "denied" means ANSWERED: it is what stops conversation-service from persisting a
        // card the user just dismissed and resurrecting it on the next page load.
        assertThat(held.metadata()).containsEntry(ToolApprovalGate.META_DECISION, "denied");
        // The original signal survives so the agent still reads "not executed".
        assertThat(held.metadata()).containsEntry("toolAuthorizationRequired", true);
    }

    @Test
    @DisplayName("An expired park keeps the pre-gate result too")
    void expiredParkKeepsThePreGateResult() {
        when(gate.awaitDecision(any()))
                .thenReturn(ToolApprovalGate.Decision.EXPIRED);

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);

        assertThat(held).isNotNull();
        assertThat(held.metadata()).containsEntry(ToolApprovalGate.META_CARD_EMITTED, true);
        // "expired" means NOT answered: the card is still on screen, so it must still be
        // persisted and survive a page refresh - unlike a refusal.
        assertThat(held.metadata()).containsEntry(ToolApprovalGate.META_DECISION, "expired");
    }

    @Test
    @DisplayName("With the gate disabled nothing is parked and the result is left untouched")
    void disabledGateLeavesTheResultUntouched() {
        when(gate.isEnabled()).thenReturn(false);
        ToolResult original = authorizationGateResult("workflow:execute");

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), original, 0L);

        assertThat(held).isSameAs(original);
        // Untouched means the streaming callback still paints the card, exactly as before.
        assertThat(held.metadata()).doesNotContainKey(ToolApprovalGate.META_CARD_EMITTED);
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Without a live stream there is nobody to show a card to, so nothing parks")
    void noStreamMeansNoPark() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        ToolResult original = authorizationGateResult("workflow:execute");

        assertThat(service.parkForAuthorization(call(), TENANT, creds, original, 0L)).isSameAs(original);
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("application:acquire never parks - the USER installs it, approving is not 'run the tool'")
    void applicationAcquireNeverParks() {
        ToolResult original = authorizationGateResult("application:acquire");

        ToolResult held = service.parkForAuthorization(
                new ToolCall("call-1", "application", Map.of("action", "acquire"), null),
                TENANT, chatCredentials(), original, 0L);

        assertThat(held).isSameAs(original);
        verifyNoInteractions(publisher);
        verify(gate, never()).awaitDecision(any());
    }

    // ---------- credential gate ----------

    @Test
    @DisplayName("Connecting the service re-runs the call so the agent gets the real API response")
    void connectingTheServiceReRunsTheCall() {
        ToolResult realResult = ToolResult.builder()
                .toolCall(call()).success(true).content("{\"messages\":[]}").build();
        doReturn(realResult).when(service).dispatch(any(), any(), anyString(), any(), anyLong());
        when(gate.awaitDecision(any()))
                .thenReturn(ToolApprovalGate.Decision.APPROVED);

        ToolResult out = service.parkForCredential(call(), null, "tenant-1", chatCredentials(),
                credentialGateResult(), System.currentTimeMillis());

        assertThat(out).isSameAs(realResult);
        verify(service, times(1)).dispatch(any(), any(), eq("tenant-1"), any(), anyLong());
        verify(publisher).publishServiceApproval(eq("stream-1"), eq("conv-1"), any(),
                eq("Credential required for Gmail"), eq(false), eq(true), eq("call-1:credential"));
    }

    @Test
    @DisplayName("Refusing to connect keeps the placeholder result and never re-runs the call")
    void refusingToConnectDoesNotReRun() {
        when(gate.awaitDecision(any()))
                .thenReturn(ToolApprovalGate.Decision.DENIED);

        ToolResult out = service.parkForCredential(call(), null, "tenant-1", chatCredentials(),
                credentialGateResult(), System.currentTimeMillis());

        assertThat(out.metadata()).containsEntry(ToolApprovalGate.META_CARD_EMITTED, true);
        verify(service, never()).dispatch(any(), any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("A result that asks for nothing is returned untouched, with no card and no park")
    void ordinaryResultIsUntouched() {
        ToolResult ordinary = ToolResult.builder()
                .toolCall(call()).success(true).content("{\"ok\":true}").build();

        ToolResult out = service.parkForCredential(call(), null, "tenant-1", chatCredentials(),
                ordinary, System.currentTimeMillis());

        assertThat(out).isSameAs(ordinary);
        verifyNoInteractions(publisher);
        verify(gate, never()).awaitDecision(any());
    }

    @Test
    @DisplayName("An AUTHORIZATION card is not re-parked by the credential gate (it already was)")
    void authorizationCardIsNotReParkedByTheCredentialGate() {
        ToolResult alreadyGated = authorizationGateResult("workflow:execute");

        ToolResult out = service.parkForCredential(call(), null, "tenant-1", chatCredentials(),
                alreadyGated, System.currentTimeMillis());

        assertThat(out).isSameAs(alreadyGated);
        verify(publisher, never()).publishServiceApproval(anyString(), anyString(), any(),
                anyString(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("request_credential is never parked - raising the card IS its job")
    void requestCredentialIsNeverParked() {
        ToolCall requestCredential = new ToolCall("call-1", "request_credential", Map.of(), null);
        ToolResult asked = credentialGateResult();

        ToolResult out = service.parkForCredential(requestCredential, null, "tenant-1",
                chatCredentials(), asked, System.currentTimeMillis());

        // Parking it would stall the turn waiting for an answer to a question the agent
        // deliberately asked, then hand back the same result anyway.
        assertThat(out).isSameAs(asked);
        verifyNoInteractions(publisher);
        verify(gate, never()).awaitDecision(any());
    }

    @Test
    @DisplayName("No context that raises no card may hold a call on a human")
    void exemptContextsNeverParkOnACredentialCard() {
        // Each of these is exempt from the authorization card by design. Parking them would
        // hold the call for the full deadline waiting for a click nobody is there to make -
        // a scheduled run, a task and a sub-agent all run with no user watching, and an
        // agent-backed chat is deliberately "an agent", not the general chat.
        Map<String, String> exemptions = Map.of(
                "__workflowRunId__", "run-1",
                "__taskId__", "task-1",
                "__agentId__", "agent-1",
                "__agent_depth__", "1");

        for (Map.Entry<String, String> exemption : exemptions.entrySet()) {
            Map<String, Object> creds = chatCredentials();
            creds.put(exemption.getKey(), exemption.getValue());
            ToolResult original = credentialGateResult();

            ToolResult out = service.parkForCredential(call(), null, "tenant-1", creds,
                    original, System.currentTimeMillis());

            assertThat(out).as("must not park for %s", exemption.getKey()).isSameAs(original);
        }
        verifyNoInteractions(publisher);
        verify(gate, never()).awaitDecision(any());
    }

    @Test
    @DisplayName("An agent that explicitly asks to be gated DOES park, exemption or not")
    void anAgentOptingIntoAuthorizationStillParks() {
        // The opt-in seam must reach here too, otherwise an agent configured to ask its user
        // would raise cards on the authorization path and silently skip them on this one.
        Map<String, Object> creds = chatCredentials();
        creds.put("__agentId__", "agent-1");
        creds.put("__requireToolAuthorization__", true);
        when(gate.awaitDecision(any()))
                .thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForCredential(call(), null, "tenant-1", creds,
                credentialGateResult(), System.currentTimeMillis());

        verify(publisher).publishServiceApproval(eq("stream-1"), eq("conv-1"), any(),
                anyString(), anyBoolean(), eq(true), eq("call-1:credential"));
    }

    @Test
    @DisplayName("A NON-gateable action never parks, even on a gateable tool")
    void nonGateableActionNeverParks() {
        // Reads are the bulk of these tools' traffic and raise no card, so holding one would
        // stall a turn for minutes with nothing on screen to answer.
        ToolResult original = credentialGateResult();

        ToolResult out = service.parkForCredential(
                new ToolCall("call-1", "catalog", Map.of("action", "search"), null),
                null, "tenant-1", chatCredentials(), original, System.currentTimeMillis());

        assertThat(out).isSameAs(original);
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("A re-run that STILL reports no connection is handed back, not parked again")
    void reDispatchThatStillNeedsAConnectionIsNotReParked() {
        // The user connected something, but not what this call needs (wrong account, wrong
        // service). Parking again would loop on a connection that never lands; returning the
        // result lets the ordinary card path take over and the turn end.
        ToolResult stillNotConnected = credentialGateResult();
        doReturn(stillNotConnected).when(service).dispatch(any(), any(), anyString(), any(), anyLong());
        when(gate.awaitDecision(any()))
                .thenReturn(ToolApprovalGate.Decision.APPROVED);

        ToolResult out = service.parkForCredential(call(), null, "tenant-1", chatCredentials(),
                credentialGateResult(), System.currentTimeMillis());

        assertThat(out).isSameAs(stillNotConnected);
        // Exactly one re-run, and exactly one park.
        verify(service, times(1)).dispatch(any(), any(), anyString(), any(), anyLong());
        verify(gate, times(1)).awaitDecision(any());
    }

    @Test
    @DisplayName("A park that cannot be advertised shows no blocking card and changes nothing")
    void unadvertisedParkShowsNoBlockingCard() {
        when(gate.beginPark(any())).thenReturn(false);
        ToolResult original = authorizationGateResult("workflow:execute");

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), original, 0L);

        // A card claiming "the agent is waiting for you" that no park is listening to is a
        // dead end: answering releases nothing and no turn restarts. Better to keep the
        // ordinary card, which the user's answer resumes the way it always has.
        assertThat(held).isSameAs(original);
        verifyNoInteractions(publisher);
        verify(gate, never()).awaitDecision(any());
    }

    @Test
    @DisplayName("The same holds for a connect-a-service card: no advertisement, no blocking card")
    void unadvertisedCredentialParkShowsNoBlockingCard() {
        when(gate.beginPark(any())).thenReturn(false);
        ToolResult original = credentialGateResult();

        ToolResult out = service.parkForCredential(call(), null, "tenant-1", chatCredentials(),
                original, System.currentTimeMillis());

        assertThat(out).isSameAs(original);
        verifyNoInteractions(publisher);
        verify(gate, never()).awaitDecision(any());
    }

    @Test
    @DisplayName("The caller's tool deadline is forwarded so a park cannot outlive its own budget")
    void callerDeadlineIsForwardedToTheGate() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__toolDeadlineEpochMs__", 1_700_000_000_000L);
        when(gate.awaitDecision(any()))
                .thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);

        verify(gate).awaitDecision(argThat(p ->
                p.hardDeadlineEpochMs() == 1_700_000_000_000L
                        && "stream-1".equals(p.streamId())
                        && "conv-1".equals(p.conversationId())));
    }

    @Test
    @DisplayName("A card that never reached the user is not parked on, and leaves no marker behind")
    void cardThatNeverReachedTheUserIsNotParkedOn() {
        // Publishing can fail on its own (a Redis blip on the channel, a serialisation
        // error) even though beginPark's write succeeded.
        when(publisher.publishToolAuthorization(anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenReturn(null);
        ToolResult original = authorizationGateResult("workflow:execute");

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), original, 0L);

        // Holding now would be minutes of spinner with nothing to click, and the
        // "already emitted" flag would stop the result consumer painting the fallback card
        // too: dead air AND no card. Untouched means the ordinary card path still runs.
        assertThat(held).isSameAs(original);
        assertThat(held.metadata()).doesNotContainKey(ToolApprovalGate.META_CARD_EMITTED);
        verify(gate, never()).awaitDecision(any());
        // And the advertisement is withdrawn, or it would tell every later answer that
        // somebody is holding, swallowing the click.
        verify(gate).abandonPark("conv-1", "call-1");
    }

    @Test
    @DisplayName("Two parks in ONE call share a window instead of each taking a fresh one")
    void bothParksOfOneCallShareTheSameWindow() {
        // A call can raise both cards in turn (authorize the action, then connect the
        // service it needs). Giving the second park a fresh half-window would let the pair
        // outlast the watchdog that has been counting since before the first one began.
        long callStarted = System.currentTimeMillis() - 5_000;
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), callStarted);
        service.parkForCredential(call(), null, "tenant-1", chatCredentials(), credentialGateResult(), callStarted);

        verify(gate, times(2)).awaitDecision(argThat(p -> p.callStartedEpochMs() == callStarted));
    }

    @Test
    @DisplayName("The credential park uses its OWN key, so an answer to one card cannot release the other")
    void theTwoParksOfOneCallUseDifferentKeys() {
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);
        service.parkForCredential(call(), null, "tenant-1", chatCredentials(), credentialGateResult(), 0L);

        // A duplicate answer to the first card (double click, client retry) would otherwise
        // land on the second and release a connect the user has not made.
        verify(gate).awaitDecision(argThat(p -> "call-1".equals(p.gateKey())));
        verify(gate).awaitDecision(argThat(p -> "call-1:credential".equals(p.gateKey())));
    }

    @Test
    @DisplayName("An ANSWERED card leaves the replay buffer; an unanswered one must stay")
    void answeredCardsLeaveTheReplayBuffer() {
        when(publisher.publishToolAuthorization(anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenReturn("{\"card\":1}");

        for (ToolApprovalGate.Decision settled : java.util.List.of(
                ToolApprovalGate.Decision.APPROVED,
                ToolApprovalGate.Decision.DENIED,
                ToolApprovalGate.Decision.STOPPED)) {
            when(gate.awaitDecision(any())).thenReturn(settled);
            service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);
        }
        // A card the user dealt with must not come back on a reload: the turn keeps running
        // after a release, so it WOULD come back, and answering it again releases nothing
        // while quietly queueing a redundant turn.
        verify(publisher, times(3)).unbuffer(eq("stream-1"), eq("{\"card\":1}"));

        for (ToolApprovalGate.Decision unanswered : java.util.List.of(
                ToolApprovalGate.Decision.EXPIRED,
                ToolApprovalGate.Decision.UNAVAILABLE)) {
            when(gate.awaitDecision(any())).thenReturn(unanswered);
            service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);
        }
        // Nobody answered these, and the database row that would otherwise carry them is
        // only written at the END OF THE RUN - on the bridge that is minutes later, while
        // the CLI keeps working. Dropping them here would leave a reload in that window with
        // no card at all, which is worse than a card whose "waiting" label has gone stale.
        verify(publisher, times(3)).unbuffer(anyString(), anyString());
    }

    @Test
    @DisplayName("A CLI-bridge session is declared to the gate, not guessed from the watchdog")
    void bridgeSessionMarkerReachesTheGate() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__cliBridgeSession__", true);
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);

        // This is what bounds the hold on that route. Inferred from the inactivity window it
        // was wrong twice: absent when the watchdog is off (cap gone where it was needed),
        // present on the direct route when configured (cap applied where it was not).
        verify(gate).awaitDecision(argThat(ToolApprovalGate.ParkRequest::cliBridgeSession));
    }

    @Test
    @DisplayName("A marker that arrives as text still caps the hold, and 'false' still does not")
    void theMarkerIsReadInItsTextFormToo() {
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        // Unreachable while the marker stays on an in-process map, but the two mistakes are
        // not symmetrical: missing it restores the original bug (a full-budget hold on a CLI
        // that quit long ago), recognising a stray one only shortens a wait.
        Map<String, Object> asText = chatCredentials();
        asText.put("__cliBridgeSession__", "true");
        service.parkForAuthorization(call(), TENANT, asText, authorizationGateResult("workflow:execute"), 0L);
        verify(gate).awaitDecision(argThat(ToolApprovalGate.ParkRequest::cliBridgeSession));

        Map<String, Object> explicitlyOff = chatCredentials();
        explicitlyOff.put("__cliBridgeSession__", false);
        service.parkForAuthorization(call(), TENANT, explicitlyOff, authorizationGateResult("workflow:execute"), 0L);
        verify(gate).awaitDecision(argThat(p -> !p.cliBridgeSession()));
    }

    @Test
    @DisplayName("The credential card leaves the replay buffer once answered, like the other one")
    void theAnsweredCredentialCardLeavesTheReplayBuffer() {
        when(publisher.publishServiceApproval(anyString(), anyString(), any(), any(),
                anyBoolean(), anyBoolean(), anyString()))
                .thenReturn("{\"card\":\"svc\"}");
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.APPROVED);

        service.parkForCredential(call(), null, "tenant-1", chatCredentials(), credentialGateResult(), 0L);

        // Asserted on this site specifically, not only on the authorization one: this is the
        // card with the longest hold (an OAuth round trip in another tab), so it is the one
        // most likely to be replayed after a reload - and a replayed copy of a card the user
        // already dealt with sends them to answer a key nobody holds.
        verify(publisher).unbuffer(eq("stream-1"), eq("{\"card\":\"svc\"}"));
    }

    @Test
    @DisplayName("The credential card is declared too - it is the one that waits on OAuth")
    void bridgeSessionMarkerReachesTheCredentialPark() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__cliBridgeSession__", true);
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForCredential(call(), null, "tenant-1", creds, credentialGateResult(), 0L);

        // Flagging only the authorization park would leave the cap off exactly where the
        // wait is longest: connecting a service means an OAuth round trip in another tab,
        // so this card is the one that runs to the end of its budget. Uncapped on a bridge
        // whose watchdog is off, that is a 240 s hold on a call the CLI drops at 60 s.
        verify(gate).awaitDecision(argThat(p ->
                p.cliBridgeSession() && "call-1:credential".equals(p.gateKey())));
    }

    @Test
    @DisplayName("A direct chat is NOT flagged as a bridge session, whatever its watchdog says")
    void directChatIsNotFlaggedAsBridge() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__inactivityTimeoutSeconds__", 300);   // legal on the direct route too
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        // BOTH cards, because the flag is per-park: hardcoded on the credential site alone
        // it would cut a direct-route connect card down to the bridge's budget, and that is
        // the one card the short budget is known to make unusable, since it waits on an
        // OAuth round trip in another tab.
        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);
        service.parkForCredential(call(), null, "tenant-1", creds, credentialGateResult(), 0L);

        verify(gate, times(2)).awaitDecision(argThat(p -> !p.cliBridgeSession()));
    }

    @Test
    @DisplayName("The run's inactivity window is forwarded so a park cannot outlast the watchdog")
    void inactivityWindowIsForwardedToTheGate() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__inactivityTimeoutSeconds__", 120);
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);

        // Without it the gate would use its own budget and the run would be killed for
        // silence while the card was still waiting - on an agent whose window is shorter
        // than the default, that is every single gated call.
        verify(gate).awaitDecision(argThat(p -> p.inactivityWindowMs() == 120_000L));
    }

    @Test
    @DisplayName("An out-of-contract inactivity value is ignored rather than invented into a window")
    void outOfContractInactivityIsIgnored() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__inactivityTimeoutSeconds__", 3);   // below the 10s contract floor
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);

        // A 3s window would make every park expire instantly. The bridge ignores the same
        // values, so agreeing with it is what keeps the two ends consistent.
        verify(gate).awaitDecision(argThat(p -> p.inactivityWindowMs() == 0L));
    }

    @Test
    @DisplayName("A deadline sent as a string is still honoured")
    void stringDeadlineIsParsed() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__toolDeadlineEpochMs__", "1700000000000");
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);

        verify(gate).awaitDecision(argThat(p -> p.hardDeadlineEpochMs() == 1_700_000_000_000L));
    }

    @Test
    @DisplayName("The tool's own runtime is held back from the park, not left to be guessed")
    void theExecutionReserveReachesTheGate() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__toolExecutionReserveMs__", 30_000);
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);

        // The one credential of this family whose CONSUMER side nothing pinned: the loop's
        // write was tested and the gate's use was tested, but the wire between them could be
        // cut with every suite green. Without it the gate reserves a token margin instead of
        // what the tool needs, so approving the second card of a call starts a run that is
        // charged and then thrown away as a timeout - the outcome the reserve exists to stop.
        verify(gate).awaitDecision(argThat(p -> p.executionReserveMs() == 30_000L));
    }

    @Test
    @DisplayName("A malformed deadline means 'no ceiling', not a park that ends instantly")
    void malformedDeadlineIsTreatedAsAbsent() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__toolDeadlineEpochMs__", "not-a-number");
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        service.parkForAuthorization(call(), TENANT, creds, authorizationGateResult("workflow:execute"), 0L);

        // Reading it as 0 keeps the gate's own budget; anything else would silently disable
        // parking for a caller that merely sent the wrong type.
        verify(gate).awaitDecision(argThat(p -> p.hardDeadlineEpochMs() == 0L));
    }

    @Test
    @DisplayName("A stopped turn keeps the result AND tells the agent the action will not run")
    void stoppedParkTellsTheAgentItIsOver() {
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.STOPPED);

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);

        assertThat(held.metadata()).containsEntry(ToolApprovalGate.META_DECISION, "stopped");
        // The pre-gate wording says the action will run "if the user approves" - after a
        // stop that is false, and an agent reading it offers to retry a cancelled turn.
        assertThat(held.content()).contains("stopped").doesNotContain("if the user approves");
    }

    @Test
    @DisplayName("A refusal tells the agent the user said NO, not that they have yet to answer")
    void refusalTellsTheAgentTheAnswerWasNo() {
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.DENIED);

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);

        // Metadata records the refusal but only conversation-service reads metadata; the
        // model reads the CONTENT, and left untouched it says the user "has only been asked".
        assertThat(held.content()).contains("REFUSED");
        assertThat(held.content()).doesNotContain("only been asked");
    }

    @Test
    @DisplayName("An UNANSWERED park keeps the original wording - the card really is still waiting")
    void expiredParkKeepsTheOriginalWording() {
        when(gate.awaitDecision(any())).thenReturn(ToolApprovalGate.Decision.EXPIRED);

        ToolResult held = service.parkForAuthorization(call(), TENANT, chatCredentials(), authorizationGateResult("workflow:execute"), 0L);

        assertThat(held.content()).isEqualTo("{\"status\":\"authorization_required\"}");
    }
}
