package com.apimarketplace.orchestrator.domain.execution;

import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SignalWaitEntity")
class SignalWaitEntityTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-04T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @Nested
    @DisplayName("Factory: forTimer()")
    class ForTimerTests {

        @Test
        @DisplayName("Should create timer entity with correct fields")
        void shouldCreateTimerEntity() {
            SignalWaitEntity entity = SignalWaitEntity.forTimer(
                "run-1", "0", "core:wait_1",
                "trigger:webhook", 5, 60_000, FIXED_CLOCK);

            assertEquals("run-1", entity.getRunId());
            assertEquals("0", entity.getItemId());
            assertEquals("core:wait_1", entity.getNodeId());
            assertEquals("trigger:webhook", entity.getDagTriggerId());
            assertEquals(5, entity.getEpoch());
            assertEquals(SignalType.WAIT_TIMER, entity.getSignalType());
            assertEquals(SignalWaitStatus.PENDING, entity.getStatus());
            assertEquals(FIXED_NOW, entity.getCreatedAt());
            assertEquals(FIXED_NOW.plusMillis(60_000), entity.getExpiresAt());
        }

        @Test
        @DisplayName("Should store duration in signal config")
        void shouldStoreDurationInConfig() {
            SignalWaitEntity entity = SignalWaitEntity.forTimer(
                "run-1", "0", "core:wait_1", null, 0, 30_000, FIXED_CLOCK);

            assertEquals(30_000L, SignalConfig.getDurationMs(entity.getSignalConfig()));
        }
    }

    @Nested
    @DisplayName("Factory: forUserApproval()")
    class ForUserApprovalTests {

        @Test
        @DisplayName("Should create approval entity with correct fields")
        void shouldCreateApprovalEntity() {
            SignalWaitEntity entity = SignalWaitEntity.forUserApproval(
                "run-2", "0", "core:approval_1",
                "trigger:manual", 0,
                List.of("manager"), 1,
                Duration.ofHours(24), FIXED_CLOCK);

            assertEquals(SignalType.USER_APPROVAL, entity.getSignalType());
            assertEquals(SignalWaitStatus.PENDING, entity.getStatus());
            assertEquals(FIXED_NOW.plus(Duration.ofHours(24)), entity.getExpiresAt());
            assertEquals(List.of("manager"), SignalConfig.getApproverRoles(entity.getSignalConfig()));
        }

        @Test
        @DisplayName("Should handle null timeout for approval")
        void shouldHandleNullTimeout() {
            SignalWaitEntity entity = SignalWaitEntity.forUserApproval(
                "run-2", "0", "core:approval_1",
                null, 0, null, 1,
                null, FIXED_CLOCK);

            assertNull(entity.getExpiresAt());
        }
    }

    @Nested
    @DisplayName("Factory: forWebhookWait()")
    class ForWebhookWaitTests {

        @Test
        @DisplayName("Should create webhook entity with token in config")
        void shouldCreateWebhookEntity() {
            SignalWaitEntity entity = SignalWaitEntity.forWebhookWait(
                "run-3", "0", "core:webhook_1",
                null, 0,
                "signed-jwt-token", Duration.ofHours(1), FIXED_CLOCK);

            assertEquals(SignalType.WEBHOOK_WAIT, entity.getSignalType());
            assertEquals("signed-jwt-token", SignalConfig.getWebhookToken(entity.getSignalConfig()));
            assertEquals(FIXED_NOW.plus(Duration.ofHours(1)), entity.getExpiresAt());
        }
    }

    @Nested
    @DisplayName("Business methods")
    class BusinessMethodTests {

        @Test
        @DisplayName("Should correctly report status")
        void shouldReportStatusCorrectly() {
            SignalWaitEntity entity = new SignalWaitEntity();

            entity.setStatus(SignalWaitStatus.PENDING);
            assertTrue(entity.isPending());
            assertFalse(entity.isClaimed());
            assertFalse(entity.isResolved());
            assertFalse(entity.isCancelled());
            assertTrue(entity.isActive());

            entity.setStatus(SignalWaitStatus.CLAIMED);
            assertFalse(entity.isPending());
            assertTrue(entity.isClaimed());
            assertTrue(entity.isActive());

            entity.setStatus(SignalWaitStatus.RESOLVED);
            assertFalse(entity.isPending());
            assertTrue(entity.isResolved());
            assertFalse(entity.isActive());

            entity.setStatus(SignalWaitStatus.CANCELLED);
            assertTrue(entity.isCancelled());
            assertFalse(entity.isActive());
        }

        @Test
        @DisplayName("Should check expiration correctly")
        void shouldCheckExpiration() {
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setExpiresAt(FIXED_NOW.plusSeconds(60));

            // Before expiration
            assertFalse(entity.isExpired(FIXED_NOW));
            assertFalse(entity.isExpired(FIXED_NOW.plusSeconds(30)));

            // After expiration
            assertTrue(entity.isExpired(FIXED_NOW.plusSeconds(61)));
            assertTrue(entity.isExpired(FIXED_NOW.plusSeconds(120)));
        }

        @Test
        @DisplayName("Should handle null expiresAt (never expires)")
        void shouldHandleNullExpiration() {
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setExpiresAt(null);

            assertFalse(entity.isExpired(FIXED_NOW));
            assertFalse(entity.isExpired(FIXED_NOW.plusSeconds(999999)));
        }

        @Test
        @DisplayName("F9: setResolvedBy caps at 255 chars (would overflow legacy VARCHAR(100) → signal stuck)")
        void f9ResolvedByIsCappedDefensively() {
            // F9 regression: pre-fix the column was VARCHAR(100) and the setter
            // was a bare assignment. A Keycloak federated id like
            // "b:long-org-id-from-sso:long-user-uuid-with-claims" could exceed
            // 100 chars in prod; Hibernate flush threw DataIntegrityViolation,
            // the resolveSignal transaction rolled back, the signal stayed
            // CLAIMED forever → the workflow at the awaiting-signal node
            // was indistinguishably-stuck from a legitimately pending approval.
            // V188 widened the column to VARCHAR(255); this setter cap is
            // defense-in-depth so a future migration narrowing the column
            // cannot re-introduce the path.
            SignalWaitEntity entity = new SignalWaitEntity();
            String longId = "b:" + "x".repeat(300);
            entity.setResolvedBy(longId);
            assertTrue(entity.getResolvedBy().length() <= 255,
                    "Cap must hold - got " + entity.getResolvedBy().length());
            assertTrue(entity.getResolvedBy().startsWith("b:"),
                    "Prefix preserved so audit can identify the federation source");
        }

        @Test
        @DisplayName("F9: null and short resolvedBy values pass through unchanged (no allocation)")
        void f9ResolvedByPreservesNormalValues() {
            SignalWaitEntity entity = new SignalWaitEntity();
            entity.setResolvedBy(null);
            assertNull(entity.getResolvedBy());

            String normalUserId = "user-12345";
            entity.setResolvedBy(normalUserId);
            assertEquals(normalUserId, entity.getResolvedBy(),
                    "Normal-length value must pass through identity - hot path is zero-allocation");
        }
    }
}
