package com.apimarketplace.agent.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit contract for {@link BridgeAccessGuard}:
 *
 * <ul>
 *   <li>Non-bridge providers never call {@link BridgeAccessClient} (zero auth-service traffic
 *       for OpenAI / Anthropic / etc.).</li>
 *   <li>Bridge providers are gated through the client; a deny raises
 *       {@link BridgeAccessDeniedException} carrying the typed reason.</li>
 *   <li>A null client fails-CLOSED by default (the bean is registered but
 *       misconfigured); explicit opt-out for cloud-only deployments is via the
 *       two-arg constructor.</li>
 *   <li>Case-insensitive provider matching so adapter code doesn't have to normalise upstream.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BridgeAccessGuard - per-user gate for CLI bridges")
class BridgeAccessGuardTest {

    @Mock private BridgeAccessClient client;

    private BridgeAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new BridgeAccessGuard(client);
    }

    @Test
    @DisplayName("isBridgeProvider recognises the four CLI bridges case-insensitively and rejects others")
    void recognisesBridgeProviders() {
        assertThat(BridgeAccessGuard.isBridgeProvider("claude-code")).isTrue();
        assertThat(BridgeAccessGuard.isBridgeProvider("CODEX")).isTrue();
        assertThat(BridgeAccessGuard.isBridgeProvider("Gemini-CLI")).isTrue();
        assertThat(BridgeAccessGuard.isBridgeProvider("mistral-vibe")).isTrue();

        assertThat(BridgeAccessGuard.isBridgeProvider("openai")).isFalse();
        assertThat(BridgeAccessGuard.isBridgeProvider("anthropic")).isFalse();
        assertThat(BridgeAccessGuard.isBridgeProvider(null)).isFalse();
        assertThat(BridgeAccessGuard.isBridgeProvider("")).isFalse();
    }

    @Test
    @DisplayName("enforce: non-bridge providers short-circuit without hitting the auth-service client")
    void nonBridgeShortCircuits() {
        assertThatCode(() -> guard.enforce("u1", "USER", "openai", true))
                .doesNotThrowAnyException();

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("enforce: bridge allow-decision propagates silently and consumes the quota")
    void bridgeAllowPassesThrough() {
        when(client.check(eq("u1"), eq("USER"), eq("claude-code"), eq(true)))
                .thenReturn(BridgeAccessDecision.allow("claude-code", 42));

        assertThatCode(() -> guard.enforce("u1", "USER", "claude-code", true))
                .doesNotThrowAnyException();

        verify(client).check("u1", "USER", "claude-code", true);
    }

    @Test
    @DisplayName("enforce: bridge deny-decision raises BridgeAccessDeniedException with the reason preserved")
    void bridgeDenyRaises() {
        when(client.check(any(), any(), anyString(), anyBoolean()))
                .thenReturn(BridgeAccessDecision.deny("codex",
                        BridgeAccessDecision.REASON_NOT_ALLOWLISTED));

        assertThatThrownBy(() -> guard.enforce("u1", "USER", "codex", true))
                .isInstanceOf(BridgeAccessDeniedException.class)
                .hasMessageContaining("codex")
                .hasMessageContaining(BridgeAccessDecision.REASON_NOT_ALLOWLISTED);
    }

    @Test
    @DisplayName("enforce: quota-exhausted deny surfaces via isQuotaExhausted() so callers can return 429")
    void quotaExhaustedIsClassified() {
        when(client.check(any(), any(), anyString(), anyBoolean()))
                .thenReturn(BridgeAccessDecision.deny("gemini-cli",
                        BridgeAccessDecision.REASON_QUOTA_EXHAUSTED));

        assertThatThrownBy(() -> guard.enforce("u1", "USER", "gemini-cli", true))
                .isInstanceOf(BridgeAccessDeniedException.class)
                .satisfies(e -> {
                    BridgeAccessDeniedException bade = (BridgeAccessDeniedException) e;
                    assertThat(bade.isQuotaExhausted()).isTrue();
                });
    }

    @Test
    @DisplayName("enforce: provider name is lowercased before hitting the client")
    void providerNameNormalised() {
        when(client.check(any(), any(), eq("claude-code"), anyBoolean()))
                .thenReturn(BridgeAccessDecision.allow("claude-code", null));

        guard.enforce("u1", "USER", "Claude-Code", true);

        ArgumentCaptor<String> providerCap = ArgumentCaptor.forClass(String.class);
        verify(client).check(any(), any(), providerCap.capture(), anyBoolean());
        assertThat(providerCap.getValue()).isEqualTo("claude-code");
    }

    @Test
    @DisplayName("check: pre-flight probe never increments the daily counter")
    void checkDoesNotIncrement() {
        when(client.check(any(), any(), anyString(), eq(false)))
                .thenReturn(BridgeAccessDecision.allow("codex", 10));

        BridgeAccessDecision d = guard.check("u1", "USER", "codex");

        assertThat(d.allowed()).isTrue();
        verify(client).check("u1", "USER", "codex", false);
    }

    @Test
    @DisplayName("Default constructor + null client + bridge provider → fail-CLOSED with guard_unavailable")
    void defaultCtorFailsClosedOnNullClient() {
        // Rationale: a deployment that advertises a bridge provider MUST not let a
        // bean-wiring accident become a silent bypass of the shared-subscription
        // quota. Default behaviour is deny with the typed reason so upstream can
        // return a 403 and the admin sees the misconfig in logs.
        BridgeAccessGuard noClientGuard = new BridgeAccessGuard(null);

        assertThatThrownBy(() -> noClientGuard.enforce("u1", "USER", "claude-code", true))
                .isInstanceOf(BridgeAccessDeniedException.class)
                .satisfies(e -> {
                    BridgeAccessDeniedException bade = (BridgeAccessDeniedException) e;
                    assertThat(bade.getReason()).isEqualTo("guard_unavailable");
                    assertThat(bade.getProviderName()).isEqualTo("claude-code");
                });
    }

    @Test
    @DisplayName("Null client with failClosedWhenClientAbsent=false → legacy no-op allow (cloud opt-out)")
    void explicitOptOutAllowsWhenClientNull() {
        // Rationale: cloud agent-service has no bridges installed, so the guard bean
        // still registers but with a null client. Passing false via config opts out
        // of fail-CLOSED so non-bridge traffic there is untouched. Bridge traffic
        // wouldn't reach this call-site in cloud anyway (no CLI registered).
        BridgeAccessGuard optOutGuard = new BridgeAccessGuard(null, false);

        assertThatCode(() -> optOutGuard.enforce("u1", "USER", "claude-code", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Null client on non-bridge provider → short-circuits before the fail-closed check")
    void nullClientNonBridgeShortCircuits() {
        // Rationale: the isBridgeProvider short-circuit must come BEFORE the null-client
        // fail-closed gate, otherwise every OpenAI request in a misconfigured deploy
        // would deny. This regression-guards that ordering.
        BridgeAccessGuard noClientGuard = new BridgeAccessGuard(null);

        assertThatCode(() -> noClientGuard.enforce("u1", "USER", "openai", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforce(incrementUsage=false): bridge check runs but does not consume quota")
    void enforceWithoutIncrement() {
        when(client.check(any(), any(), anyString(), eq(false)))
                .thenReturn(BridgeAccessDecision.allow("mistral-vibe", null));

        guard.enforce("u1", "USER", "mistral-vibe", false);

        verify(client).check("u1", "USER", "mistral-vibe", false);
        verify(client, never()).check(any(), any(), anyString(), eq(true));
    }
}
