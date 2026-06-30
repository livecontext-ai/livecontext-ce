package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SignalConfig")
class SignalConfigTest {

    @Nested
    @DisplayName("timer()")
    class TimerTests {

        @Test
        @DisplayName("Should create timer config with duration")
        void shouldCreateTimerConfig() {
            Map<String, Object> config = SignalConfig.timer(60_000);

            assertEquals("WAIT_TIMER", config.get("type"));
            assertEquals(60_000L, config.get("durationMs"));
        }

        @Test
        @DisplayName("Should read duration from config")
        void shouldReadDuration() {
            Map<String, Object> config = SignalConfig.timer(30_000);

            assertEquals(30_000L, SignalConfig.getDurationMs(config));
        }
    }

    @Nested
    @DisplayName("userApproval()")
    class UserApprovalTests {

        @Test
        @DisplayName("Should create approval config with all fields")
        void shouldCreateApprovalConfig() {
            Map<String, Object> config = SignalConfig.userApproval(
                List.of("manager", "admin"), 2,
                Duration.ofHours(24));

            assertEquals("USER_APPROVAL", config.get("type"));
            assertEquals(List.of("manager", "admin"), config.get("approverRoles"));
            assertEquals(2, config.get("requiredApprovals"));
            assertEquals(86400000L, config.get("timeoutMs"));
        }

        @Test
        @DisplayName("Should use default timeout when null")
        void shouldUseDefaultTimeout() {
            Map<String, Object> config = SignalConfig.userApproval(
                null, 0, null);

            assertEquals(86400000L, SignalConfig.getTimeoutMs(config)); // 24h default
        }

        @Test
        @DisplayName("Should clamp requiredApprovals to minimum 1")
        void shouldClampMinApprovals() {
            Map<String, Object> config = SignalConfig.userApproval(
                null, 0, null);

            assertEquals(1, SignalConfig.getRequiredApprovals(config));
        }

        @Test
        @DisplayName("Should handle null approverRoles")
        void shouldHandleNullRoles() {
            Map<String, Object> config = SignalConfig.userApproval(
                null, 1, null);

            assertTrue(SignalConfig.getApproverRoles(config).isEmpty());
        }

        @Test
        @DisplayName("Should initialize empty receivedApprovals")
        void shouldInitEmptyReceivedApprovals() {
            Map<String, Object> config = SignalConfig.userApproval(
                List.of("admin"), 1, Duration.ofHours(1));

            assertTrue(SignalConfig.getReceivedApprovals(config).isEmpty());
        }
    }

    @Nested
    @DisplayName("webhookWait()")
    class WebhookWaitTests {

        @Test
        @DisplayName("Should create webhook config with token and timeout")
        void shouldCreateWebhookConfig() {
            Map<String, Object> config = SignalConfig.webhookWait(
                "jwt-token-123", Duration.ofHours(1));

            assertEquals("WEBHOOK_WAIT", config.get("type"));
            assertEquals("jwt-token-123", config.get("webhookToken"));
            assertEquals(3600000L, config.get("timeoutMs"));
        }

        @Test
        @DisplayName("Should use default timeout when null")
        void shouldUseDefaultWebhookTimeout() {
            Map<String, Object> config = SignalConfig.webhookWait(
                "token", null);

            assertEquals(3600000L, SignalConfig.getTimeoutMs(config)); // 1h default
        }
    }

    @Nested
    @DisplayName("Typed accessors")
    class AccessorTests {

        @Test
        @DisplayName("Should return null/defaults for null config")
        void shouldHandleNullConfig() {
            assertNull(SignalConfig.getType(null));
            assertEquals(0L, SignalConfig.getDurationMs(null));
            assertTrue(SignalConfig.getApproverRoles(null).isEmpty());
            assertEquals(1, SignalConfig.getRequiredApprovals(null));
            assertEquals(0L, SignalConfig.getTimeoutMs(null));
            assertNull(SignalConfig.getWebhookToken(null));
            assertTrue(SignalConfig.getReceivedApprovals(null).isEmpty());
        }

        @Test
        @DisplayName("Should parse SignalType from config")
        void shouldParseSignalType() {
            Map<String, Object> timerConfig = SignalConfig.timer(1000);
            assertEquals(SignalType.WAIT_TIMER, SignalConfig.getType(timerConfig));

            Map<String, Object> approvalConfig = SignalConfig.userApproval(
                null, 1, null);
            assertEquals(SignalType.USER_APPROVAL, SignalConfig.getType(approvalConfig));

            Map<String, Object> webhookConfig = SignalConfig.webhookWait("t", null);
            assertEquals(SignalType.WEBHOOK_WAIT, SignalConfig.getType(webhookConfig));
        }
    }

    @Nested
    @DisplayName("browserTakeover()")
    class BrowserTakeoverTests {

        @Test
        @DisplayName("Builds config with type=BROWSER_USER_TAKEOVER and blocking=true")
        void shouldBuildBlockingConfig() {
            Map<String, Object> config = SignalConfig.browserTakeover(
                "ses_01", "run_42", "node_browse", "jwt.cdp.token", Duration.ofMinutes(10));

            assertEquals("BROWSER_USER_TAKEOVER", config.get("type"));
            assertEquals(Boolean.TRUE, config.get("blocking"));
            assertEquals("ses_01", config.get("sessionId"));
            assertEquals("run_42", config.get("runId"));
            assertEquals("node_browse", config.get("nodeId"));
            assertEquals("jwt.cdp.token", config.get("cdpToken"));
            assertEquals(600_000L, config.get("timeoutMs"));
        }

        @Test
        @DisplayName("Defaults timeout to 30 minutes when null")
        void shouldDefaultTimeout() {
            Map<String, Object> config = SignalConfig.browserTakeover(
                "ses_01", "run_42", "node_browse", "jwt", null);

            assertEquals(1_800_000L, config.get("timeoutMs"));
        }

        @Test
        @DisplayName("Typed accessors return the takeover-specific fields")
        void typedAccessors() {
            Map<String, Object> config = SignalConfig.browserTakeover(
                "ses_01", "run_42", "node_browse", "jwt.cdp.token", Duration.ofMinutes(5));

            assertEquals(SignalType.BROWSER_USER_TAKEOVER, SignalConfig.getType(config));
            assertEquals("ses_01", SignalConfig.getSessionId(config));
            assertEquals("run_42", SignalConfig.getRunId(config));
            assertEquals("node_browse", SignalConfig.getNodeId(config));
            assertEquals("jwt.cdp.token", SignalConfig.getCdpToken(config));
            assertTrue(SignalConfig.isBlocking(config));
            assertEquals(300_000L, SignalConfig.getTimeoutMs(config));
        }

        @Test
        @DisplayName("Typed accessors handle null config defensively")
        void typedAccessorsHandleNull() {
            assertNull(SignalConfig.getSessionId(null));
            assertNull(SignalConfig.getRunId(null));
            assertNull(SignalConfig.getNodeId(null));
            assertNull(SignalConfig.getCdpToken(null));
        }
    }
}
