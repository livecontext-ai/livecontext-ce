package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.cloud.RuntimeLlmProviderResolver;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every in-process agent loop must pass the CLI-bridge access policy.
 *
 * <p>It did not. {@code resolveProvider} called {@code LLMProviderFactory.getProvider}, the variant
 * that checks nothing, while {@code getProviderForUser} - the one that checks - had ZERO callers
 * anywhere in the codebase. Enforcement existed only at the two dispatch entry points
 * ({@code BridgeLoopDispatcher}, {@code ConversationAgentService}), so any execution that ran the
 * loop in-process reached a bridge provider ungated.
 *
 * <p>Production proof, and the reason this test exists: agent "Agenda Scout", owned by a non-admin,
 * was denied on all 12 of its scheduled fires and yet COMPLETED twice as a sub-agent on
 * {@code claude-code} - an {@code admin_only} bridge with an empty allowlist - billing 2617 credits
 * against the admin's shared subscription. Same agent, same user, same provider, opposite outcomes,
 * decided purely by which path dispatched it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopService - the bridge policy gates every in-process loop")
class AgentLoopServiceBridgeGateTest {

    private static final String BRIDGE = "claude-code";

    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider provider;
    @Mock private RuntimeLlmProviderResolver providerResolver;

    private AgentLoopService serviceWith(RuntimeLlmProviderResolver resolver) {
        return new AgentLoopService(providerFactory, null, null, null, null, resolver);
    }

    private static AgentLoopContext context(String userRoles) {
        return AgentLoopContext.builder()
            .provider(BRIDGE)
            .model("claude-fable-5")
            .userPrompt("scan the agenda")
            .tenantId("121")
            .userRoles(userRoles)
            .maxIterations(1)
            .build();
    }

    @Test
    @DisplayName("a denied bridge stops the run instead of reaching the model")
    void deniedBridgeStopsTheRun() {
        doThrow(new BridgeAccessDeniedException(BRIDGE, "admin_only_requires_admin_role", null))
            .when(providerFactory).enforceBridgeAccess(eq(BRIDGE), eq("121"), eq("USER"), anyBoolean());

        AgentLoopService service = serviceWith(null);

        // Denial THROWS rather than returning a failed result: that is the existing contract
        // BridgeLoopDispatcher uses and GlobalExceptionHandler maps to 403/429 with the typed
        // reason. The sub-agent handler catches it and reports a tool error.
        assertThatThrownBy(() -> service.execute(context("USER")))
            .isInstanceOf(BridgeAccessDeniedException.class)
            .as("this is the exact production case: a non-admin's sub-agent on an admin_only "
                + "bridge, which used to COMPLETE and bill the admin's shared subscription")
            .hasMessageContaining("admin_only_requires_admin_role");
        verify(providerFactory, never()).getProvider(anyString());
    }

    @Test
    @DisplayName("an allowed caller still gets their provider")
    void allowedCallerStillRuns() {
        when(providerFactory.getProvider(BRIDGE)).thenReturn(provider);
        lenient().when(provider.isConfigured()).thenReturn(false); // stop before any network call

        AgentLoopResult result = serviceWith(null).execute(context("USER,ADMIN"));

        assertThat(result.error())
            .as("the run must fail on the provider being unconfigured, NOT on access - proving "
                + "the gate let an admin through")
            .contains("not configured");
    }

    @Test
    @DisplayName("the gate runs BEFORE the CE cloud resolver, which returns bridges untouched")
    void gateRunsBeforeTheCloudResolver() {
        doThrow(new BridgeAccessDeniedException(BRIDGE, "admin_only_requires_admin_role", null))
            .when(providerFactory).enforceBridgeAccess(eq(BRIDGE), eq("121"), eq("USER"), anyBoolean());

        AgentLoopService service = serviceWith(providerResolver);

        assertThatThrownBy(() -> service.execute(context("USER")))
            .isInstanceOf(BridgeAccessDeniedException.class);
        // RuntimeLlmProviderResolver.resolve short-circuits for a bridge and hands back the local
        // provider, so gating inside it would have missed the CE cloud path entirely.
        verify(providerResolver, never()).resolve(anyString(), any());
    }

    @Test
    @DisplayName("the access check never counts against the daily bridge quota")
    void accessCheckDoesNotConsumeQuota() {
        lenient().when(providerFactory.getProvider(anyString())).thenReturn(provider);
        lenient().when(provider.isConfigured()).thenReturn(false);

        serviceWith(null).execute(context("USER,ADMIN"));

        // The quota is counted where a dispatch happens (BridgeLoopDispatcher). Counting here too
        // would double-charge an admin whose run passes both.
        verify(providerFactory).enforceBridgeAccess(eq(BRIDGE), eq("121"), eq("USER,ADMIN"), eq(false));
    }
}
