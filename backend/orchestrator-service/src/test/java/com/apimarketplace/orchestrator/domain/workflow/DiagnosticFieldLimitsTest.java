package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract pins for {@link DiagnosticFieldLimits} - the F4/F1-bundle-2-prep
 * cap utility for identifier columns on {@code workflow_step_data}.
 *
 * <p>These tests are CONTRACT pins (not silent-drop regression guards). The
 * silent-drop regression is covered end-to-end by the entity setter tests
 * in {@code WorkflowStepDataEntityTest}.
 */
@DisplayName("DiagnosticFieldLimits")
class DiagnosticFieldLimitsTest {

    @Nested
    @DisplayName("cap() - plain truncation for non-indexed columns")
    class CapPlain {

        @Test
        @DisplayName("null passes through unchanged (fast-path)")
        void nullPassThrough() {
            assertNull(DiagnosticFieldLimits.cap(null, 200));
        }

        @Test
        @DisplayName("Value within budget returns identical reference (zero-allocation hot path)")
        void withinBudgetIdentity() {
            String s = "short_value";
            assertSame(s, DiagnosticFieldLimits.cap(s, 200));
        }

        @Test
        @DisplayName("Oversized value is truncated with the …[truncated,was=N] marker")
        void oversizedTruncatedWithMarker() {
            String s = "x".repeat(500);
            String capped = DiagnosticFieldLimits.cap(s, 220);
            assertTrue(capped.length() <= 220);
            assertTrue(capped.endsWith("…[truncated,was=500]"));
            assertTrue(capped.startsWith("x"));
        }

        @Test
        @DisplayName("Already-truncated value is idempotent - no double-suffix stacking")
        void idempotent() {
            String once = DiagnosticFieldLimits.cap("z".repeat(500), 220);
            String twice = DiagnosticFieldLimits.cap(once, 220);
            assertSame(once, twice, "Re-cap on already-truncated input must return the same reference");
        }
    }

    @Nested
    @DisplayName("capWithCollisionHash() - hash-suffix for indexed columns")
    class CapHashed {

        @Test
        @DisplayName("trigger:default sentinel is preserved unchanged")
        void defaultTriggerSentinelPreserved() {
            String s = "trigger:default";
            assertSame(s, DiagnosticFieldLimits.capWithCollisionHash(s, 200));
        }

        @Test
        @DisplayName("Two distinct long values differing past the cap get DIFFERENT truncated keys (no collision)")
        void distinctLongValuesDoNotCollide() {
            // Both share the first 300 chars, then diverge - pre-hash the
            // truncated keys would collide on the unique index, silently
            // dropping the second INSERT via ON CONFLICT DO NOTHING.
            String a = "y".repeat(300) + "_A";
            String b = "y".repeat(300) + "_B";

            String aCapped = DiagnosticFieldLimits.capWithCollisionHash(a, 200);
            String bCapped = DiagnosticFieldLimits.capWithCollisionHash(b, 200);

            assertNotEquals(aCapped, bCapped,
                    "F4/F1-bundle-2 collision-safety contract: the hash suffix must differentiate "
                            + "two distinct values that share their leading prefix. Without this, "
                            + "the unique index v6 would treat them as duplicates and drop one silently.");
            assertTrue(aCapped.matches(".*hash=[0-9a-f]{8}\\]$"));
            assertTrue(bCapped.matches(".*hash=[0-9a-f]{8}\\]$"));
        }

        @Test
        @DisplayName("Idempotent - already-hashed value is preserved")
        void hashedIsIdempotent() {
            String once = DiagnosticFieldLimits.capWithCollisionHash("k".repeat(500), 200);
            String twice = DiagnosticFieldLimits.capWithCollisionHash(once, 200);
            assertSame(once, twice);
        }

        @Test
        @DisplayName("null passes through")
        void nullPassThrough() {
            assertNull(DiagnosticFieldLimits.capWithCollisionHash(null, 200));
        }

        @Test
        @DisplayName("Cap respects the requested limit even when the suffix nearly exhausts it")
        void capRespectsLimit() {
            String s = "q".repeat(1000);
            String capped = DiagnosticFieldLimits.capWithCollisionHash(s, 50);
            assertTrue(capped.length() <= 50,
                    "Total length must not exceed the cap - got " + capped.length());
            assertTrue(capped.contains("hash="));
        }
    }

    @Nested
    @DisplayName("Cap constants match the V189 schema (VARCHAR(2000) + CHECK ≤500 on indexed cols)")
    class Constants {
        @Test
        @DisplayName("Indexed columns capped at 500 - matches V189 CHECK constraint, leaves headroom for 33-char hash suffix")
        void indexedCapsMatchCheckConstraint() {
            // V189 added CHECK (length(<col>) <= 500) NOT VALID on each.
            // Cap = 500 keeps suffixed output ≤ 533 → well within
            // VARCHAR(2000) and respects the CHECK ceiling.
            assertEquals(500, DiagnosticFieldLimits.STEP_ALIAS_MAX);
            assertEquals(500, DiagnosticFieldLimits.TRIGGER_ID_MAX);
            assertEquals(500, DiagnosticFieldLimits.NORMALIZED_KEY_MAX);
        }

        @Test
        @DisplayName("Non-indexed columns capped at 1 000 - generous on widened VARCHAR(2000) without CHECK")
        void nonIndexedCaps() {
            assertEquals(1_000, DiagnosticFieldLimits.TOOL_ID_MAX);
            assertEquals(1_000, DiagnosticFieldLimits.LOOP_ID_MAX);
            assertEquals(1_000, DiagnosticFieldLimits.SKIP_SOURCE_NODE_MAX);
        }

        @Test
        @DisplayName("run_id cap = 200 - matches V189 CHECK on system-generated run id")
        void runIdCap() {
            assertEquals(200, DiagnosticFieldLimits.RUN_ID_MAX);
        }
    }
}
