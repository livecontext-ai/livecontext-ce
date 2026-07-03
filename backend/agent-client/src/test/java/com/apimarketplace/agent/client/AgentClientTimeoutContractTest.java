package com.apimarketplace.agent.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: the sync execution endpoints' read timeout is the total wall-clock
 * budget a caller (orchestrator, conversation-service) grants an agent run over
 * HTTP. The previous 65-min value sat BELOW the executionTimeout/inactivityTimeout
 * contract maximum (7200s), so a valid 2h budget could never elapse on the sync
 * path - the caller aborted the socket first.
 */
@DisplayName("AgentClient - execution read timeout covers the timeout contract")
class AgentClientTimeoutContractTest {

    /** Contract maximum for executionTimeout AND inactivityTimeout (AgentService validation). */
    private static final Duration CONTRACT_MAX = Duration.ofSeconds(7200);

    /** Bridge hard cap default (mcp/bridge/server.mjs BRIDGE_MAX_TIMEOUT_MS) - the longest downstream leg. */
    private static final Duration BRIDGE_CAP = Duration.ofMinutes(125);

    @Test
    @DisplayName("read timeout exceeds the 7200s executionTimeout/inactivityTimeout contract maximum")
    void readTimeoutCoversContractMax() {
        assertThat(AgentClient.EXECUTION_READ_TIMEOUT).isGreaterThan(CONTRACT_MAX);
    }

    @Test
    @DisplayName("read timeout sits above the downstream 125-min bridge cap so typed bridge timeouts win")
    void readTimeoutAboveBridgeCap() {
        assertThat(AgentClient.EXECUTION_READ_TIMEOUT).isGreaterThan(BRIDGE_CAP);
    }
}
