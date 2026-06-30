package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CreditConsumptionClient#persistRejection}.
 *
 * <p>Why this method exists: when a synchronous {@code consumeCredits} call returns
 * {@code {success=false, error="402 ..."}} (soft rejection - auth-service said "no"
 * without throwing), the caller has already burned tokens that must be captured in the
 * dead-letter table for audit. Using {@code consumeCreditsAsync} for this is wrong -
 * it retries 3 times hitting the same 402. This method persists directly, once,
 * without retry, and swallows handler exceptions so the caller's main flow is safe.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient.persistRejection")
class CreditConsumptionClientPersistRejectionTest {

    @Mock private CreditDeadLetterHandler handler;

    private CreditConsumptionClient buildClient(boolean enabled, boolean wireHandler) {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth:8083", enabled);
        if (wireHandler) {
            client.setDeadLetterHandler(handler);
        }
        return client;
    }

    @Test
    @DisplayName("Delegates to dead-letter handler with all fields preserved (9-arg incl. orgId)")
    void delegatesToHandler() {
        CreditConsumptionClient client = buildClient(true, true);

        client.persistRejection("tenant-1", "CHAT_CONVERSATION", "exec-123",
                "claude-code", "claude-opus-4-6", 2_000_000, 10_000,
                "402 Insufficient credits");

        // Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2): client routes the 8-arg
        // call through the 9-arg internal overload. With no RequestContextHolder
        // bound in this unit test, the captured orgId is null - handler still
        // receives the 9-arg form.
        verify(handler).persistFailedConsumption(
                eq("tenant-1"),
                eq("CHAT_CONVERSATION"),
                eq("exec-123"),
                eq("claude-code"),
                eq("claude-opus-4-6"),
                eq(2_000_000),
                eq(10_000),
                eq("402 Insufficient credits"),
                isNull());
    }

    @Test
    @DisplayName("9-arg explicit-orgId overload threads organizationId to handler")
    void delegatesExplicitOrgIdToHandler() {
        CreditConsumptionClient client = buildClient(true, true);

        client.persistRejection("tenant-1", "AGENT_EXECUTION", "exec-456",
                "openai", "gpt-4o", 500, 100,
                "402 Insufficient credits", "org-uuid-7");

        verify(handler).persistFailedConsumption(
                eq("tenant-1"),
                eq("AGENT_EXECUTION"),
                eq("exec-456"),
                eq("openai"),
                eq("gpt-4o"),
                eq(500),
                eq(100),
                eq("402 Insufficient credits"),
                eq("org-uuid-7"));
    }

    @Test
    @DisplayName("No-op when client is disabled (no handler call, no throw)")
    void noOpWhenDisabled() {
        CreditConsumptionClient client = buildClient(false, true);

        client.persistRejection("tenant-1", "CHAT_CONVERSATION", "exec-123",
                "claude-code", "claude-opus-4-6", 1, 1, "402");

        verify(handler, never()).persistFailedConsumption(
                any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("No-op when handler is not wired (no throw)")
    void noOpWhenHandlerNull() {
        CreditConsumptionClient client = buildClient(true, false);

        // Must not throw - serves as fallback when the service forgot to @PostConstruct
        // wire the dead-letter handler. Logging-only behaviour.
        client.persistRejection("tenant-1", "AGENT_EXECUTION", "exec-123",
                "openai", "gpt-4o", 1, 1, "402");
    }

    @Test
    @DisplayName("Swallows handler exceptions so the caller's main path is never broken")
    void swallowsHandlerExceptions() {
        CreditConsumptionClient client = buildClient(true, true);
        doThrow(new RuntimeException("DB connection lost"))
                .when(handler).persistFailedConsumption(
                        any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());

        // Must not throw - the caller is in the post-flight billing pipeline; a
        // dead-letter write failure should not mask the primary execution outcome.
        client.persistRejection("tenant-1", "AGENT_EXECUTION", "exec-123",
                "openai", "gpt-4o", 1, 1, "402");

        verify(handler).persistFailedConsumption(
                any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }
}
