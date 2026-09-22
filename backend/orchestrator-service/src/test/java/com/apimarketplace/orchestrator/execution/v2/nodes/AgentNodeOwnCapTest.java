package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.dto.AgentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a workflow agent node starts an agent that has spent its own cap.
 *
 * <p>The gate itself is not new: {@code AgentNode} has refused here for a long time, and a
 * refusal FAILS the node with a {@code BUDGET_EXHAUSTED} message, so a workflow stops on it
 * rather than carrying on with an empty answer. What was new is that nothing tested it, and
 * that it decided the question differently from every other place that asks it.
 */
@DisplayName("AgentNode - the agent's own credit cap")
class AgentNodeOwnCapTest {

    private static AgentDto dto(String cap, String consumed, Boolean verdict) {
        AgentDto agent = new AgentDto();
        agent.setCreditBudget(cap == null ? null : new BigDecimal(cap));
        agent.setCreditsConsumed(consumed == null ? null : new BigDecimal(consumed));
        agent.setBudgetBlocked(verdict);
        return agent;
    }

    @Test
    @DisplayName("the server verdict decides when it is present, even against the raw figures")
    void theVerdictWins() {
        // The case the raw comparison gets wrong in BOTH directions.
        //
        // Blocked while the figures look fine: credits_reserved is committed to an in-flight
        // sub-agent and never reaches this DTO, so only the verdict knows. Starting the run
        // here just moves the refusal one iteration later, into AgentBudgetGuard.
        assertThat(AgentNode.isOverItsOwnCap(dto("10", "4", true))).isTrue();

        // Not blocked while the figures look spent: the accumulator is zeroed lazily, at the
        // next run, so a monthly agent that reached its cap last month still reads at the cap
        // and is about to run perfectly well. Refusing it would idle a workflow for a month.
        assertThat(AgentNode.isOverItsOwnCap(dto("10", "10", false))).isFalse();
    }

    @Test
    @DisplayName("an absent verdict falls back to the comparison this node has always made")
    void anAbsentVerdictKeepsTheOldBehaviour() {
        // A rolling deploy puts a new orchestrator in front of an old agent-service, which
        // sends no verdict. Reading that silence as "not blocked" would stop gating workflow
        // agent nodes for the length of the rollout, which is the one direction a cap must
        // never fail.
        assertThat(AgentNode.isOverItsOwnCap(dto("10", "10", null))).isTrue();
        assertThat(AgentNode.isOverItsOwnCap(dto("10", "9.9999", null))).isFalse();
    }

    @Test
    @DisplayName("an uncapped agent is never refused, by either route")
    void anUncappedAgentRuns() {
        // Unreachable through the ONLY production call site, which wraps this in
        // `creditBudget != null`, and kept anyway: this is the helper contract, and the guard
        // that stops a future second caller from reintroducing the outcome the wrapper
        // currently prevents. Stated rather than dressed up as a production path.
        assertThat(AgentNode.isOverItsOwnCap(dto(null, "9999", null))).isFalse();
        assertThat(AgentNode.isOverItsOwnCap(dto(null, "9999", false))).isFalse();
    }

    @Test
    @DisplayName("an agent that could not be resolved does not block the node")
    void anUnresolvableAgentFailsOpen() {
        // Same status as the case above: the caller null-checks first, so this cannot fail
        // today. It pins the helper own fail-open posture, which is the property any second
        // caller would depend on.
        assertThat(AgentNode.isOverItsOwnCap(null)).isFalse();
    }

    @Test
    @DisplayName("a capped agent that has spent nothing runs")
    void aFreshCappedAgentRuns() {
        assertThat(AgentNode.isOverItsOwnCap(dto("500", "0", false))).isFalse();
        assertThat(AgentNode.isOverItsOwnCap(dto("500", "0", null))).isFalse();
    }
}
