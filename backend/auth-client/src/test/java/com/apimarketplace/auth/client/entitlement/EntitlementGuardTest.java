package com.apimarketplace.auth.client.entitlement;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.AuthClient.PlanLimitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EntitlementGuardTest {

    private AuthClient authClient;

    @BeforeEach
    void setUp() {
        authClient = mock(AuthClient.class);
    }

    @Test
    void ceFreeNoopMode_isNoOp() {
        EntitlementGuard guard = new EntitlementGuard(authClient, true);
        AtomicInteger counter = new AtomicInteger(0);

        guard.check("u1", ResourceType.WORKFLOW, () -> {
            counter.incrementAndGet();
            return 9999L;
        });

        assertThat(counter.get()).isZero();
        verifyNoInteractions(authClient);
    }

    @Test
    void blankProviderId_isNoOp() {
        EntitlementGuard guard = new EntitlementGuard(authClient, false);
        guard.check(null, ResourceType.WORKFLOW, () -> 1L);
        guard.check("", ResourceType.WORKFLOW, () -> 1L);
        verifyNoInteractions(authClient);
    }

    @Test
    void blankProviderId_failsClosedWhenConfigured() {
        EntitlementGuard guard = new EntitlementGuard(authClient, false, true);

        assertThatThrownBy(() -> guard.check("", ResourceType.WORKFLOW, () -> 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing provider id");

        verifyNoInteractions(authClient);
    }

    @Test
    void authClientReturnsNull_failsOpen() {
        when(authClient.getResourceLimit("u1", "WORKFLOW")).thenReturn(null);
        EntitlementGuard guard = new EntitlementGuard(authClient, false);

        guard.check("u1", ResourceType.WORKFLOW, () -> {
            throw new AssertionError("counter must not be invoked");
        });
    }

    @Test
    void authClientReturnsNull_failsClosedWhenConfigured() {
        when(authClient.getResourceLimit("u1", "WORKFLOW")).thenReturn(null);
        EntitlementGuard guard = new EntitlementGuard(authClient, false, true);

        assertThatThrownBy(() -> guard.check("u1", ResourceType.WORKFLOW, () -> 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Entitlement lookup unavailable")
                .hasMessageContaining("WORKFLOW");
    }

    @Test
    void noSubscriptionPlanCode_failsClosedWhenConfigured() {
        when(authClient.getResourceLimit("u1", "WORKFLOW")).thenReturn(new PlanLimitResponse("__NONE__", null));
        EntitlementGuard guard = new EntitlementGuard(authClient, false, true);

        assertThatThrownBy(() -> guard.check("u1", ResourceType.WORKFLOW, () -> 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active plan");
    }

    @Test
    void unlimitedPlan_doesNotInvokeCounter() {
        when(authClient.getResourceLimit("u1", "AGENT")).thenReturn(new PlanLimitResponse("ENTERPRISE", null));
        EntitlementGuard guard = new EntitlementGuard(authClient, false);

        guard.check("u1", ResourceType.AGENT, () -> {
            throw new AssertionError("counter must not be invoked for unlimited plan");
        });
    }

    @Test
    void belowLimit_allowsCreation() {
        when(authClient.getResourceLimit("u1", "DATASOURCE")).thenReturn(new PlanLimitResponse("FREE", 5));
        EntitlementGuard guard = new EntitlementGuard(authClient, false);

        guard.check("u1", ResourceType.DATASOURCE, () -> 4L);
    }

    @Test
    void atLimit_throwsLimitExceededException() {
        when(authClient.getResourceLimit("u1", "WORKFLOW")).thenReturn(new PlanLimitResponse("FREE", 5));
        EntitlementGuard guard = new EntitlementGuard(authClient, false);

        assertThatThrownBy(() -> guard.check("u1", ResourceType.WORKFLOW, () -> 5L))
                .isInstanceOf(LimitExceededException.class)
                .satisfies(ex -> {
                    LimitExceededException lex = (LimitExceededException) ex;
                    assertThat(lex.payload().resourceType()).isEqualTo(ResourceType.WORKFLOW);
                    assertThat(lex.payload().planCode()).isEqualTo("FREE");
                    assertThat(lex.payload().limit()).isEqualTo(5);
                    assertThat(lex.payload().currentCount()).isEqualTo(5);
                    assertThat(lex.payload().upgradeHint()).contains("STARTER");
                    assertThat(lex.getMessage()).contains("DO NOT RETRY");
                });
    }

    @Test
    void publicationAtLimit_throwsWithActionableHint() {
        // The marketplace PUBLISH cap (distinct from APPLICATION = acquire).
        when(authClient.getResourceLimit("u1", "PUBLICATION")).thenReturn(new PlanLimitResponse("PRO", 300));
        EntitlementGuard guard = new EntitlementGuard(authClient, false);

        assertThatThrownBy(() -> guard.check("u1", ResourceType.PUBLICATION, () -> 300L))
                .isInstanceOf(LimitExceededException.class)
                .satisfies(ex -> {
                    LimitExceededException lex = (LimitExceededException) ex;
                    assertThat(lex.payload().resourceType()).isEqualTo(ResourceType.PUBLICATION);
                    assertThat(lex.payload().planCode()).isEqualTo("PRO");
                    assertThat(lex.payload().limit()).isEqualTo(300);
                    assertThat(lex.payload().currentCount()).isEqualTo(300);
                    assertThat(lex.payload().upgradeHint()).contains("TEAM");
                });
    }

    @Test
    void publicationUnlimitedInCeFree() {
        // CE-free publishes without limit - the guard no-ops, never consulting auth.
        EntitlementGuard guard = new EntitlementGuard(authClient, true);
        guard.check("u1", ResourceType.PUBLICATION, () -> {
            throw new AssertionError("CE-free must not count publications");
        });
        verifyNoInteractions(authClient);
    }

    @Test
    void overLimit_throwsLimitExceededException() {
        when(authClient.getResourceLimit("u1", "INTERFACE")).thenReturn(new PlanLimitResponse("STARTER", 10));
        EntitlementGuard guard = new EntitlementGuard(authClient, false);

        assertThatThrownBy(() -> guard.check("u1", ResourceType.INTERFACE, () -> 12L))
                .isInstanceOf(LimitExceededException.class);
    }

    @Test
    void upgradeHint_followsPlanLadder() {
        EntitlementGuard guard = new EntitlementGuard(authClient, false);

        assertHintFor(guard, "FREE", "STARTER");
        assertHintFor(guard, "STARTER", "PRO");
        assertHintFor(guard, "PRO", "TEAM");
        assertHintFor(guard, "TEAM", "ENTERPRISE");
    }

    private void assertHintFor(EntitlementGuard guard, String planCode, String expectedNext) {
        when(authClient.getResourceLimit("u1", "WORKFLOW")).thenReturn(new PlanLimitResponse(planCode, 1));
        try {
            guard.check("u1", ResourceType.WORKFLOW, () -> 1L);
        } catch (LimitExceededException e) {
            assertThat(e.payload().upgradeHint()).contains(expectedNext);
        }
    }
}
