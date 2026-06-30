package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent Execution Signal")
class AgentExecutionSignalTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    // ========================================================================
    // SignalType
    // ========================================================================

    @Nested
    @DisplayName("SignalType enum")
    class SignalTypeTests {

        @Test
        @DisplayName("AGENT_EXECUTION value exists")
        void agentExecutionValueExists() {
            assertNotNull(SignalType.AGENT_EXECUTION);
            assertEquals("AGENT_EXECUTION", SignalType.AGENT_EXECUTION.name());
        }

        @Test
        @DisplayName("All original values still exist")
        void originalValuesExist() {
            assertNotNull(SignalType.WAIT_TIMER);
            assertNotNull(SignalType.USER_APPROVAL);
            assertNotNull(SignalType.WEBHOOK_WAIT);
            assertNotNull(SignalType.INTERFACE_SIGNAL);
        }
    }

    // ========================================================================
    // SignalResolution
    // ========================================================================

    @Nested
    @DisplayName("SignalResolution enum")
    class SignalResolutionTests {

        @Test
        @DisplayName("AGENT_COMPLETED value exists")
        void agentCompletedExists() {
            assertNotNull(SignalResolution.AGENT_COMPLETED);
            assertEquals("AGENT_COMPLETED", SignalResolution.AGENT_COMPLETED.name());
        }

        @Test
        @DisplayName("AGENT_FAILED value exists")
        void agentFailedExists() {
            assertNotNull(SignalResolution.AGENT_FAILED);
            assertEquals("AGENT_FAILED", SignalResolution.AGENT_FAILED.name());
        }

        @Test
        @DisplayName("All original values still exist")
        void originalValuesExist() {
            assertNotNull(SignalResolution.COMPLETED);
            assertNotNull(SignalResolution.APPROVED);
            assertNotNull(SignalResolution.REJECTED);
            assertNotNull(SignalResolution.TIMEOUT);
            assertNotNull(SignalResolution.CANCELLED);
            assertNotNull(SignalResolution.ACTION_FIRED);
            assertNotNull(SignalResolution.CONTINUE);
        }
    }

    // ========================================================================
    // SignalConfig.agentExecution()
    // ========================================================================

    @Nested
    @DisplayName("SignalConfig.agentExecution()")
    class SignalConfigAgentExecutionTests {

        @Test
        @DisplayName("Should create agent execution config with all fields")
        void shouldCreateAgentExecutionConfig() {
            Map<String, Object> config = SignalConfig.agentExecution(
                    "req-123", "agent_loop", "openai", "gpt-4o",
                    Duration.ofMinutes(70));

            assertEquals("AGENT_EXECUTION", config.get("type"));
            assertEquals("req-123", config.get("correlationId"));
            assertEquals("agent_loop", config.get("agentType"));
            assertEquals("openai", config.get("provider"));
            assertEquals("gpt-4o", config.get("model"));
            assertEquals(4200000L, config.get("timeoutMs"));
            assertEquals(true, config.get("blocking"));
        }

        @Test
        @DisplayName("Should use default 70 min timeout when null")
        void shouldUseDefaultTimeout() {
            Map<String, Object> config = SignalConfig.agentExecution(
                    "req-456", "classify", "anthropic", "claude-3-5-sonnet",
                    null);

            assertEquals(4200000L, SignalConfig.getTimeoutMs(config)); // 70 min default
        }

        @Test
        @DisplayName("Should read typed accessors from agent execution config")
        void shouldReadAccessors() {
            Map<String, Object> config = SignalConfig.agentExecution(
                    "req-789", "guardrail", "anthropic", "claude-3-haiku",
                    Duration.ofSeconds(120));

            assertEquals(SignalType.AGENT_EXECUTION, SignalConfig.getType(config));
            assertEquals("req-789", SignalConfig.getCorrelationId(config));
            assertEquals("guardrail", SignalConfig.getAgentType(config));
            assertEquals("anthropic", SignalConfig.getProvider(config));
            assertEquals("claude-3-haiku", SignalConfig.getModel(config));
            assertEquals(120000L, SignalConfig.getTimeoutMs(config));
            assertTrue(SignalConfig.isBlocking(config));
        }

        @Test
        @DisplayName("Should always be blocking")
        void shouldAlwaysBeBlocking() {
            Map<String, Object> config = SignalConfig.agentExecution(
                    "req-abc", "agent_loop", "openai", "gpt-4o", null);

            assertTrue(SignalConfig.isBlocking(config));
        }

        @Test
        @DisplayName("Null config accessors return null/defaults")
        void shouldHandleNullConfig() {
            assertNull(SignalConfig.getCorrelationId(null));
            assertNull(SignalConfig.getAgentType(null));
            assertNull(SignalConfig.getProvider(null));
            assertNull(SignalConfig.getModel(null));
        }
    }

    // ========================================================================
    // SignalWaitEntity.forAgentExecution()
    // ========================================================================

    @Nested
    @DisplayName("SignalWaitEntity.forAgentExecution()")
    class SignalWaitEntityAgentExecutionTests {

        @Test
        @DisplayName("Should create entity with all required fields")
        void shouldCreateEntity() {
            SignalWaitEntity entity = SignalWaitEntity.forAgentExecution(
                    "run-1", "item-1", "agent:my_agent",
                    "trigger:start", 1,
                    "corr-123", "agent_loop",
                    "openai", "gpt-4o",
                    Duration.ofMinutes(70), FIXED_CLOCK);

            assertEquals("run-1", entity.getRunId());
            assertEquals("item-1", entity.getItemId());
            assertEquals("agent:my_agent", entity.getNodeId());
            assertEquals("trigger:start", entity.getDagTriggerId());
            assertEquals(1, entity.getEpoch());
            assertEquals(SignalType.AGENT_EXECUTION, entity.getSignalType());
            assertEquals(SignalWaitEntity.SignalWaitStatus.PENDING, entity.getStatus());
            assertTrue(entity.isBlocking());
            assertEquals("corr-123", entity.getCorrelationId());
            assertNotNull(entity.getCreatedAt());
            assertNotNull(entity.getExpiresAt());
        }

        @Test
        @DisplayName("Should set expiration based on timeout")
        void shouldSetExpiration() {
            Duration timeout = Duration.ofSeconds(120);
            SignalWaitEntity entity = SignalWaitEntity.forAgentExecution(
                    "run-1", "item-1", "agent:classify",
                    "trigger:start", 1,
                    "corr-456", "classify",
                    "anthropic", "claude-3-5-sonnet",
                    timeout, FIXED_CLOCK);

            Instant expectedExpiry = FIXED_CLOCK.instant().plus(timeout);
            assertEquals(expectedExpiry, entity.getExpiresAt());
        }

        @Test
        @DisplayName("Should use default 70 min expiration when timeout is null")
        void shouldUseDefaultExpiration() {
            SignalWaitEntity entity = SignalWaitEntity.forAgentExecution(
                    "run-1", "item-1", "agent:my_agent",
                    "trigger:start", 1,
                    "corr-789", "agent_loop",
                    "openai", "gpt-4o",
                    null, FIXED_CLOCK);

            Instant expectedExpiry = FIXED_CLOCK.instant().plus(Duration.ofMinutes(70));
            assertEquals(expectedExpiry, entity.getExpiresAt());
        }

        @Test
        @DisplayName("Should store correlation ID in both entity field and signal config")
        void shouldStoreCorrelationIdInBothPlaces() {
            SignalWaitEntity entity = SignalWaitEntity.forAgentExecution(
                    "run-1", "item-1", "agent:my_agent",
                    "trigger:start", 1,
                    "corr-dual", "agent_loop",
                    "openai", "gpt-4o",
                    Duration.ofMinutes(70), FIXED_CLOCK);

            assertEquals("corr-dual", entity.getCorrelationId());
            assertEquals("corr-dual", SignalConfig.getCorrelationId(entity.getSignalConfig()));
        }
    }
}
