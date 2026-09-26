package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.tools.authz.AuthorizationAsk;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one step on this path that can authorize something.
 *
 * <p>It posts to the endpoints the in-app card posts to, which already hold the rule that
 * matters: release the parked call if one is still holding, otherwise write a grant the next
 * turn consumes. What it adds is WHICH call that grant covers. The answer arrives from a chat,
 * often hours late, and the turn that spends it may be a scheduled run nobody is watching, so a
 * grant recorded against the rule would let the agent do the next thing of that kind unasked.
 */
@DisplayName("chat answer applier - what the grant it writes covers")
class AgentAuthorizationAnswerApplierTest {

    private static final String FINGERPRINT = "aVeryBoundedDigestValue000000001";

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ConversationClient> provider = mock(ObjectProvider.class);
    private ConversationClient client;
    private AgentAuthorizationAnswerApplier applier;

    @BeforeEach
    void setUp() {
        client = mock(ConversationClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.answerToolAuthorization(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean())).thenReturn(true);
        applier = new AgentAuthorizationAnswerApplier(provider);
    }

    private static ChatAuthorizationRequestEntity request() {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setId(UUID.randomUUID());
        row.setTenantId("42");
        row.setOrganizationId("org-1");
        row.setConversationId("conv-1");
        row.setGateKey("call-9");
        row.setRule("workflow:execute");
        row.setFingerprint(FINGERPRINT);
        return row;
    }

    @Test
    @DisplayName("hands the ask over with the answer, so the grant covers one call")
    void passesTheFingerprint() {
        applier.apply(request(), true, "user-7");

        // Without this argument the endpoint records the RULE, and the person who approved
        // "run the September report" has approved running whatever workflow the agent picks
        // next, on a turn they will not see.
        verify(client).answerToolAuthorization(eq("conv-1"), eq("42"), eq("org-1"),
                eq("workflow:execute"), eq("call-9"), eq(FINGERPRINT), eq(true));
    }

    @Test
    @DisplayName("sends one body shape for both verdicts, so neither can drift alone")
    void bothVerdictsSendTheSameShape() {
        applier.apply(request(), false, "user-7");

        // The deny endpoint ignores the fingerprint: it writes no grant. What this pins is that
        // approve and deny go through ONE call with one argument list, so a change to the shape
        // cannot land on one verdict and not the other. Two call sites is how the approve path
        // kept an argument the deny path quietly lost.
        verify(client).answerToolAuthorization(anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(FINGERPRINT), eq(false));
    }

    @Test
    @DisplayName("the fingerprint it sends is the one the request was recorded under")
    void theAskIsTheStoredOne() {
        ChatAuthorizationRequestEntity row = request();
        row.setFingerprint(AuthorizationAsk.fingerprint("workflow:execute", "workflow|id=wf-1"));

        applier.apply(row, true, "user-7");

        // The value agent-service recomputes from the call is this same digest of rule plus
        // call material. Anything else here is a grant that can never match, which reads
        // exactly like a person who never pressed the button.
        verify(client).answerToolAuthorization(anyString(), anyString(), anyString(),
                eq("workflow:execute"), anyString(),
                eq(AuthorizationAsk.fingerprintOfCall("workflow:execute", "workflow",
                        java.util.Map.of("id", "wf-1"))), eq(true));
    }

    @Test
    @DisplayName("a request whose fingerprint came from the summary still fails closed")
    void anUnreproducibleAskIsNotWidened() {
        ChatAuthorizationRequestEntity row = request();
        // What deliver() stores when the caller supplied no call material: a digest of the
        // DISPLAY summary. agent-service recomputes from the call, so the two cannot agree.
        row.setFingerprint(AuthorizationAsk.fingerprint("workflow:execute", "a summary someone read"));

        applier.apply(row, true, "user-7");

        // It is still sent, and the grant it produces matches nothing, so the agent asks again.
        // The alternative, dropping the fingerprint to get a rule-wide grant, would turn an
        // unreproducible ask into permission for the next action of that kind. A discarded
        // approval costs one re-ask; that costs an unauthorized action.
        verify(client).answerToolAuthorization(anyString(), anyString(), anyString(),
                eq("workflow:execute"), anyString(),
                eq(AuthorizationAsk.fingerprint("workflow:execute", "a summary someone read")),
                eq(true));
    }

    @Test
    @DisplayName("authorizes nothing when this deployment has no conversation service")
    void noClientAuthorizesNothing() {
        when(provider.getIfAvailable()).thenReturn(null);

        // False, not a swallowed success: the caller has to say the decision could not be
        // applied rather than show a verdict on a question that is still open.
        assertThat(applier.apply(request(), true, "user-7")).isFalse();
        verify(client, never()).answerToolAuthorization(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("reports a refused answer as not applied")
    void relaysTheEndpointsVerdict() {
        when(client.answerToolAuthorization(anyString(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(false);

        assertThat(applier.apply(request(), true, "user-7")).isFalse();
    }
}
