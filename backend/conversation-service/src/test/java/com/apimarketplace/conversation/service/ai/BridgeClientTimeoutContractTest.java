package com.apimarketplace.conversation.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: the blocking bridge {@code /execute} POST is the run's total
 * wall-clock budget as seen from the chat path. The previous 65-min read timeout
 * sat BELOW the executionTimeout/inactivityTimeout contract maximum (7200s), so
 * a valid 2h budget could never elapse on a bridge chat run - the client aborted
 * the socket first.
 */
@DisplayName("BridgeClient - execution read timeout covers the timeout contract")
class BridgeClientTimeoutContractTest {

    /** Contract maximum for executionTimeout AND inactivityTimeout (AgentService validation). */
    private static final Duration CONTRACT_MAX = Duration.ofSeconds(7200);

    /** Bridge hard cap default (mcp/bridge/server.mjs BRIDGE_MAX_TIMEOUT_MS). */
    private static final Duration BRIDGE_CAP = Duration.ofMinutes(125);

    @Test
    @DisplayName("read timeout exceeds the 7200s executionTimeout/inactivityTimeout contract maximum")
    void readTimeoutCoversContractMax() {
        assertThat(BridgeClient.EXECUTION_READ_TIMEOUT).isGreaterThan(CONTRACT_MAX);
    }

    @Test
    @DisplayName("read timeout sits above the bridge's own 125-min hard cap so the bridge's typed timeout wins")
    void readTimeoutAboveBridgeCap() {
        assertThat(BridgeClient.EXECUTION_READ_TIMEOUT).isGreaterThan(BRIDGE_CAP);
    }
}
