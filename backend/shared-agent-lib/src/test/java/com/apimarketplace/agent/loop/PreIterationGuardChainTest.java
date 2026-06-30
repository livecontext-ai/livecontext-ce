package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentStopReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PreIterationGuard.chain")
class PreIterationGuardChainTest {

    private static IterationContext ctx() {
        return new IterationContext(
            "tenant-1", "agent-1", "openai", "gpt-test", 1, 0, 0, 0, 0L);
    }

    @Test
    @DisplayName("empty chain yields ALWAYS_PROCEED")
    void emptyChain() {
        assertThat(PreIterationGuard.chain().check(ctx()).proceed()).isTrue();
    }

    @Test
    @DisplayName("null entries are ignored")
    void allNullEntries() {
        PreIterationGuard chain = PreIterationGuard.chain(null, null, null);
        assertThat(chain.check(ctx()).proceed()).isTrue();
    }

    @Test
    @DisplayName("single guard chain delegates directly")
    void singleGuard() {
        PreIterationGuard guard = c -> GuardResult.deny(AgentStopReason.ERROR, "test", "boom");
        PreIterationGuard chain = PreIterationGuard.chain(guard);
        GuardResult r = chain.check(ctx());
        assertThat(r.proceed()).isFalse();
        assertThat(r.scope()).isEqualTo("test");
    }

    @Test
    @DisplayName("first deny short-circuits subsequent guards")
    void firstDenyShortCircuits() {
        AtomicInteger secondCalls = new AtomicInteger();

        PreIterationGuard tenant = c -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "tenant", "no money");
        PreIterationGuard agent = c -> {
            secondCalls.incrementAndGet();
            return GuardResult.allow();
        };

        PreIterationGuard chain = PreIterationGuard.chain(tenant, agent);
        GuardResult r = chain.check(ctx());

        assertThat(r.proceed()).isFalse();
        assertThat(r.scope()).isEqualTo("tenant");
        assertThat(secondCalls.get()).isZero();
    }

    @Test
    @DisplayName("second guard runs when first allows")
    void secondGuardRunsWhenFirstAllows() {
        AtomicInteger secondCalls = new AtomicInteger();

        PreIterationGuard tenant = c -> GuardResult.allow();
        PreIterationGuard agent = c -> {
            secondCalls.incrementAndGet();
            return GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "agent", "no money on agent");
        };

        PreIterationGuard chain = PreIterationGuard.chain(tenant, agent);
        GuardResult r = chain.check(ctx());

        assertThat(secondCalls.get()).isOne();
        assertThat(r.proceed()).isFalse();
        assertThat(r.scope()).isEqualTo("agent");
    }

    @Test
    @DisplayName("all-allow chain proceeds")
    void allAllowChainProceeds() {
        PreIterationGuard chain = PreIterationGuard.chain(
            c -> GuardResult.allow(),
            c -> GuardResult.allow(),
            c -> GuardResult.allow()
        );
        assertThat(chain.check(ctx()).proceed()).isTrue();
    }

    @Test
    @DisplayName("tenant wins when tenant + agent both deny on same iteration")
    void tenantWinsOverAgentWhenBothDeny() {
        AtomicInteger agentCalls = new AtomicInteger();
        PreIterationGuard tenant = c -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "tenant", "tenant out");
        PreIterationGuard agent = c -> {
            agentCalls.incrementAndGet();
            return GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "agent", "agent out");
        };
        PreIterationGuard chain = PreIterationGuard.chain(tenant, agent);
        GuardResult r = chain.check(ctx());
        assertThat(r.proceed()).isFalse();
        assertThat(r.scope()).isEqualTo("tenant");
        assertThat(r.denialReason()).isEqualTo("tenant out");
        assertThat(agentCalls.get()).isZero();
    }

    @Test
    @DisplayName("null entries among real guards are skipped")
    void mixedNullAndReal() {
        PreIterationGuard chain = PreIterationGuard.chain(
            null,
            c -> GuardResult.allow(),
            null,
            c -> GuardResult.deny(AgentStopReason.LOOP_DETECTED, "loop", "spinning")
        );
        GuardResult r = chain.check(ctx());
        assertThat(r.proceed()).isFalse();
        assertThat(r.stopReason()).isEqualTo(AgentStopReason.LOOP_DETECTED);
    }
}
