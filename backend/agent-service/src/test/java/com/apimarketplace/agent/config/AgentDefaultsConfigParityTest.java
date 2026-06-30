package com.apimarketplace.agent.config;

import com.apimarketplace.agent.loop.LoopDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test between {@link AgentDefaultsConfig}'s hard-coded loop thresholds and the
 * {@link LoopDetector#DEFAULT_STOP_THRESHOLD} / {@link LoopDetector#DEFAULT_CONSECUTIVE_STOP}
 * constants.
 *
 * <p>Why this test exists: when an agent has no per-agent override (V100 columns are NULL),
 * two different code paths pick the default:
 * <ul>
 *   <li>agent-service tool modules (AgentCrudModule/SkillCrudModule/SubAgentExecutionHandler)
 *       read AgentDefaultsConfig from YAML.</li>
 *   <li>shared-agent-lib {@code AgentLoopService} falls back to the {@code LoopDetector.DEFAULT_*}
 *       constants - conversation-service does not hold an {@code AgentDefaultsConfig} bean, so
 *       the YAML values never reach it.</li>
 * </ul>
 *
 * <p>If these two sources drift (e.g. ops edits {@code application.yml} to raise the default),
 * agent-service enforces one threshold while conversation-service enforces another, silently.
 * This test pins the constants to the YAML defaults so any future edit to
 * {@code AgentDefaultsConfig} fails here until the LoopDetector constant is updated in lockstep.
 */
@DisplayName("AgentDefaultsConfig ↔ LoopDetector default parity")
class AgentDefaultsConfigParityTest {

    @Test
    @DisplayName("loopIdenticalStop default matches LoopDetector.DEFAULT_STOP_THRESHOLD")
    void identicalStopParity() {
        AgentDefaultsConfig config = new AgentDefaultsConfig();
        assertThat(config.getLoopIdenticalStop())
            .as("AgentDefaultsConfig.loopIdenticalStop and LoopDetector.DEFAULT_STOP_THRESHOLD "
                + "must match - they are the same platform default seen from two code paths "
                + "(agent-service YAML vs shared-agent-lib constant). Update both together.")
            .isEqualTo(LoopDetector.DEFAULT_STOP_THRESHOLD);
    }

    @Test
    @DisplayName("loopConsecutiveStop default matches LoopDetector.DEFAULT_CONSECUTIVE_STOP")
    void consecutiveStopParity() {
        AgentDefaultsConfig config = new AgentDefaultsConfig();
        assertThat(config.getLoopConsecutiveStop())
            .as("AgentDefaultsConfig.loopConsecutiveStop and LoopDetector.DEFAULT_CONSECUTIVE_STOP "
                + "must match - they are the same platform default seen from two code paths "
                + "(agent-service YAML vs shared-agent-lib constant). Update both together.")
            .isEqualTo(LoopDetector.DEFAULT_CONSECUTIVE_STOP);
    }
}
